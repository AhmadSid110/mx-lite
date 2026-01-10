#include "AudioEngine.h"
#include "AudioDebug.h"

#include <aaudio/AAudio.h>
#include <media/NdkMediaExtractor.h>
#include <media/NdkMediaCodec.h>

#include <android/log.h>
#include <algorithm>
#include <thread>
#include <chrono>
#include <cstring>

#define LOG_TAG "AudioEngine"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern AudioDebug gAudioDebug;

/* ===================== Lifecycle ===================== */

AudioEngine::AudioEngine(Clock* clock)
        : clock_(clock) {
    gAudioDebug.engineCreated.store(true);
}

AudioEngine::~AudioEngine() {
    stop();
    cleanupAAudio();

    if (codec_) {
        AMediaCodec_stop(codec_);
        AMediaCodec_delete(codec_);
        codec_ = nullptr;
    }
    if (extractor_) {
        AMediaExtractor_delete(extractor_);
        extractor_ = nullptr;
    }
    if (format_) {
        AMediaFormat_delete(format_);
        format_ = nullptr;
    }
}

/* ===================== Open ===================== */

bool AudioEngine::open(const char* /*path*/) {
    if (!setupAAudio()) {
        LOGE("AAudio setup failed");
        return false;
    }
    return true;
}

/* ===================== Start / Stop ===================== */

void AudioEngine::start() {
    if (!stream_) return;

    running_.store(true);

    AAudioStream_requestStart(stream_);

    aaudio_stream_state_t state = AAudioStream_getState(stream_);
    if (state == AAUDIO_STREAM_STATE_STARTED) {
        gAudioDebug.aaudioStarted.store(true);
    } else {
        LOGE("AAudio failed to reach STARTED state (%d)", state);
    }
}

void AudioEngine::stop() {
    running_.store(false);

    if (decodeThread_.joinable()) {
        decodeThread_.join();
    }

    if (stream_) {
        AAudioStream_requestStop(stream_);
    }
}

/* ===================== SEEK (FIXED) ===================== */
/* REQUIRED for JNI + linker correctness */

void AudioEngine::seekUs(int64_t us) {
    stop();

    // Reset ring buffer
    readPos_.store(0);
    writePos_.store(0);
    std::fill(ring_.begin(), ring_.end(), 0);

    // Reset clock
    if (clock_) {
        clock_->setUs(us);
    }

    // Media seek (safe even if null)
    if (extractor_) {
        AMediaExtractor_seekTo(
                extractor_,
                us,
                AMEDIAEXTRACTOR_SEEK_CLOSEST_SYNC
        );
    }

    if (codec_) {
        AMediaCodec_flush(codec_);
    }

    start();
}

/* ===================== AAudio Setup ===================== */

bool AudioEngine::setupAAudio() {
    AAudioStreamBuilder* builder = nullptr;

    if (AAudio_createStreamBuilder(&builder) != AAUDIO_OK) {
        LOGE("Failed to create AAudio builder");
        return false;
    }

    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED); // IMPORTANT
    AAudioStreamBuilder_setPerformanceMode(
            builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);

    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setChannelCount(builder, 2);
    AAudioStreamBuilder_setSampleRate(builder, 48000);

    AAudioStreamBuilder_setDataCallback(
            builder, audioCallback, this);

    aaudio_result_t res =
            AAudioStreamBuilder_openStream(builder, &stream_);

    AAudioStreamBuilder_delete(builder);

    if (res != AAUDIO_OK || !stream_) {
        LOGE("Failed to open AAudio stream");
        return false;
    }

    sampleRate_   = AAudioStream_getSampleRate(stream_);
    channelCount_ = AAudioStream_getChannelCount(stream_);

    ringFrames_ = sampleRate_; // 1 second buffer
    ring_.resize(ringFrames_ * channelCount_);

    gAudioDebug.aaudioOpened.store(true);
    return true;
}

void AudioEngine::cleanupAAudio() {
    if (stream_) {
        AAudioStream_close(stream_);
        stream_ = nullptr;
    }
}

/* ===================== Ring Buffer ===================== */

int AudioEngine::readPcm(int16_t* out, int frames) {
    int64_t r = readPos_.load(std::memory_order_acquire);
    int64_t w = writePos_.load(std::memory_order_acquire);
    int64_t avail = w - r;

    if (avail <= 0) return 0;

    int toRead = static_cast<int>(std::min<int64_t>(frames, avail));

    for (int f = 0; f < toRead; ++f) {
        size_t base =
                static_cast<size_t>((r + f) % ringFrames_) * channelCount_;
        memcpy(out + f * channelCount_,
               &ring_[base],
               channelCount_ * sizeof(int16_t));
    }

    readPos_.store(r + toRead, std::memory_order_release);
    gAudioDebug.bufferFill.store(w - (r + toRead));

    return toRead;
}

void AudioEngine::writePcmBlocking(const int16_t* in, int frames) {
    for (int f = 0; f < frames && running_.load(); ++f) {
        while ((writePos_.load() - readPos_.load()) >= ringFrames_) {
            std::this_thread::sleep_for(std::chrono::milliseconds(1));
        }

        int64_t w = writePos_.load();
        size_t base =
                static_cast<size_t>(w % ringFrames_) * channelCount_;

        memcpy(&ring_[base],
               in + f * channelCount_,
               channelCount_ * sizeof(int16_t));

        writePos_.fetch_add(1);
    }

    gAudioDebug.bufferFill.store(
            writePos_.load() - readPos_.load());
}

/* ===================== Decoder Thread ===================== */
/* Placeholder – debug only */

void AudioEngine::decodeLoop() {
    while (running_.load()) {
        // MediaCodec decode would go here
        std::this_thread::sleep_for(std::chrono::milliseconds(10));
    }
}

/* ===================== AAudio Callback ===================== */

aaudio_data_callback_result_t AudioEngine::audioCallback(
        AAudioStream*,
        void* userData,
        void* audioData,
        int32_t numFrames) {

    auto* engine = static_cast<AudioEngine*>(userData);

    gAudioDebug.callbackCalled.store(true);

    auto* out = static_cast<int16_t*>(audioData);
    int read = engine->readPcm(out, numFrames);

    if (read < numFrames) {
        memset(out + read * engine->channelCount_,
               0,
               (numFrames - read) *
               engine->channelCount_ * sizeof(int16_t));
    }

    if (engine->clock_) {
        engine->clock_->addUs(
                (int64_t)numFrames * 1000000LL / engine->sampleRate_);
    }

    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}