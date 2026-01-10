#include "AudioEngine.h"

#include <android/log.h>
#include <cstring>
#include <cmath>
#include <chrono>
#include <thread>

#define LOG_TAG "AudioEngine"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/* ───────────────── PCM helper ───────────────── */

static inline int16_t floatToPcm16(float v) {
    if (v > 1.0f) v = 1.0f;
    if (v < -1.0f) v = -1.0f;
    return static_cast<int16_t>(v * 32767.0f);
}

/* ───────────────── Lifecycle ───────────────── */

AudioEngine::AudioEngine(Clock* clock)
        : clock_(clock) {}

AudioEngine::~AudioEngine() {
    stop();
    cleanupAAudio();

    if (codec_) {
        AMediaCodec_stop(codec_);
        AMediaCodec_delete(codec_);
    }
    if (extractor_) {
        AMediaExtractor_delete(extractor_);
    }
    if (format_) {
        AMediaFormat_delete(format_);
    }
}

/* ───────────────── Open ───────────────── */

bool AudioEngine::open(const char* path) {
    extractor_ = AMediaExtractor_new();
    if (!extractor_) return false;

    if (AMediaExtractor_setDataSource(extractor_, path) != AMEDIA_OK)
        return false;

    int audioTrack = -1;
    const size_t tracks = AMediaExtractor_getTrackCount(extractor_);

    for (size_t i = 0; i < tracks; i++) {
        AMediaFormat* fmt = AMediaExtractor_getTrackFormat(extractor_, i);
        const char* mime = nullptr;
        AMediaFormat_getString(fmt, AMEDIAFORMAT_KEY_MIME, &mime);

        if (mime && strncmp(mime, "audio/", 6) == 0) {
            format_ = fmt;
            audioTrack = (int)i;
            break;
        }
        AMediaFormat_delete(fmt);
    }

    if (audioTrack < 0) return false;

    AMediaExtractor_selectTrack(extractor_, audioTrack);
    AMediaFormat_getInt32(format_, AMEDIAFORMAT_KEY_SAMPLE_RATE, &sampleRate_);
    AMediaFormat_getInt32(format_, AMEDIAFORMAT_KEY_CHANNEL_COUNT, &channelCount_);

    const char* mime = nullptr;
    AMediaFormat_getString(format_, AMEDIAFORMAT_KEY_MIME, &mime);

    codec_ = AMediaCodec_createDecoderByType(mime);
    if (!codec_) return false;

    if (AMediaCodec_configure(codec_, format_, nullptr, nullptr, 0) != AMEDIA_OK)
        return false;

    /* Ring buffer = 500 ms */
    ringFrames_ = sampleRate_ / 2;
    ring_.resize(ringFrames_ * channelCount_);

    return setupAAudio() && AMediaCodec_start(codec_) == AMEDIA_OK;
}

/* ───────────────── AAudio ───────────────── */

void AudioEngine::errorCallback(
        AAudioStream* /* stream */,
        void* userData,
        aaudio_result_t error) {
    auto* self = static_cast<AudioEngine*>(userData);
    LOGE("AAudio stream error: %d", error);

    // Set flag for stream recovery - do NOT call setupAAudio() from within
    // the callback as it runs on the audio thread and may cause deadlocks
    if (error == AAUDIO_ERROR_DISCONNECTED) {
        self->needsRestart_.store(true, std::memory_order_release);
    }
}

bool AudioEngine::setupAAudio() {
    AAudioStreamBuilder* builder = nullptr;
    aaudio_result_t result = AAudio_createStreamBuilder(&builder);
    if (result != AAUDIO_OK) {
        LOGE("Failed to create AAudio stream builder: %d", result);
        return false;
    }

    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setChannelCount(builder, channelCount_);
    AAudioStreamBuilder_setSampleRate(builder, sampleRate_);
    AAudioStreamBuilder_setPerformanceMode(
            builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);

    // Use SHARED mode for broader device compatibility
    AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);

    AAudioStreamBuilder_setDataCallback(
            builder, audioCallback, this);
    AAudioStreamBuilder_setErrorCallback(
            builder, errorCallback, this);

    result = AAudioStreamBuilder_openStream(builder, &stream_);
    AAudioStreamBuilder_delete(builder);

    if (result != AAUDIO_OK) {
        LOGE("Failed to open AAudio stream: %d", result);
        return false;
    }

    result = AAudioStream_requestStart(stream_);
    if (result != AAUDIO_OK) {
        LOGE("Failed to start AAudio stream: %d", result);
        AAudioStream_close(stream_);
        stream_ = nullptr;
        return false;
    }

    // Wait for the stream to actually start (max ~100ms)
    aaudio_stream_state_t state = AAudioStream_getState(stream_);
    aaudio_stream_state_t nextState = AAUDIO_STREAM_STATE_UNINITIALIZED;
    int retries = 10;
    while (state != AAUDIO_STREAM_STATE_STARTED && retries-- > 0) {
        result = AAudioStream_waitForStateChange(
                stream_, state, &nextState, 10'000'000LL); // 10ms timeout
        if (result != AAUDIO_OK) {
            LOGE("Error waiting for stream state change: %d", result);
            break;
        }
        state = nextState;
    }

    if (state != AAUDIO_STREAM_STATE_STARTED) {
        LOGE("AAudio stream failed to reach started state, current state: %d", state);
        AAudioStream_close(stream_);
        stream_ = nullptr;
        return false;
    }

    LOGD("AAudio stream started successfully, sample rate: %d, channels: %d",
         AAudioStream_getSampleRate(stream_),
         AAudioStream_getChannelCount(stream_));

    return true;
}

void AudioEngine::cleanupAAudio() {
    if (stream_) {
        AAudioStream_requestStop(stream_);
        AAudioStream_close(stream_);
        stream_ = nullptr;
    }
}

/* ───────────────── Start / Stop ───────────────── */

void AudioEngine::start() {
    running_ = true;
    clock_->setUs(0);
    decodeThread_ = std::thread(&AudioEngine::decodeLoop, this);
}

void AudioEngine::stop() {
    running_ = false;
    if (decodeThread_.joinable())
        decodeThread_.join();
}

/* ───────────────── Seek ───────────────── */

void AudioEngine::seekUs(int64_t us) {
    stop();
    AMediaExtractor_seekTo(extractor_, us, AMEDIAEXTRACTOR_SEEK_CLOSEST_SYNC);
    AMediaCodec_flush(codec_);
    writePos_.store(0);
    readPos_.store(0);
    clock_->setUs(us);
    start();
}

/* ───────────────── Ring Buffer ───────────────── */

inline int64_t AudioEngine::getWritePos() const {
    return writePos_.load(std::memory_order_acquire);
}

inline int64_t AudioEngine::getReadPos() const {
    return readPos_.load(std::memory_order_acquire);
}

void AudioEngine::writePcmBlocking(const int16_t* in, int frames) {
    int written = 0;

    while (written < frames && running_) {
        int64_t wp = getWritePos();
        int64_t rp = getReadPos();
        int64_t used = wp - rp;
        int64_t free = ringFrames_ - used;

        if (free <= 0) {
            std::this_thread::sleep_for(std::chrono::milliseconds(2));
            continue;
        }

        int chunk = std::min((int)free, frames - written);

        for (int i = 0; i < chunk * channelCount_; i++) {
            ring_[(wp * channelCount_ + i) % ring_.size()] =
                    in[written * channelCount_ + i];
        }

        writePos_.store(wp + chunk, std::memory_order_release);
        written += chunk;
    }
}

int AudioEngine::readPcm(int16_t* out, int frames) {
    int64_t rp = getReadPos();
    int64_t wp = getWritePos();
    int64_t avail = wp - rp;

    if (avail <= 0) return 0;

    int chunk = std::min((int)avail, frames);

    for (int i = 0; i < chunk * channelCount_; i++) {
        out[i] = ring_[(rp * channelCount_ + i) % ring_.size()];
    }

    readPos_.store(rp + chunk, std::memory_order_release);
    return chunk;
}

/* ───────────────── Decoder Thread ───────────────── */

void AudioEngine::decodeLoop() {
    AMediaCodecBufferInfo info;
    std::vector<int16_t> tmp;

    while (running_) {
        // Check if stream needs restart (e.g., after disconnect error)
        if (needsRestart_.load(std::memory_order_acquire)) {
            LOGD("Restarting AAudio stream due to disconnect");
            cleanupAAudio();
            if (!setupAAudio()) {
                LOGE("Failed to restart AAudio stream");
                std::this_thread::sleep_for(std::chrono::milliseconds(100));
                continue;
            }
            needsRestart_.store(false, std::memory_order_release);
        }

        ssize_t in = AMediaCodec_dequeueInputBuffer(codec_, 2000);
        if (in >= 0) {
            size_t cap;
            uint8_t* buf = AMediaCodec_getInputBuffer(codec_, in, &cap);
            ssize_t sz = AMediaExtractor_readSampleData(extractor_, buf, cap);

            if (sz < 0) {
                AMediaCodec_queueInputBuffer(codec_, in, 0, 0, 0,
                                             AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
                break;
            }

            AMediaCodec_queueInputBuffer(
                    codec_, in, 0, sz,
                    AMediaExtractor_getSampleTime(extractor_), 0);

            AMediaExtractor_advance(extractor_);
        }

        ssize_t out = AMediaCodec_dequeueOutputBuffer(codec_, &info, 2000);
        if (out >= 0 && info.size > 0) {
            uint8_t* raw = AMediaCodec_getOutputBuffer(codec_, out, nullptr);

            bool isFloat =
                    info.size % (sizeof(float) * channelCount_) == 0;

            int samples = info.size / (isFloat ? sizeof(float) : sizeof(int16_t));
            int frames = samples / channelCount_;

            tmp.resize(samples);

            if (isFloat) {
                float* f = (float*)(raw + info.offset);
                for (int i = 0; i < samples; i++)
                    tmp[i] = floatToPcm16(f[i]);
            } else {
                memcpy(tmp.data(), raw + info.offset, info.size);
            }

            writePcmBlocking(tmp.data(), frames);
            AMediaCodec_releaseOutputBuffer(codec_, out, false);
        }
    }
}

/* ───────────────── Audio Callback ───────────────── */

aaudio_data_callback_result_t AudioEngine::audioCallback(
        AAudioStream*,
        void* userData,
        void* audioData,
        int32_t numFrames) {

    auto* self = static_cast<AudioEngine*>(userData);
    auto* out = (int16_t*)audioData;

    int frames = self->readPcm(out, numFrames);

    if (frames < numFrames) {
        memset(out + frames * self->channelCount_,
               0,
               (numFrames - frames) *
               self->channelCount_ * sizeof(int16_t));
    }

    self->clock_->addUs(
            (int64_t)numFrames * 1'000'000LL / self->sampleRate_);

    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}