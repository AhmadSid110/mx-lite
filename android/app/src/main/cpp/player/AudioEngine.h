#pragma once

#include <media/NdkMediaExtractor.h>
#include <media/NdkMediaCodec.h>

#include <SLES/OpenSLES.h>
#include <SLES/OpenSLES_Android.h>

#include <thread>
#include <atomic>
#include <cstdint>

#include "Clock.h"

class AudioEngine {
public:
    explicit AudioEngine(Clock* clock);
    ~AudioEngine();

    // Lifecycle
    bool open(const char* path);
    void start();
    void stop();
    void seekUs(int64_t us);   // safe even if unused for now

private:
    // Internal
    void decodeLoop();
    bool setupOpenSL();
    void cleanupOpenSL();
    void cleanupCodec();

    static void bufferQueueCallback(
        SLAndroidSimpleBufferQueueItf queue,
        void* ctx
    );

private:
    // ───────── Clock ─────────
    Clock* clock_ = nullptr;

    // ───────── MediaCodec ─────────
    AMediaExtractor* extractor_ = nullptr;
    AMediaCodec* codec_ = nullptr;
    AMediaFormat* format_ = nullptr;

    // ───────── OpenSL ES ─────────
    SLObjectItf engineObj_ = nullptr;
    SLEngineItf engine_ = nullptr;

    SLObjectItf outputMix_ = nullptr;

    SLObjectItf playerObj_ = nullptr;
    SLPlayItf player_ = nullptr;
    SLAndroidSimpleBufferQueueItf bufferQueue_ = nullptr;

    // ───────── Threading ─────────
    std::atomic<bool> running_{false};
    std::thread decodeThread_;

    // Buffer queue flow control (CRITICAL)
    std::atomic<int> buffersAvailable_{2};

    // ───────── Audio format ─────────
    int sampleRate_ = 44100;
    int channelCount_ = 2;
};