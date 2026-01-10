#include "AudioEngine.h"
#include "AudioDebug.h"

#include <aaudio/AAudio.h>
#include <media/NdkMediaExtractor.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>

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

bool AudioEngine::open(const char* path) {
    // --- Extractor ---
    extractor_ = AMediaExtractor_new();
    if (!extractor_) return false;

    if (AMediaExtractor_setDataSource(extractor_, path) != AMEDIA_OK) {
        LOGE("Extractor setDataSource failed");
        return false;
    }

    int audioTrack = -1;

    for (size_t i = 0; i < AMediaExtractor_getTrackCount(extractor_); i++) {
        AMediaFormat* fmt = AMediaExtractor_getTrackFormat(extractor_, i);
        const char* mime = nullptr;

        if (AMediaFormat_getString(fmt, AMEDIAFORMAT_KEY_MIME, &mime) &&
            mime && !strncmp(mime, "audio/", 6)) {
            format_ = fmt;
            audioTrack = (int)i;
            break;
        }
        AMediaFormat_delete(fmt);
    }

    if (audioTrack < 0) {
        LOGE("No audio track found");
        return false;
    }

    AMediaExtractor_selectTrack(extractor_, audioTrack);

    AMediaFormat_getInt32(format_, AMEDIAFORMAT_KEY_SAMPLE_RATE, &sampleRate_);
    AMediaFormat_getInt32(format_, AMEDIAFORMAT_KEY_CHANNEL_COUNT, &channelCount_);

    const char* mime = nullptr;
    AMediaFormat_getString(format_, AMEDIAFORMAT_KEY_MIME, &mime);

    // --- Codec ---
    codec_ = AMediaCodec_createDecoderByType(mime);
    if (!codec_) return false;

    if (AMediaCodec_configure(codec_, format_, nullptr, nullptr, 0) != AMEDIA_OK)
        return false;

    if (AMediaCodec_start(codec_) != AMEDIA_OK)
        return false;

    // --- AAudio ---
    if (!setupAAudio())
        return false;

    // --- Ring buffer (1 second) ---
    ringFrames_ = sampleRate_;
    ring_.resize(ringFrames_ * channelCount_);

    // --- Start decode thread ---
    running_.store(true);
    decodeThread_ = std::thread(&AudioEngine::decodeLoop, this);

    return true;
}

/* ===================== Start / Stop ===================== */

void AudioEngine::start() {
    if (!stream_) return;

    AAudioStream_requestStart(stream_);

    if (AAudioStream_getState(stream_) == AAUDIO_STREAM_STATE_STARTED) {
        gAudioDebug.aaudioStarted.store(true);
    }
}

void AudioEngine::stop() {
    running_.store(false);

    if (decodeThread_.joinable())
        decodeThread_.join();

    if (stream_)
        AAudioStream_requestStop(stream_);
}

/* ===================== Seek ===================== */

void AudioEngine::seekUs(int64_t us) {
    stop();

    readPos_.store(0);
    writePos_.store(0);
    std::fill(ring_.begin(), ring_.end(), 0);

    if (clock_)
        clock_->setUs(us);

    if (extractor_)
        AMediaExtractor_seekTo(extractor_, us, AMEDIAEXTRACTOR_SEEK_CLOSEST_SYNC);

    if (codec_)
        AMediaCodec_flush(codec_);

    running_.store(true);
    decodeThread_ = std::thread(&AudioEngine::decodeLoop, this);
}

/* ===================== AAudio ===================== */

bool AudioEngine::setupAAudio() {
    AAudioStreamBuilder* builder = nullptr;

    if (AAudio_createStreamBuilder(&builder) != AAUDIO_OK)
        return false;

    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setPerformanceMode(
            builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);

    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setChannelCount(builder, channelCount_);
    AAudioStreamBuilder_setSampleRate(builder, sampleRate_);
    AAudioStreamBuilder_setDataCallback(builder, audioCallback, this);

    if (AAudioStreamBuilder_openStream(builder, &stream_) != AAUDIO_OK) {
        AAudioStreamBuilder_delete(builder);
        return false;
    }

    AAudioStreamBuilder_delete(builder);
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
    int64_t r = readPos_.load();
    int64_t w = writePos_.load();
    int64_t avail = w - r;

    if (avail <= 0) return 0;

    int toRead = (int)std::min<int64_t>(frames, avail);

    for (int f = 0; f < toRead; ++f) {
        size_t base = ((r + f) % ringFrames_) * channelCount_;
        memcpy(out + f * channelCount_,
               &ring_[base],
               channelCount_ * sizeof(int16_t));
    }

    readPos_.store(r + toRead);
    gAudioDebug.bufferFill.store(w - (r + toRead));
    return toRead;
}

void AudioEngine::writePcmBlocking(const int16_t* in, int frames) {
    for (int f = 0; f < frames && running_.load(); ++f) {
        while ((writePos_.load() - readPos_.load()) >= ringFrames_)
            std::this_thread::sleep_for(std::chrono::milliseconds(1));

        int64_t w = writePos_.load();
        size_t base = (w % ringFrames_) * channelCount_;

        memcpy(&ring_[base],
               in + f * channelCount_,
               channelCount_ * sizeof(int16_t));

        writePos_.fetch_add(1);
    }

    gAudioDebug.bufferFill.store(writePos_.load() - readPos_.load());
}

/* ===================== Decoder Thread ===================== */

void AudioEngine::decodeLoop() {
    AMediaCodecBufferInfo info;

    while (running_.load()) {

        ssize_t inIdx = AMediaCodec_dequeueInputBuffer(codec_, 2000);
        if (inIdx >= 0) {
            size_t cap = 0;
            uint8_t* buf = AMediaCodec_getInputBuffer(codec_, inIdx, &cap);

            ssize_t size = AMediaExtractor_readSampleData(extractor_, buf, cap);

            if (size < 0) {
                AMediaCodec_queueInputBuffer(
                        codec_, inIdx, 0, 0, 0,
                        AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
            } else {
                AMediaCodec_queueInputBuffer(
                        codec_, inIdx, 0, size,
                        AMediaExtractor_getSampleTime(extractor_), 0);
                AMediaExtractor_advance(extractor_);
            }
        }

        ssize_t outIdx = AMediaCodec_dequeueOutputBuffer(codec_, &info, 2000);
        if (outIdx >= 0 && info.size > 0) {

            uint8_t* pcm =
                    AMediaCodec_getOutputBuffer(codec_, outIdx, nullptr);

            int frames =
                    info.size / (sizeof(int16_t) * channelCount_);

            gAudioDebug.decoderProduced.store(true);
            gAudioDebug.decodedFrames.fetch_add(frames);

            writePcmBlocking(
                    reinterpret_cast<int16_t*>(pcm + info.offset),
                    frames);

            AMediaCodec_releaseOutputBuffer(codec_, outIdx, false);
        }
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
    gAudioDebug.callbackCount.fetch_add(1);

    auto* out = static_cast<int16_t*>(audioData);
    int read = engine->readPcm(out, numFrames);

    if (read < numFrames) {
        memset(out + read * engine->channelCount_,
               0,
               (numFrames - read) *
               engine->channelCount_ * sizeof(int16_t));
    }

    if (engine->clock_ && read > 0) {
        engine->clock_->addUs(
                (int64_t)read * 1000000LL / engine->sampleRate_);
    }

    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}