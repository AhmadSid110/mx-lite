#pragma once

#include <media/NdkMediaExtractor.h>
#include <media/NdkMediaCodec.h>
#include <aaudio/AAudio.h>

#include <cstdint>

#include "Clock.h"

/*
 * CALLBACK-DRIVEN AUDIO ENGINE
 *
 * RULES:
 * - AAudio callback is the MASTER clock driver
 * - No decode thread
 * - No start()/stop()
 * - No seek (for now)
 * - open() == start playback
 */

class AudioEngine {
public:
    explicit AudioEngine(Clock* clock);
    ~AudioEngine();

    // Opens media + starts audio immediately
    bool open(const char* path);

private:
    // AAudio
    bool setupAAudio();
    void cleanupAAudio();

    // MediaCodec
    void cleanupCodec();

    // AAudio callback
    static aaudio_data_callback_result_t audioCallback(
        AAudioStream* stream,
        void* userData,
        void* audioData,
        int32_t numFrames
    );

private:
    // MASTER CLOCK (audio-driven)
    Clock* clock_;

    // Media
    AMediaExtractor* extractor_ = nullptr;
    AMediaCodec* codec_ = nullptr;
    AMediaFormat* format_ = nullptr;

    // AAudio
    AAudioStream* stream_ = nullptr;

    // Audio format
    int sampleRate_ = 44100;
    int channelCount_ = 2;
};