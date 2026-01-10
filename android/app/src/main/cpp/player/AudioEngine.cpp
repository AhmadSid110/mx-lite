#include "AudioEngine.h"

#include <aaudio/AAudio.h>
#include <android/log.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaExtractor.h>
#include <cstring>
#include <vector>

#define LOG_TAG "AudioEngine"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/* ============================================================
 * PCM helpers
 * ============================================================ */

static inline int16_t floatToPcm16(float v) {
    if (v > 1.0f) v = 1.0f;
    if (v < -1.0f) v = -1.0f;
    return (int16_t)(v * 32767.0f);
}

/* ============================================================
 * Lifecycle
 * ============================================================ */

AudioEngine::AudioEngine(Clock* clock)
        : clock_(clock) {}

AudioEngine::~AudioEngine() {
    stop();
    cleanupCodec();
    cleanupAAudio();
}

/* ============================================================
 * Open
 * ============================================================ */

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

    if (AMediaCodec_start(codec_) != AMEDIA_OK)
        return false;

    return setupAAudio();
}

/* ============================================================
 * AAudio setup (CALLBACK MODE)
 * ============================================================ */

bool AudioEngine::setupAAudio() {
    AAudioStreamBuilder* builder = nullptr;
    AAudio_createStreamBuilder(&builder);

    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setPerformanceMode(
            builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setSharingMode(
            builder, AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setSampleRate(builder, sampleRate_);
    AAudioStreamBuilder_setChannelCount(builder, channelCount_);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);

    AAudioStreamBuilder_setDataCallback(
            builder,
            AudioEngine::audioCallback,
            this
    );

    aaudio_result_t res =
            AAudioStreamBuilder_openStream(builder, &stream_);

    AAudioStreamBuilder_delete(builder);

    if (res != AAUDIO_OK) {
        LOGE("AAudio open failed: %s",
             AAudio_convertResultToText(res));
        return false;
    }

    AAudioStream_requestStart(stream_);
    running_ = true;

    LOGD("AAudio started (callback mode)");
    return true;
}

/* ============================================================
 * Start / Stop
 * ============================================================ */

void AudioEngine::start() {
    clock_->setUs(0);
}

void AudioEngine::stop() {
    running_ = false;
}

/* ============================================================
 * Seek
 * ============================================================ */

void AudioEngine::seekUs(int64_t us) {
    if (!extractor_ || !codec_) return;

    AMediaExtractor_seekTo(
            extractor_,
            us,
            AMEDIAEXTRACTOR_SEEK_CLOSEST_SYNC
    );

    AMediaCodec_flush(codec_);
    clock_->setUs(us);
}

/* ============================================================
 * AAudio CALLBACK (MASTER CLOCK)
 * ============================================================ */

aaudio_data_callback_result_t AudioEngine::audioCallback(
        AAudioStream* /*stream*/,
        void* userData,
        void* audioData,
        int32_t numFrames
) {
    auto* self = static_cast<AudioEngine*>(userData);
    if (!self->running_)
        return AAUDIO_CALLBACK_RESULT_CONTINUE;

    int16_t* out = (int16_t*)audioData;
    int writtenFrames = 0;

    AMediaCodecBufferInfo info;

    /* ---------- FEED DECODER INPUT ---------- */
    for (int i = 0; i < 4; i++) {
        ssize_t in = AMediaCodec_dequeueInputBuffer(self->codec_, 0);
        if (in < 0) break;

        size_t cap;
        uint8_t* buf =
                AMediaCodec_getInputBuffer(self->codec_, in, &cap);

        ssize_t sz =
                AMediaExtractor_readSampleData(self->extractor_, buf, cap);

        if (sz < 0) {
            AMediaCodec_queueInputBuffer(
                    self->codec_, in, 0, 0, 0,
                    AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM
            );
            break;
        } else {
            AMediaCodec_queueInputBuffer(
                    self->codec_,
                    in,
                    0,
                    sz,
                    AMediaExtractor_getSampleTime(self->extractor_),
                    0
            );
            AMediaExtractor_advance(self->extractor_);
        }
    }

    /* ---------- DRAIN DECODER OUTPUT ---------- */
    while (writtenFrames < numFrames) {
        ssize_t outIndex =
                AMediaCodec_dequeueOutputBuffer(self->codec_, &info, 0);

        if (outIndex < 0 || info.size <= 0)
            break;

        uint8_t* raw =
                AMediaCodec_getOutputBuffer(self->codec_, outIndex, nullptr);

        bool isFloat =
                (info.size % (sizeof(float) * self->channelCount_)) == 0;

        int samples =
                info.size / (isFloat ? sizeof(float) : sizeof(int16_t));
        int frames =
                samples / self->channelCount_;

        int copyFrames =
                std::min(frames, numFrames - writtenFrames);

        if (isFloat) {
            float* f = (float*)(raw + info.offset);
            for (int i = 0; i < copyFrames * self->channelCount_; i++)
                out[writtenFrames * self->channelCount_ + i] =
                        floatToPcm16(f[i]);
        } else {
            memcpy(
                    out + writtenFrames * self->channelCount_,
                    raw + info.offset,
                    copyFrames * self->channelCount_ * sizeof(int16_t)
            );
        }

        writtenFrames += copyFrames;
        AMediaCodec_releaseOutputBuffer(self->codec_, outIndex, false);
    }

    /* ---------- SILENCE PADDING (CRITICAL) ---------- */
    if (writtenFrames < numFrames) {
        memset(
                out + writtenFrames * self->channelCount_,
                0,
                (numFrames - writtenFrames) *
                self->channelCount_ * sizeof(int16_t)
        );
    }

    /* ---------- MASTER CLOCK ADVANCE ---------- */
    self->clock_->addUs(
            (int64_t)numFrames * 1'000'000LL / self->sampleRate_
    );

    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

/* ============================================================
 * Cleanup
 * ============================================================ */

void AudioEngine::cleanupCodec() {
    if (codec_) {
        AMediaCodec_stop(codec_);
        AMediaCodec_delete(codec_);
        codec_ = nullptr;
    }
    if (format_) {
        AMediaFormat_delete(format_);
        format_ = nullptr;
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