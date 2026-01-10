#include "AudioEngine.h"
#include <android/log.h>
#include <cstring>
#include <cmath>
#include <unistd.h>

#define LOG_TAG "AudioEngine"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static inline int16_t floatToPcm16(float v) {
    if (v > 1.0f) v = 1.0f;
    if (v < -1.0f) v = -1.0f;
    return (int16_t)(v * 32767.0f);
}

/* ───────────────── Lifecycle ───────────────── */

AudioEngine::AudioEngine(Clock* clock)
        : clock_(clock) {}

AudioEngine::~AudioEngine() {
    stop();
    cleanupAAudio();
}

/* ───────────────── Open ───────────────── */

bool AudioEngine::open(const char* path) {
    extractor_ = AMediaExtractor_new();
    if (!extractor_) return false;

    if (AMediaExtractor_setDataSource(extractor_, path) != AMEDIA_OK)
        return false;

    int audioTrack = -1;
    const size_t trackCount = AMediaExtractor_getTrackCount(extractor_);

    for (size_t i = 0; i < trackCount; i++) {
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

    ringFrames_ = sampleRate_ * 2; // ~2 seconds buffer
    ring_.resize(ringFrames_ * channelCount_);

    if (!setupAAudio())
        return false;

    return AMediaCodec_start(codec_) == AMEDIA_OK;
}

/* ───────────────── AAudio ───────────────── */

bool AudioEngine::setupAAudio() {
    AAudioStreamBuilder* b = nullptr;
    AAudio_createStreamBuilder(&b);

    AAudioStreamBuilder_setDirection(b, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setSampleRate(b, sampleRate_);
    AAudioStreamBuilder_setChannelCount(b, channelCount_);
    AAudioStreamBuilder_setFormat(b, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setSharingMode(b, AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setDataCallback(b, audioCallback, this);

    aaudio_result_t r = AAudioStreamBuilder_openStream(b, &stream_);
    AAudioStreamBuilder_delete(b);

    if (r != AAUDIO_OK) {
        LOGE("AAudio open failed");
        return false;
    }

    AAudioStream_requestStart(stream_);
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

    writePos_ = readPos_ = 0;
    clock_->setUs(us);

    start();
}

/* ───────────────── Decoder Thread ───────────────── */

void AudioEngine::decodeLoop() {
    AMediaCodecBufferInfo info;

    while (running_) {

        ssize_t in = AMediaCodec_dequeueInputBuffer(codec_, 10000);
        if (in >= 0) {
            size_t cap;
            uint8_t* buf = AMediaCodec_getInputBuffer(codec_, in, &cap);
            ssize_t sz = AMediaExtractor_readSampleData(extractor_, buf, cap);

            if (sz < 0) {
                AMediaCodec_queueInputBuffer(
                        codec_, in, 0, 0, 0,
                        AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM
                );
            } else {
                AMediaCodec_queueInputBuffer(
                        codec_, in, 0, sz,
                        AMediaExtractor_getSampleTime(extractor_), 0
                );
                AMediaExtractor_advance(extractor_);
            }
        }

        ssize_t out = AMediaCodec_dequeueOutputBuffer(codec_, &info, 10000);
        if (out >= 0 && info.size > 0) {

            uint8_t* raw = AMediaCodec_getOutputBuffer(codec_, out, nullptr);

            bool isFloat = (info.size % (sizeof(float) * channelCount_)) == 0;
            int samples = info.size / (isFloat ? sizeof(float) : sizeof(int16_t));
            int frames = samples / channelCount_;

            std::vector<int16_t> tmp(samples);

            if (isFloat) {
                float* f = (float*)(raw + info.offset);
                for (int i = 0; i < samples; i++)
                    tmp[i] = floatToPcm16(f[i]);
            } else {
                memcpy(tmp.data(), raw + info.offset, info.size);
            }

            writePcm(tmp.data(), frames);
            AMediaCodec_releaseOutputBuffer(codec_, out, false);
        }
    }
}

/* ───────────────── Ring Buffer ───────────────── */

int AudioEngine::writePcm(const int16_t* in, int frames) {
    int wp = writePos_.load();
    int rp = readPos_.load();
    int freeFrames = ringFrames_ - (wp - rp);

    if (frames > freeFrames)
        frames = freeFrames;

    for (int i = 0; i < frames * channelCount_; i++)
        ring_[(wp * channelCount_ + i) % ring_.size()] = in[i];

    writePos_ = wp + frames;
    return frames;
}

int AudioEngine::readPcm(int16_t* out, int frames) {
    int rp = readPos_.load();
    int wp = writePos_.load();
    int avail = wp - rp;

    if (frames > avail)
        frames = avail;

    for (int i = 0; i < frames * channelCount_; i++)
        out[i] = ring_[(rp * channelCount_ + i) % ring_.size()];

    readPos_ = rp + frames;
    return frames;
}

/* ───────────────── AAudio Callback ───────────────── */

aaudio_data_callback_result_t AudioEngine::audioCallback(
        AAudioStream*,
        void* userData,
        void* audioData,
        int32_t numFrames
) {
    auto* self = (AudioEngine*)userData;
    auto* out = (int16_t*)audioData;

    int got = self->readPcm(out, numFrames);

    if (got < numFrames) {
        memset(out + got * self->channelCount_, 0,
               (numFrames - got) * self->channelCount_ * sizeof(int16_t));
    }

    self->clock_->addUs(
            (int64_t)numFrames * 1000000LL / self->sampleRate_);

    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}