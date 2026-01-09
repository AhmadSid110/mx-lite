#pragma once

#include <media/NdkMediaExtractor.h>
#include <media/NdkMediaCodec.h>
#include <aaudio/AAudio.h>

#include <thread>
#include <atomic>
#include <cstdint>

#include "Clock.h"

class AudioEngine {
public:
    explicit AudioEngine(Clock* clock);
    ~AudioEngine();

    bool open(const char* path);
    void start();
    void stop();

    // MUST be int64_t (not long)
    void seekUs(int64_t us);

private:
    // Decode thread (audio master)
    void decodeLoop();

    // AAudio
    bool setupAAudio();
    void cleanupAAudio();

    // Media
    void cleanupCodec();

private:
    // Master clock
    Clock* clock_ = nullptr;

    // Media
    AMediaExtractor* extractor_ = nullptr;
    AMediaCodec* codec_ = nullptr;
    AMediaFormat* format_ = nullptr;

    // AAudio
    AAudioStream* stream_ = nullptr;

    // Threading
    std::atomic<bool> running_{false};
    std::thread decodeThread_;

    // Audio format
    int sampleRate_ = 44100;
    int channelCount_ = 2;
};