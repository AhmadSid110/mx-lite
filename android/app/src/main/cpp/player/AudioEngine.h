#pragma once

#include <aaudio/AAudio.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaExtractor.h>

#include <atomic>
#include <thread>
#include <vector>
#include <cstdint>

#include "Clock.h"

class AudioEngine {
public:
    explicit AudioEngine(Clock* clock);
    ~AudioEngine();

    bool open(const char* path);
    bool openFd(int fd, int64_t offset, int64_t length);
    void start();
    void pause();
    void stop();
    void seekUs(int64_t us);
    int64_t getClockUs() const;

private:
    /* Media */
    AMediaExtractor* extractor_ = nullptr;
    AMediaCodec* codec_ = nullptr;
    AMediaFormat* format_ = nullptr;

    /* Audio */
    AAudioStream* stream_ = nullptr;
    int32_t sampleRate_ = 0;
    int32_t channelCount_ = 0;

    Clock* clock_ = nullptr;

    /* Threading */
    std::thread decodeThread_;
    std::atomic<bool> isPlaying_{false};
    std::atomic<bool> aaudioStarted_{false};

    /* ───────── Ring Buffer (lock-free) ───────── */
    static constexpr int32_t kRingBufferSize = 192000;
    int16_t ringBuffer_[kRingBufferSize];

    std::atomic<int32_t> writeHead_{0};
    std::atomic<int32_t> readHead_{0};

    /* Internal */
    bool setupAAudio();
    void cleanupAAudio();
    void cleanupMedia();   // ✅ ADD THIS LINE

    void decodeLoop();
    void writePcmBlocking(const int16_t* in, int frames);
    int readPcm(int16_t* out, int frames);

    /* ───────── Helpers ───────── */
    bool writeAudio(const int16_t* data, int32_t samples);
    // `out` is an int16_t buffer sized for samples
    // `samples` = frames * channelCount_
    void renderAudio(int16_t* out, int32_t samples);
     void flushRingBuffer();
    int32_t framesToSamples(int32_t frames) const;

    static aaudio_data_callback_result_t audioCallback(
            AAudioStream* stream,
            void* userData,
            void* audioData,
            int32_t numFrames);
};