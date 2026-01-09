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

class AudioEngine {
public:
    explicit AudioEngine(Clock* clock);
    ~AudioEngine();

    bool open(const char* path);
    void start();
    void stop();
    void seekUs(int64_t us);

private:
    // Decode thread
    void decodeLoop();

    // OpenSL ES
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

    /* ───────── MediaCodec / Extractor ───────── */
    AMediaExtractor* extractor_ = nullptr;
    AMediaCodec* codec_ = nullptr;
    AMediaFormat* format_ = nullptr;

    /* ───────── OpenSL ES ───────── */
    SLObjectItf engineObj_ = nullptr;
    SLEngineItf engine_ = nullptr;
    SLObjectItf outputMix_ = nullptr;
    SLObjectItf playerObj_ = nullptr;
    SLPlayItf player_ = nullptr;
    SLAndroidSimpleBufferQueueItf bufferQueue_ = nullptr;

    /* ───────── Threading / State ───────── */
    std::atomic<bool> running_{false};
    std::thread decodeThread_;

    /* ───────── PCM Buffer Pool (CRITICAL) ───────── */
    static constexpr int kNumBuffers = 4;
    static constexpr int kBufferSize = 8192;

    std::vector<std::vector<uint8_t>> pcmBuffers_;
    std::atomic<int> buffersAvailable_{0};

    /* ───────── Audio Format ───────── */
    int sampleRate_ = 44100;
    int channelCount_ = 2;
};