#pragma once

#include <media/NdkMediaExtractor.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>   // ✅ REQUIRED
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

    bool open(const char* path);
    void start();
    void stop();

    // ⚠️ MUST match cpp exactly
    void seekUs(int64_t us);

private:
    void decodeLoop();

    bool setupOpenSL();
    void cleanupOpenSL();
    void cleanupCodec();

    static void bufferQueueCallback(
        SLAndroidSimpleBufferQueueItf bq,
        void* context
    );

private:
    /* ───────── MASTER CLOCK ───────── */
    Clock* clock_;

    /* ───────── MediaCodec ───────── */
    AMediaExtractor* extractor_ = nullptr;
    AMediaCodec* codec_ = nullptr;
    AMediaFormat* format_ = nullptr;

    // 🔑 REQUIRED: decoder output PCM format
    int pcmEncoding_ = AMEDIAFORMAT_PCM_ENCODING_PCM_16BIT;

    /* ───────── OpenSL ES ───────── */
    SLObjectItf engineObj_ = nullptr;
    SLEngineItf engine_ = nullptr;
    SLObjectItf outputMix_ = nullptr;
    SLObjectItf playerObj_ = nullptr;
    SLPlayItf player_ = nullptr;
    SLAndroidSimpleBufferQueueItf bufferQueue_ = nullptr;

    /* ───────── State ───────── */
    std::atomic<bool> running_{false};
    std::thread decodeThread_;
    std::atomic<int> buffersAvailable_{0};

    /* ───────── Audio format ───────── */
    int sampleRate_ = 44100;
    int channelCount_ = 2;
};