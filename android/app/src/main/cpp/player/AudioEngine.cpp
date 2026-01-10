#include "AudioEngine.h"
#include "AudioDebug.h"

#include <android/log.h>
#include <algorithm>
#include <thread>
#include <chrono>
#include <cstring>

#define LOG_TAG "AudioEngine"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern AudioDebug gAudioDebug;

/* ───────────────── Lifecycle ───────────────── */

AudioEngine::AudioEngine(Clock* clock)
        : clock_(clock) {
    gAudioDebug.engineCreated.store(true, std::memory_order_release);
}

AudioEngine::~AudioEngine() {
    stop();
    cleanupAAudio();
}

/* ───────────────── Open ───────────────── */

bool AudioEngine::open(const char* /*path*/) {
    if (!setupAAudio()) {
        LOGE("AAudio setup failed");
        return false;
    }
    return true;
}

/* ───────────────── Start / Stop ───────────────── */

void AudioEngine::start() {
    if (!stream_) return;

    aaudio_result_t r = AAudioStream_requestStart(stream_);
    if (r != AAUDIO_OK) {
        LOGE("AAudio start failed: %d", r);
        return;
    }

    /* 🔑 WAIT UNTIL STREAM IS REALLY STARTED */
    aaudio_stream_state_t state = AAUDIO_STREAM_STATE_UNINITIALIZED;
    int retries = 0;

    while (retries < 50) { // ~500ms max
        state = AAudioStream_getState(stream_);
        if (state == AAUDIO_STREAM_STATE_STARTED) {
            gAudioDebug.aaudioStarted.store(true, std::memory_order_release);
            LOGD("AAudio stream STARTED");
            return;
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(10));
        retries++;
    }

    LOGE("AAudio never reached STARTED state (state=%d)", state);
}

void AudioEngine::stop() {
    running_.store(false, std::memory_order_release);

    if (decodeThread_.joinable()) {
        decodeThread_.join();
    }

    if (stream_) {
        AAudioStream_requestStop(stream_);
    }
}

void AudioEngine::seekUs(int64_t us) {
    if (clock_) {
        clock_->setUs(us);
    }
}

/* ───────────────── AAudio Setup ───────────────── */

bool AudioEngine::setupAAudio() {
    AAudioStreamBuilder* builder = nullptr;
    if (AAudio_createStreamBuilder(&builder) != AAUDIO_OK) {
        return false;
    }

    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);

    /* 🔑 CRITICAL FIX: SHARED MODE (NOT EXCLUSIVE) */
    AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);

    AAudioStreamBuilder_setPerformanceMode(
            builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);

    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setChannelCount(builder, 2);
    AAudioStreamBuilder_setSampleRate(builder, 48000);

    AAudioStreamBuilder_setDataCallback(builder, audioCallback, this);

    aaudio_result_t result =
            AAudioStreamBuilder_openStream(builder, &stream_);

    AAudioStreamBuilder_delete(builder);

    if (result != AAUDIO_OK || !stream_) {
        LOGE("AAudio open failed: %d", result);
        return false;
    }

    sampleRate_   = AAudioStream_getSampleRate(stream_);
    channelCount_ = AAudioStream_getChannelCount(stream_);

    /* Ring buffer: 1 second */
    ringFrames_ = sampleRate_;
    ring_.assign(ringFrames_ * channelCount_, 0);

    writePos_.store(0);
    readPos_.store(0);

    gAudioDebug.aaudioOpened.store(true, std::memory_order_release);

    LOGD("AAudio opened sr=%d ch=%d",
         sampleRate_, channelCount_);

    return true;
}

void AudioEngine::cleanupAAudio() {
    if (stream_) {
        AAudioStream_close(stream_);
        stream_ = nullptr;
    }
}

/* ───────────────── Ring Buffer ───────────────── */

int AudioEngine::readPcm(int16_t* out, int frames) {
    int64_t rp = readPos_.load(std::memory_order_acquire);
    int64_t wp = writePos_.load(std::memory_order_acquire);
    int64_t avail = wp - rp;

    if (avail <= 0) return 0;

    int framesRead = (int)std::min<int64_t>(frames, avail);

    for (int f = 0; f < framesRead; ++f) {
        for (int ch = 0; ch < channelCount_; ++ch) {
            size_t idx =
                    ((rp + f) % ringFrames_) * channelCount_ + ch;
            out[f * channelCount_ + ch] = ring_[idx];
        }
    }

    readPos_.store(rp + framesRead, std::memory_order_release);
    gAudioDebug.bufferFill.store((int)(wp - (rp + framesRead)));

    return framesRead;
}

void AudioEngine::writePcmBlocking(const int16_t* in, int frames) {
    for (int f = 0; f < frames; ++f) {
        int64_t wp, rp;
        do {
            wp = writePos_.load(std::memory_order_acquire);
            rp = readPos_.load(std::memory_order_acquire);
            if ((wp - rp) < ringFrames_) break;
            std::this_thread::sleep_for(std::chrono::milliseconds(1));
        } while (true);

        for (int ch = 0; ch < channelCount_; ++ch) {
            size_t idx =
                    (wp % ringFrames_) * channelCount_ + ch;
            ring_[idx] = in[f * channelCount_ + ch];
        }

        writePos_.store(wp + 1, std::memory_order_release);
    }

    gAudioDebug.decoderProduced.store(true, std::memory_order_release);
}

/* ───────────────── Audio Callback ───────────────── */

aaudio_data_callback_result_t AudioEngine::audioCallback(
        AAudioStream*,
        void* userData,
        void* audioData,
        int32_t numFrames) {

    auto* engine = static_cast<AudioEngine*>(userData);
    auto* out = static_cast<int16_t*>(audioData);

    gAudioDebug.callbackCalled.store(true, std::memory_order_release);
    gAudioDebug.callbackCount.fetch_add(1);

    int framesRead = engine->readPcm(out, numFrames);

    if (framesRead < numFrames) {
        std::memset(
                out + framesRead * engine->channelCount_,
                0,
                (numFrames - framesRead) *
                engine->channelCount_ * sizeof(int16_t));
    }

    /* 🔑 CLOCK ADVANCES ONLY IF AUDIO BACKEND IS LIVE */
    if (engine->clock_ &&
        gAudioDebug.aaudioStarted.load(std::memory_order_acquire)) {

        engine->clock_->addUs(
                (int64_t)numFrames * 1'000'000LL /
                engine->sampleRate_);
    }

    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

/* ───────────────── Decoder Loop (stub) ───────────────── */

void AudioEngine::decodeLoop() {
    running_.store(true, std::memory_order_release);

    while (running_.load(std::memory_order_acquire)) {
        std::this_thread::sleep_for(std::chrono::milliseconds(10));
    }
}