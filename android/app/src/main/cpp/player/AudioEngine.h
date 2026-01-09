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
    void decodeLoop();
    bool setupOpenSL();
    void cleanupOpenSL();
    void cleanupCodec();

    static void bufferQueueCallback(
        SLAndroidSimpleBufferQueueItf bq, void* ctx
    );

    Clock* clock_;

    AMediaExtractor* extractor_ = nullptr;
    AMediaCodec* codec_ = nullptr;
    AMediaFormat* format_ = nullptr;

    int sampleRate_ = 44100;
    int channelCount_ = 2;

    SLObjectItf engineObj_ = nullptr;
    SLEngineItf engine_ = nullptr;
    SLObjectItf outputMix_ = nullptr;
    SLObjectItf playerObj_ = nullptr;
    SLPlayItf player_ = nullptr;
    SLAndroidSimpleBufferQueueItf bufferQueue_ = nullptr;

    std::atomic<bool> running_{false};
    std::thread decodeThread_;

    std::atomic<int> buffersAvailable_{0};
};