#include "AudioEngine.h"
#include "AudioDebug.h"

#include <media/NdkMediaExtractor.h>
#include <media/NdkMediaCodec.h>
#include <android/log.h>

#include <cstring>
#include <thread>
#include <chrono>
#include <algorithm>

#define LOG_TAG "AudioEngine"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern AudioDebug gAudioDebug;

/* ───────────────── PCM helper ───────────────── */

static inline int16_t floatToPcm16(float v) {
    if (v > 1.f) v = 1.f;
    if (v < -1.f) v = -1.f;
    return static_cast<int16_t>(v * 32767.f);
}

/* ───────────────── Lifecycle ───────────────── */

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
    size_t trackCount = AMediaExtractor_getTrackCount(extractor_);

    for (size_t i = 0; i < trackCount; ++i) {
        AMediaFormat* fmt = AMediaExtractor_getTrackFormat(extractor_, i);
        const char* mime = nullptr;
        AMediaFormat_getString(fmt, AMEDIAFORMAT_KEY_MIME, &mime);

        if (mime && !strncmp(mime, "audio/", 6)) {
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

    if (AMediaCodec_start(codec_) != AMEDIA_OK)
        return false;

    /* 1 second ring buffer */
    ringFrames_ = sampleRate_;
    ring_.resize(ringFrames_ * channelCount_);

    if (!setupAAudio())
        return false;

    return true;
}

/* ───────────────── AAudio ───────────────── */

bool AudioEngine::setupAAudio() {
    AAudioStreamBuilder* builder = nullptr;
    if (AAudio_createStreamBuilder(&builder) != AAUDIO_OK)
        return false;

    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setChannelCount(builder, channelCount_);
    AAudioStreamBuilder_setSampleRate(builder, sampleRate_);
    AAudioStreamBuilder_setDataCallback(builder, audioCallback, this);

    if (AAudioStreamBuilder_openStream(builder, &stream_) != AAUDIO_OK) {
        AAudioStreamBuilder_delete(builder);
        return false;
    }

    AAudioStreamBuilder_delete(builder);

    AAudioStream_requestStart(stream_);
    gAudioDebug.aaudioOpened.store(true);
    gAudioDebug.aaudioStarted.store(true);

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
    running_.store(true);
    decodeThread_ = std::thread(&AudioEngine::decodeLoop, this);
}

void AudioEngine::stop() {
    running_.store(false);
    if (decodeThread_.joinable())
        decodeThread_.join();
}

/* ───────────────── Ring Buffer ───────────────── */

int AudioEngine::readPcm(int16_t* out, int frames) {
    int64_t r = readPos_.load();
    int64_t w = writePos_.load();
    int64_t avail = w - r;
    if (avail <= 0) return 0;

    int chunk = std::min((int64_t)frames, avail);

    for (int i = 0; i < chunk * channelCount_; ++i) {
        out[i] = ring_[(r * channelCount_ + i) % ring_.size()];
    }

    readPos_.store(r + chunk);
    gAudioDebug.bufferFill.store(w - readPos_.load());
    return chunk;
}

void AudioEngine::writePcmBlocking(const int16_t* in, int frames) {
    int written = 0;

    while (written < frames && running_) {
        int64_t w = writePos_.load();
        int64_t r = readPos_.load();

        if ((w - r) >= ringFrames_) {
            std::this_thread::sleep_for(std::chrono::milliseconds(2));
            continue;
        }

        int canWrite = std::min<int64_t>(ringFrames_ - (w - r), frames - written);

        for (int i = 0; i < canWrite * channelCount_; ++i) {
            ring_[(w * channelCount_ + i) % ring_.size()] =
                    in[written * channelCount_ + i];
        }

        writePos_.store(w + canWrite);
        written += canWrite;
    }

    gAudioDebug.bufferFill.store(writePos_.load() - readPos_.load());
}

/* ───────────────── Decoder Thread ───────────────── */

void AudioEngine::decodeLoop() {
    AMediaCodecBufferInfo info;
    std::vector<int16_t> pcm;

    while (running_) {

        /* Feed input */
        ssize_t in = AMediaCodec_dequeueInputBuffer(codec_, 2000);
        if (in >= 0) {
            size_t cap;
            uint8_t* buf = AMediaCodec_getInputBuffer(codec_, in, &cap);
            ssize_t sz = AMediaExtractor_readSampleData(extractor_, buf, cap);

            if (sz < 0) {
                AMediaCodec_queueInputBuffer(
                        codec_, in, 0, 0, 0,
                        AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
                break;
            }

            AMediaCodec_queueInputBuffer(
                    codec_, in, 0, sz,
                    AMediaExtractor_getSampleTime(extractor_), 0);
            AMediaExtractor_advance(extractor_);
        }

        /* Drain output */
        ssize_t out = AMediaCodec_dequeueOutputBuffer(codec_, &info, 2000);
        if (out >= 0 && info.size > 0) {
            uint8_t* raw = AMediaCodec_getOutputBuffer(codec_, out, nullptr);

            bool isFloat =
                    (info.size % (sizeof(float) * channelCount_) == 0);

            int samples = info.size / (isFloat ? sizeof(float) : sizeof(int16_t));
            int frames = samples / channelCount_;

            pcm.resize(samples);

            if (isFloat) {
                float* f = (float*)(raw + info.offset);
                for (int i = 0; i < samples; ++i)
                    pcm[i] = floatToPcm16(f[i]);
            } else {
                memcpy(pcm.data(), raw + info.offset, info.size);
            }

            writePcmBlocking(pcm.data(), frames);
            gAudioDebug.decoderProduced.store(true);

            AMediaCodec_releaseOutputBuffer(codec_, out, false);
        }
    }
}

/* ───────────────── AAudio Callback ───────────────── */

aaudio_data_callback_result_t AudioEngine::audioCallback(
        AAudioStream*, void* userData, void* audioData, int32_t frames) {

    auto* engine = static_cast<AudioEngine*>(userData);
    auto* out = (int16_t*)audioData;

    gAudioDebug.callbackCalled.store(true);

    int read = engine->readPcm(out, frames);

    if (read < frames) {
        memset(out + read * engine->channelCount_,
               0,
               (frames - read) * engine->channelCount_ * sizeof(int16_t));
    }

    if (read > 0 && engine->clock_) {
        engine->clock_->addUs(
                (int64_t)read * 1'000'000LL / engine->sampleRate_);
    }

    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}