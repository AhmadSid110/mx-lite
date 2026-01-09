#pragma once

#include <media/NdkMediaExtractor.h>
#include <media/NdkMediaCodec.h>
#include <aaudio/AAudio.h>

#include <thread>
#include <atomic>
#include <cstdint>
#include <vector>

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
    void decodeLoop();

    bool setupAAudio();
    void cleanupAAudio();
    void cleanupCodec();

private:
    // MASTER CLOCK (audio-driven)
    Clock* clock_;

    // Media
    AMediaExtractor* extractor_ = nullptr;
    AMediaCodec* codec_ = nullptr;
    AMediaFormat* format_ = nullptr;

    // Audio output
    AAudioStream* stream_ = nullptr;

    // Threading
    std::atomic<bool> running_{false};
    std::thread decodeThread_;

    // Audio format
    int sampleRate_ = 44100;
    int channelCount_ = 2;
    int pcmEncoding_ = AMEDIAFORMAT_PCM_ENCODING_PCM_16BIT;

    // Conversion buffer (for PCM_FLOAT → PCM_16)
    std::vector<int16_t> pcm16Buffer_;
};