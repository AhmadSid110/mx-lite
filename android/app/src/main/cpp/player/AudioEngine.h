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

    bool open(const char* path);
    void start();
    void stop();
    void seekUs(int64_t us);

private:
    // ───────── Core loops ─────────
    void decodeLoop();

    // ───────── OpenSL ES ─────────
    bool setupOpenSL();
    void cleanupOpenSL();

    static void bufferQueueCallback(
        SLAndroidSimpleBufferQueueItf bq,
        void* context
    );

    // ───────── Codec cleanup ─────────
    void cleanupCodec();

    // ───────── Clock ─────────
    Clock* clock_;

    // ───────── MediaCodec / Extractor ─────────
    AMediaExtractor* extractor_ = nullptr;
    AMediaCodec* codec_ = nullptr;
    AMediaFormat* format_ = nullptr;

    // ───────── OpenSL ES objects ─────────
    SLObjectItf engineObj_ = nullptr;
    SLEngineItf engine_ = nullptr;

    SLObjectItf outputMix_ = nullptr;

    SLObjectItf playerObj_ = nullptr;
    SLPlayItf player_ = nullptr;
    SLAndroidSimpleBufferQueueItf bufferQueue_ = nullptr;

    // ───────── Playback state ─────────
    std::atomic<bool> running_{false};
    std::thread decodeThread_;

    // ───────── Audio format (MANDATORY) ─────────
    int32_t sampleRate_ = 0;        // Hz
    int32_t channelCount_ = 0;      // 1 or 2
    int32_t bytesPerFrame_ = 0;     // channels * 2 bytes

    // ───────── Timing ─────────
    int64_t lastPtsUs_ = 0;         // decoder PTS (NOT master clock)
};