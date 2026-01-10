#include "AudioEngine.h"

#include <aaudio/AAudio.h>
#include <android/log.h>
#include <vector>
#include <cmath>

#define LOG_TAG "AudioEngine"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static inline int16_t floatToPcm16(float v) {
    if (v > 1.f) v = 1.f;
    if (v < -1.f) v = -1.f;
    return (int16_t)(v * 32767.f);
}

/* ====================================================== */
/* AAudio callback */
/* ====================================================== */

static aaudio_data_callback_result_t audioCallback(
        AAudioStream*,
        void* user,
        void* audioData,
        int32_t frames) {

    auto* self = static_cast<AudioEngine*>(user);
    if (!self || !self->codec_) return AAUDIO_CALLBACK_RESULT_STOP;

    int16_t* out = static_cast<int16_t*>(audioData);
    int neededSamples = frames * self->channelCount_;
    int written = 0;

    AMediaCodecBufferInfo info;

    while (written < neededSamples) {

        ssize_t outIndex =
                AMediaCodec_dequeueOutputBuffer(self->codec_, &info, 0);

        if (outIndex < 0) break;

        uint8_t* raw =
                AMediaCodec_getOutputBuffer(self->codec_, outIndex, nullptr);

        bool isFloat =
                (info.size % (sizeof(float) * self->channelCount_)) == 0;

        if (isFloat) {
            float* f = reinterpret_cast<float*>(raw + info.offset);
            int samples = info.size / sizeof(float);

            for (int i = 0; i < samples && written < neededSamples; i++) {
                out[written++] = floatToPcm16(f[i]);
            }
        } else {
            int16_t* pcm = reinterpret_cast<int16_t*>(raw + info.offset);
            int samples = info.size / sizeof(int16_t);

            for (int i = 0; i < samples && written < neededSamples; i++) {
                out[written++] = pcm[i];
            }
        }

        AMediaCodec_releaseOutputBuffer(self->codec_, outIndex, false);
    }

    /* 🔑 MASTER CLOCK — HARDWARE PULL */
    int64_t deltaUs =
            (int64_t)frames * 1'000'000LL / self->sampleRate_;
    self->clock_->addUs(deltaUs);

    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

/* ====================================================== */
/* Lifecycle */
/* ====================================================== */

AudioEngine::AudioEngine(Clock* clock)
        : clock_(clock) {}

AudioEngine::~AudioEngine() {
    cleanupCodec();
    cleanupAAudio();
}

/* ====================================================== */
/* Open */
/* ====================================================== */

bool AudioEngine::open(const char* path) {

    extractor_ = AMediaExtractor_new();
    if (!extractor_) return false;

    if (AMediaExtractor_setDataSource(extractor_, path) != AMEDIA_OK)
        return false;

    int track = -1;
    size_t count = AMediaExtractor_getTrackCount(extractor_);

    for (size_t i = 0; i < count; i++) {
        AMediaFormat* fmt = AMediaExtractor_getTrackFormat(extractor_, i);
        const char* mime = nullptr;
        AMediaFormat_getString(fmt, AMEDIAFORMAT_KEY_MIME, &mime);

        if (mime && !strncmp(mime, "audio/", 6)) {
            format_ = fmt;
            track = (int)i;
            break;
        }
        AMediaFormat_delete(fmt);
    }

    if (track < 0) return false;

    AMediaExtractor_selectTrack(extractor_, track);
    AMediaFormat_getInt32(format_, AMEDIAFORMAT_KEY_SAMPLE_RATE, &sampleRate_);
    AMediaFormat_getInt32(format_, AMEDIAFORMAT_KEY_CHANNEL_COUNT, &channelCount_);

    const char* mime = nullptr;
    AMediaFormat_getString(format_, AMEDIAFORMAT_KEY_MIME, &mime);

    codec_ = AMediaCodec_createDecoderByType(mime);
    if (!codec_) return false;

    AMediaCodec_configure(codec_, format_, nullptr, nullptr, 0);
    AMediaCodec_start(codec_);

    return setupAAudio();
}

/* ====================================================== */
/* AAudio */
/* ====================================================== */

bool AudioEngine::setupAAudio() {

    AAudioStreamBuilder* b = nullptr;
    AAudio_createStreamBuilder(&b);

    AAudioStreamBuilder_setDirection(b, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setFormat(b, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setSampleRate(b, sampleRate_);
    AAudioStreamBuilder_setChannelCount(b, channelCount_);
    AAudioStreamBuilder_setPerformanceMode(b,
        AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setDataCallback(b, audioCallback, this);

    if (AAudioStreamBuilder_openStream(b, &stream_) != AAUDIO_OK)
        return false;

    AAudioStreamBuilder_delete(b);
    AAudioStream_requestStart(stream_);

    LOGD("AAudio CALLBACK started");
    return true;
}

/* ====================================================== */
/* Cleanup */
/* ====================================================== */

void AudioEngine::cleanupCodec() {
    if (codec_) {
        AMediaCodec_stop(codec_);
        AMediaCodec_delete(codec_);
        codec_ = nullptr;
    }
    if (extractor_) {
        AMediaExtractor_delete(extractor_);
        extractor_ = nullptr;
    }
}

void AudioEngine::cleanupAAudio() {
    if (stream_) {
        AAudioStream_requestStop(stream_);
        AAudioStream_close(stream_);
        stream_ = nullptr;
    }
}