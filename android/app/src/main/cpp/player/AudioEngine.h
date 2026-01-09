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
    void seekUs(int64_t us);

private:
    void decodeLoop();
    bool setupAAudio();
    void cleanupCodec();
    void cleanupAAudio();

    // Media
    AMediaExtractor* extractor_ = nullptr;
    AMediaCodec* codec_ = nullptr;
    AMediaFormat* format_ = nullptr;

    // AAudio
    AAudioStream* stream_ = nullptr;

    // Clock (MASTER)
    Clock* clock_;

    // State
    std::atomic<bool> running_{false};
    std::thread decodeThread_;

    // Format
    int sampleRate_ = 44100;
    int channelCount_ = 2;
};