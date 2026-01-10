#pragma once

#include <aaudio/AAudio.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaExtractor.h>

#include <atomic>
#include <thread>
#include <vector>
#include <cstdint>

class Clock {
public:
    virtual void setUs(int64_t us) = 0;
    virtual void addUs(int64_t us) = 0;
};

class AudioEngine {
public:
    explicit AudioEngine(Clock* clock);
    ~AudioEngine();

    bool open(const char* path);
    void start();
    void stop();
    void seekUs(int64_t us);

    /* ───── DEBUG FLAGS (JNI-visible) ───── */
    bool isAAudioOpened() const;
    bool isAAudioStarted() const;
    bool isCallbackRunning() const;
    int64_t getCallbackCount() const;
    int64_t getFramesPlayed() const;

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
    std::atomic<bool> running_{false};
    std::thread decodeThread_;

    /* Ring buffer */
    std::vector<int16_t> ring_;
    int64_t ringFrames_ = 0;
    std::atomic<int64_t> writePos_{0};
    std::atomic<int64_t> readPos_{0};

    /* DEBUG STATE */
    std::atomic<bool> aaudioOpened_{false};
    std::atomic<bool> aaudioStarted_{false};
    std::atomic<bool> callbackSeen_{false};
    std::atomic<int64_t> callbackCount_{0};
    std::atomic<int64_t> framesPlayed_{0};

    /* Internal */
    bool setupAAudio();
    void cleanupAAudio();

    void decodeLoop();
    void writePcmBlocking(const int16_t* in, int frames);
    int readPcm(int16_t* out, int frames);

    static aaudio_data_callback_result_t audioCallback(
            AAudioStream* stream,
            void* userData,
            void* audioData,
            int32_t numFrames);
};