#include "AudioEngine.h"

#include <aaudio/AAudio.h>
#include <android/log.h>
#include <cstring>
#include <vector>
#include <cmath>

#define LOG_TAG "AudioEngine"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/* ───────────────────────────────────────────── */
/* PCM helpers */
/* ───────────────────────────────────────────── */

static inline int16_t floatToPcm16(float v) {
    if (v > 1.0f) v = 1.0f;
    if (v < -1.0f) v = -1.0f;
    return static_cast<int16_t>(v * 32767.0f);
}

/* ───────────────────────────────────────────── */
/* Lifecycle */
/* ───────────────────────────────────────────── */

AudioEngine::AudioEngine(Clock* clock)
        : clock_(clock) {}

AudioEngine::~AudioEngine() {
    stop();
    cleanupCodec();
    cleanupAAudio();
}

/* ───────────────────────────────────────────── */
/* Open media */
/* ───────────────────────────────────────────── */

bool AudioEngine::open(const char* path) {
    extractor_ = AMediaExtractor_new();
    if (!extractor_) return false;

    if (AMediaExtractor_setDataSource(extractor_, path) != AMEDIA_OK)
        return false;

    int audioTrack = -1;
    size_t trackCount = AMediaExtractor_getTrackCount(extractor_);

    for (size_t i = 0; i < trackCount; i++) {
        AMediaFormat* fmt = AMediaExtractor_getTrackFormat(extractor_, i);
        const char* mime = nullptr;
        AMediaFormat_getString(fmt, AMEDIAFORMAT_KEY_MIME, &mime);

        if (mime && strncmp(mime, "audio/", 6) == 0) {
            format_ = fmt;
            audioTrack = static_cast<int>(i);
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

    if (!setupAAudio())
        return false;

    return AMediaCodec_start(codec_) == AMEDIA_OK;
}

/* ───────────────────────────────────────────── */
/* AAudio setup */
/* ───────────────────────────────────────────── */

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

    aaudio_result_t res =
            AAudioStreamBuilder_openStream(builder, &stream_);
    AAudioStreamBuilder_delete(builder);

    if (res != AAUDIO_OK) {
        LOGE("AAudio open failed: %s",
             AAudio_convertResultToText(res));
        return false;
    }

    AAudioStream_requestStart(stream_);
    return true;
}

/* ───────────────────────────────────────────── */
/* Start / Stop */
/* ───────────────────────────────────────────── */

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

/* ───────────────────────────────────────────── */
/* Seek */
/* ───────────────────────────────────────────── */

void AudioEngine::seekUs(int64_t us) {
    if (!extractor_ || !codec_) return;

    running_ = false;
    if (decodeThread_.joinable())
        decodeThread_.join();

    AMediaExtractor_seekTo(
            extractor_,
            us,
            AMEDIAEXTRACTOR_SEEK_CLOSEST_SYNC
    );

    AMediaCodec_flush(codec_);
    clock_->setUs(us);

    running_ = true;
    decodeThread_ = std::thread(&AudioEngine::decodeLoop, this);
}

/* ───────────────────────────────────────────── */
/* Decode loop (MASTER CLOCK SOURCE) */
/* ───────────────────────────────────────────── */

void AudioEngine::decodeLoop() {
    AMediaCodecBufferInfo info;
    std::vector<int16_t> pcm16;

    while (running_) {

        /* ---------- INPUT ---------- */
        ssize_t inIndex =
                AMediaCodec_dequeueInputBuffer(codec_, 10'000);

        if (inIndex >= 0) {
            size_t cap;
            uint8_t* buf =
                    AMediaCodec_getInputBuffer(codec_, inIndex, &cap);
            ssize_t size =
                    AMediaExtractor_readSampleData(extractor_, buf, cap);

            if (size < 0) {
                AMediaCodec_queueInputBuffer(
                        codec_, inIndex, 0, 0, 0,
                        AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM
                );
            } else {
                int64_t pts =
                        AMediaExtractor_getSampleTime(extractor_);
                AMediaCodec_queueInputBuffer(
                        codec_, inIndex, 0, size, pts, 0);
                AMediaExtractor_advance(extractor_);
            }
        }

        /* ---------- OUTPUT ---------- */
        ssize_t outIndex =
                AMediaCodec_dequeueOutputBuffer(codec_, &info, 10'000);

        if (outIndex >= 0 && info.size > 0) {

            uint8_t* raw =
                    AMediaCodec_getOutputBuffer(codec_, outIndex, nullptr);

            /*
             * SAFE PCM FORMAT DETECTION
             * - PCM_FLOAT: size divisible by 4 * channels
             * - PCM_16:    size divisible by 2 * channels
             */
            bool isFloat =
                    (info.size % (sizeof(float) * channelCount_)) == 0;

            int frames = 0;

            if (isFloat) {
                float* f =
                        reinterpret_cast<float*>(raw + info.offset);
                int samples = info.size / sizeof(float);
                pcm16.resize(samples);

                for (int i = 0; i < samples; i++)
                    pcm16[i] = floatToPcm16(f[i]);

                frames = samples / channelCount_;

                AAudioStream_write(
                        stream_,
                        pcm16.data(),
                        frames,
                        -1
                );
            } else {
                int16_t* pcm =
                        reinterpret_cast<int16_t*>(raw + info.offset);

                frames = info.size / (2 * channelCount_);

                AAudioStream_write(
                        stream_,
                        pcm,
                        frames,
                        -1
                );
            }

            /* MASTER CLOCK — AFTER AUDIO IS WRITTEN */
            int64_t deltaUs =
                    (int64_t)frames * 1'000'000LL / sampleRate_;
            clock_->addUs(deltaUs);

            AMediaCodec_releaseOutputBuffer(codec_, outIndex, false);
        }
    }
}

/* ───────────────────────────────────────────── */
/* Cleanup */
/* ───────────────────────────────────────────── */

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