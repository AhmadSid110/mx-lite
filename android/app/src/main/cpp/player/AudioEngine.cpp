#include "AudioEngine.h"

#include <aaudio/AAudio.h>
#include <media/NdkMediaExtractor.h>
#include <media/NdkMediaCodec.h>

#include <android/log.h>
#include <thread>
#include <vector>
#include <atomic>
#include <cstring>
#include <algorithm>

#define LOG_TAG "AudioEngine"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

/* =========================================================
   Lifecycle
   ========================================================= */

AudioEngine::AudioEngine(Clock* clock)
        : clock_(clock) {}

AudioEngine::~AudioEngine() {
    stop();
    cleanupAAudio();
    cleanupMedia();
}

/* =========================================================
   Open
   ========================================================= */

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

    if (!setupAAudio())
        return false;

    if (AMediaCodec_start(codec_) != AMEDIA_OK)
        return false;

    /* 500ms ring buffer */
    ringFrames_ = sampleRate_ / 2;
    ring_.resize(ringFrames_ * channelCount_);

    return true;
}

/* =========================================================
   Start / Stop / Seek
   ========================================================= */

void AudioEngine::start() {
    if (running_) return;
    running_ = true;

    clock_->setUs(0);
    decodeThread_ = std::thread(&AudioEngine::decodeLoop, this);

    AAudioStream_requestStart(stream_);
}

void AudioEngine::stop() {
    running_ = false;
    if (decodeThread_.joinable())
        decodeThread_.join();

    if (stream_)
        AAudioStream_requestStop(stream_);
}

void AudioEngine::seekUs(int64_t us) {
    stop();

    AMediaExtractor_seekTo(
            extractor_,
            us,
            AMEDIAEXTRACTOR_SEEK_CLOSEST_SYNC);

    AMediaCodec_flush(codec_);

    writePos_.store(0);
    readPos_.store(0);

    clock_->setUs(us);
    start();
}

/* =========================================================
   AAudio
   ========================================================= */

bool AudioEngine::setupAAudio() {
    AAudioStreamBuilder* builder = nullptr;
    if (AAudio_createStreamBuilder(&builder) != AAUDIO_OK)
        return false;

    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setPerformanceMode(
            builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setSampleRate(builder, sampleRate_);
    AAudioStreamBuilder_setChannelCount(builder, channelCount_);
    AAudioStreamBuilder_setDataCallback(builder, audioCallback, this);

    if (AAudioStreamBuilder_openStream(builder, &stream_) != AAUDIO_OK) {
        AAudioStreamBuilder_delete(builder);
        return false;
    }

    AAudioStreamBuilder_delete(builder);
    return true;
}

void AudioEngine::cleanupAAudio() {
    if (stream_) {
        AAudioStream_close(stream_);
        stream_ = nullptr;
    }
}

/* =========================================================
   Media cleanup
   ========================================================= */

void AudioEngine::cleanupMedia() {
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

/* =========================================================
   Ring buffer
   ========================================================= */

int AudioEngine::readPcm(int16_t* out, int frames) {
    int64_t r = readPos_.load(std::memory_order_acquire);
    int64_t w = writePos_.load(std::memory_order_acquire);
    int64_t avail = w - r;

    if (avail <= 0) return 0;

    int toRead = std::min((int)avail, frames);

    for (int f = 0; f < toRead; f++) {
        size_t idx =
                (size_t)((r + f) % ringFrames_) * channelCount_;
        memcpy(out + f * channelCount_,
               &ring_[idx],
               channelCount_ * sizeof(int16_t));
    }

    readPos_.store(r + toRead, std::memory_order_release);
    return toRead;
}

void AudioEngine::writePcmBlocking(const int16_t* in, int frames) {
    int written = 0;

    while (written < frames && running_) {
        int64_t r = readPos_.load();
        int64_t w = writePos_.load();

        if ((w - r) >= ringFrames_) {
            std::this_thread::sleep_for(std::chrono::milliseconds(1));
            continue;
        }

        size_t idx =
                (size_t)(w % ringFrames_) * channelCount_;

        memcpy(&ring_[idx],
               in + written * channelCount_,
               channelCount_ * sizeof(int16_t));

        writePos_.fetch_add(1);
        written++;
    }
}

/* =========================================================
   Decoder thread (THE IMPORTANT PART)
   ========================================================= */

void AudioEngine::decodeLoop() {
    AMediaCodecBufferInfo info;
    std::vector<int16_t> pcm;

    while (running_) {
        ssize_t in = AMediaCodec_dequeueInputBuffer(codec_, 2000);
        if (in >= 0) {
            size_t cap;
            uint8_t* buf =
                    AMediaCodec_getInputBuffer(codec_, in, &cap);

            ssize_t sz =
                    AMediaExtractor_readSampleData(extractor_, buf, cap);

            if (sz < 0) {
                AMediaCodec_queueInputBuffer(
                        codec_, in, 0, 0, 0,
                        AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
            } else {
                AMediaCodec_queueInputBuffer(
                        codec_, in, 0, sz,
                        AMediaExtractor_getSampleTime(extractor_), 0);
                AMediaExtractor_advance(extractor_);
            }
        }

        ssize_t out =
                AMediaCodec_dequeueOutputBuffer(codec_, &info, 2000);

        if (out >= 0 && info.size > 0) {
            uint8_t* raw =
                    AMediaCodec_getOutputBuffer(codec_, out, nullptr);

            int samples = info.size / sizeof(int16_t);
            int frames  = samples / channelCount_;

            pcm.resize(samples);
            memcpy(pcm.data(), raw + info.offset, info.size);

            writePcmBlocking(pcm.data(), frames);
            AMediaCodec_releaseOutputBuffer(codec_, out, false);
        }
    }
}

/* =========================================================
   AAudio callback (consumer)
   ========================================================= */

aaudio_data_callback_result_t AudioEngine::audioCallback(
        AAudioStream*,
        void* userData,
        void* audioData,
        int32_t numFrames) {

    auto* self = static_cast<AudioEngine*>(userData);
    auto* out  = (int16_t*)audioData;

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