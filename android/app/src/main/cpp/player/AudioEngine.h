#pragma once

#include <media/NdkMediaExtractor.h>
#include <media/NdkMediaCodec.h>
#include <aaudio/AAudio.h>

#include <thread>
#include <atomic>
#include <vector>
#include <cstdint>

#include "Clock.h"

/*
 * AudioEngine
 *  - Decoder thread produces PCM16
 *  - AAudio callback consumes PCM16
 *  - Audio callback is MASTER clock
 */
class AudioEngine {
public:
    explicit AudioEngine(Clock* clock);
    ~AudioEngine();

    bool open(const char* path);
    void start();
    void stop();
    void seekUs(int64_t us);

private:
    /* -------- Decoder thread -------- */
    void decodeLoop();

    /* -------- AAudio -------- */
    bool setupAAudio();
    void cleanupAAudio();

    static aaudio_data_callback_result_t audioCallback(
            AAudioStream* stream,
            void* userData,
            void* audioData,
            int32_t numFrames
    );

    /* -------- Ring buffer -------- */
    int readPcm(int16_t* out, int frames);
    int writePcm(const int16_t* in, int frames);

private:
    Clock* clock_;

    /* Media */
    AMediaExtractor* extractor_ = nullptr;
    AMediaCodec* codec_ = nullptr;
    AMediaFormat* format_ = nullptr;

    /* Audio format */
    int sampleRate_ = 44100;
    int channelCount_ = 2;

    /* Decoder thread */
    std::thread decodeThread_;
    std::atomic<bool> running_{false};

    /* Ring buffer (PCM16 frames) */
    std::vector<int16_t> ring_;
    std::atomic<int> writePos_{0};
    std::atomic<int> readPos_{0};
    int ringFrames_ = 0;

    /* AAudio */
    AAudioStream* stream_ = nullptr;
};