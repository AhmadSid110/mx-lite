#pragma once

#include <media/NdkMediaExtractor.h>
#include <media/NdkMediaCodec.h>
#include <SLES/OpenSLES.h>
#include <SLES/OpenSLES_Android.h>

#include <thread>
#include <atomic>
#include <vector>
#include <cstdint>

#include "Clock.h"

/**
 * Native audio engine.
 * - Decodes audio using NDK MediaCodec
 * - Outputs PCM using OpenSL ES
 * - Updates master clock ONLY when audio is rendered
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
    // Core logic
    void decodeLoop();

    // OpenSL ES
    bool setupOpenSL();
    void cleanupOpenSL();

    // Codec
    void cleanupCodec();

    // OpenSL buffer callback
    static void bufferQueueCallback(
        SLAndroidSimpleBufferQueueItf bq,
        void* context
    );

private:
    // Master clock (audio-driven)
    Clock* clock_;

    // Media
    AMediaExtractor* extractor_ = nullptr;
    AMediaCodec* codec_ = nullptr;
    AMediaFormat* format_ = nullptr;

    // Audio format
    int sampleRate_ = 44100;
    int channelCount_ = 2;

    // OpenSL ES
    SLObjectItf engineObj_ = nullptr;
    SLEngineItf engine_ = nullptr;
    SLObjectItf outputMix_ = nullptr;
    SLObjectItf playerObj_ = nullptr;
    SLPlayItf player_ = nullptr;
    SLAndroidSimpleBufferQueueItf bufferQueue_ = nullptr;

    // Threading / state
    std::atomic<bool> running_{false};
    std::thread decodeThread_;

    // Buffer management
    std::atomic<int> buffersAvailable_{0};
    std::vector<uint8_t> pcmBuffer_;
};