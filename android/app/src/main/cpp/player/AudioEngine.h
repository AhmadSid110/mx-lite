#pragma once

#include <media/NdkMediaExtractor.h>
#include <media/NdkMediaCodec.h>
#include <aaudio/AAudio.h>
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
    // AAudio callback
    static aaudio_data_callback_result_t
    audioCallback(
            AAudioStream* stream,
            void* userData,
            void* audioData,
            int32_t numFrames
    );

    aaudio_data_callback_result_t
    onAudioCallback(void* audioData, int32_t numFrames);

    void cleanupCodec();
    void cleanupAAudio();

private:
    Clock* clock_;

    AMediaExtractor* extractor_ = nullptr;
    AMediaCodec* codec_ = nullptr;
    AMediaFormat* format_ = nullptr;

    AAudioStream* stream_ = nullptr;

    int sampleRate_ = 44100;
    int channelCount_ = 2;

    std::atomic<bool> running_{false};
};