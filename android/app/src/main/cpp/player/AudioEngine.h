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

    // Diagnostics
    bool hasAudioTrack() const { return hasAudioTrack_; }

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

    // HARD OUTPUT / DECODE GATES
    std::atomic<bool> audioOutputEnabled_{false};
    std::atomic<bool> decodeEnabled_{false};
    
    // Note: We removed the wait-for-callback logic, so this might be debug-only now
    std::atomic<bool> aaudioStarted_{false}; 

    /* ───────── Ring Buffer (lock-free) ───────── */
    static constexpr int32_t kRingBufferSize = 192000;
    int16_t ringBuffer_[kRingBufferSize];

    std::atomic<int32_t> writeHead_{0};
    std::atomic<int32_t> readHead_{0};
    
    /* ───────── Time Offset Variables (THE FIX) ───────── */
    // Tracks where we seeked to (e.g., 10:00)
    std::atomic<int64_t> seekOffsetUs_{0};  
    // Tracks hardware frame count at the moment of seek
    int64_t startFramePosition_ = 0;        

    /* Internal */
    bool setupAAudio();
    void cleanupAAudio();
    void cleanupMedia();

    // Internal state
    bool hasAudioTrack_ = false;

    // Diagnostics / state (public accessor declared in public section)
    
    void decodeLoop();

    // These helpers seem legacy or debug, keeping them if you use them internally
    
    // These helpers seem legacy or debug, keeping them if you use them internally
    void writePcmBlocking(const int16_t* in, int frames);
    int readPcm(int16_t* out, int frames);

    /* ───────── Helpers ───────── */
    bool writeAudio(const int16_t* data, int32_t samples);
    void renderAudio(int16_t* out, int32_t samples);
    void flushRingBuffer();
    
    int32_t framesToSamples(int32_t frames) const;

    static aaudio_data_callback_result_t dataCallback(
            AAudioStream* stream,
            void* userData,
            void* audioData,
            int32_t numFrames);
};
