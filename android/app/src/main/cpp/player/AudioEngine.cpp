#include "AudioEngine.h"

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

AudioEngine::AudioEngine(Clock* clock)
        : clock_(clock) {}

AudioEngine::~AudioEngine() {
    stop();
    cleanupCodec();
    cleanupAAudio();
}

/* ───────────────────────────── */
/* Open */
/* ───────────────────────────── */

bool AudioEngine::open(const char* path) {
    extractor_ = AMediaExtractor_new();
    if (!extractor_) return false;

    if (AMediaExtractor_setDataSource(extractor_, path) != AMEDIA_OK)
        return false;

    int audioTrack = -1;
    for (size_t i = 0; i < AMediaExtractor_getTrackCount(extractor_); i++) {
        AMediaFormat* fmt = AMediaExtractor_getTrackFormat(extractor_, i);
        const char* mime = nullptr;
        AMediaFormat_getString(fmt, AMEDIAFORMAT_KEY_MIME, &mime);

        if (mime && !strncmp(mime, "audio/", 6)) {
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

    /* ---------- AAudio ---------- */

    AAudioStreamBuilder* builder = nullptr;
    AAudio_createStreamBuilder(&builder);

    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setSampleRate(builder, sampleRate_);
    AAudioStreamBuilder_setChannelCount(builder, channelCount_);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setDataCallback(builder, audioCallback, this);

    if (AAudioStreamBuilder_openStream(builder, &stream_) != AAUDIO_OK) {
        AAudioStreamBuilder_delete(builder);
        return false;
    }

    AAudioStreamBuilder_delete(builder);

    AMediaCodec_start(codec_);
    return true;
}

/* ───────────────────────────── */
/* Start / Stop */
/* ───────────────────────────── */

void AudioEngine::start() {
    running_ = true;
    clock_->setUs(0);
    AAudioStream_requestStart(stream_);
}

void AudioEngine::stop() {
    running_ = false;
    if (stream_)
        AAudioStream_requestStop(stream_);
}

/* ───────────────────────────── */
/* AAudio CALLBACK */
/* ───────────────────────────── */

aaudio_data_callback_result_t
AudioEngine::audioCallback(
        AAudioStream*,
        void* userData,
        void* audioData,
        int32_t numFrames
) {
    return static_cast<AudioEngine*>(userData)
            ->onAudioCallback(audioData, numFrames);
}

aaudio_data_callback_result_t
AudioEngine::onAudioCallback(void* audioData, int32_t numFrames) {
    if (!running_) {
        memset(audioData, 0, numFrames * channelCount_ * sizeof(int16_t));
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }

    int16_t* out = (int16_t*)audioData;
    int framesWritten = 0;

    while (framesWritten < numFrames) {
        AMediaCodecBufferInfo info;
        ssize_t outIndex =
                AMediaCodec_dequeueOutputBuffer(codec_, &info, 0);

        if (outIndex < 0)
            break;

        uint8_t* raw =
                AMediaCodec_getOutputBuffer(codec_, outIndex, nullptr);

        bool isFloat =
                info.size % (sizeof(float) * channelCount_) == 0;

        int samples = info.size / (isFloat ? 4 : 2);
        int frames = samples / channelCount_;
        int toCopy = std::min(frames, numFrames - framesWritten);

        if (isFloat) {
            float* f = (float*)(raw + info.offset);
            for (int i = 0; i < toCopy * channelCount_; i++)
                out[i] = floatToPcm16(f[i]);
        } else {
            memcpy(out, raw + info.offset,
                   toCopy * channelCount_ * sizeof(int16_t));
        }

        out += toCopy * channelCount_;
        framesWritten += toCopy;

        AMediaCodec_releaseOutputBuffer(codec_, outIndex, false);
    }

    clock_->addUs(
            (int64_t)framesWritten * 1'000'000LL / sampleRate_
    );

    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

/* ───────────────────────────── */
/* Cleanup */
/* ───────────────────────────── */

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
        AAudioStream_close(stream_);
        stream_ = nullptr;
    }
}