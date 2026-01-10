#pragma once

#include <media/NdkMediaExtractor.h>
#include <media/NdkMediaCodec.h>
#include <aaudio/AAudio.h>

#include <atomic>
#include <thread>
#include <vector>
#include <cstdint>

#include "Clock.h"

class AudioEngine {
public:
    explicit AudioEngine(Clock* clock);
    ~AudioEngine();

    bool open(const char* path);
    void start();
    void stop();
    void seekUs(int64_t us);

private:
    /* ---------- Decoder ---------- */
    void decodeLoop();

    /* ---------- AAudio ---------- */
    bool setupAAudio();
    void cleanupAAudio();

    static aaudio_data_callback_result_t audioCallback(
            AAudioStream* stream,
            void* userData,
            void* audioData,
            int32_t numFrames
    );

    static void errorCallback(
            AAudioStream* stream,
            void* userData,
            aaudio_result_t error
    );

    /* ---------- Ring Buffer ---------- */
    void writePcmBlocking(const int16_t* in, int frames);
    int readPcm(int16_t* out, int frames);

    inline int64_t getWritePos() const;
    inline int64_t getReadPos() const;

private:
    Clock* clock_;

    /* Media */
    AMediaExtractor* extractor_ = nullptr;
    AMediaCodec* codec_ = nullptr;
    AMediaFormat* format_ = nullptr;

    /* Audio */
    AAudioStream* stream_ = nullptr;
    int sampleRate_ = 48000;
    int channelCount_ = 2;

    /* Threads */
    std::atomic<bool> running_{false};
    std::atomic<bool> needsRestart_{false};
    std::thread decodeThread_;

    /* Ring buffer */
    std::vector<int16_t> ring_;
    int64_t ringFrames_ = 0;

    std::atomic<int64_t> writePos_{0};
    std::atomic<int64_t> readPos_{0};
};