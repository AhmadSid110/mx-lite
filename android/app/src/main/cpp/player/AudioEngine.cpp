#include "AudioEngine.h"
#include "AudioDebug.h"

#include <aaudio/AAudio.h>
#include <media/NdkMediaExtractor.h>
#include <media/NdkMediaCodec.h>

#include <android/log.h>
#include <algorithm>
#include <thread>
#include <chrono>
#include <cstring>
#include <unistd.h>

#define LOG_TAG "AudioEngine"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern AudioDebug gAudioDebug;

#if __ANDROID_API__ >= 28
static void aaudioStateCallback(
        AAudioStream*,
        void*,
        aaudio_stream_state_t state,
        aaudio_stream_state_t /*prev*/) {

    if (state == AAUDIO_STREAM_STATE_STARTED) {
        gAudioDebug.aaudioStarted.store(true);
    }
}
#endif

/* ===================== PCM helper ===================== */

static inline int16_t floatToPcm16(float v) {
    if (v > 1.0f) v = 1.0f;
    if (v < -1.0f) v = -1.0f;
    return static_cast<int16_t>(v * 32767.0f);
}

/* ===================== Lifecycle ===================== */

AudioEngine::AudioEngine(Clock* clock)
        : clock_(clock) {
    gAudioDebug.engineCreated.store(true);
}

AudioEngine::~AudioEngine() {
    stop();
    cleanupAAudio();
    cleanupMedia();
}

/* ===================== Open ===================== */

bool AudioEngine::open(const char* path) {
    gAudioDebug.openStage.store(1);

    extractor_ = AMediaExtractor_new();
    if (!extractor_) return false;

    if (AMediaExtractor_setDataSource(extractor_, path) != AMEDIA_OK) {
        return false;
    }
    gAudioDebug.openStage.store(2);

    int audioTrack = -1;
    size_t trackCount = AMediaExtractor_getTrackCount(extractor_);

    for (size_t i = 0; i < trackCount; ++i) {
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
        return false;
    }
    gAudioDebug.openStage.store(3);

    AMediaExtractor_selectTrack(extractor_, audioTrack);

    const char* mime = nullptr;
    AMediaFormat_getString(format_, AMEDIAFORMAT_KEY_MIME, &mime);

    codec_ = AMediaCodec_createDecoderByType(mime);
    if (!codec_) return false;
    gAudioDebug.openStage.store(4);

    if (AMediaCodec_configure(codec_, format_, nullptr, nullptr, 0) != AMEDIA_OK)
        return false;
    gAudioDebug.openStage.store(5);

    if (AMediaCodec_start(codec_) != AMEDIA_OK)
        return false;
    gAudioDebug.openStage.store(6);

    gAudioDebug.openStage.store(7);

    if (!setupAAudio())
        return false;

    return true;
}

bool AudioEngine::openFd(int fd, int64_t offset, int64_t length) {

    // 🔐 CRITICAL: duplicate fd so GC / Java cannot close it
    int dupFd = dup(fd);
    if (dupFd < 0) {
        return false;
    }

    gAudioDebug.openStage.store(1);

    extractor_ = AMediaExtractor_new();
    if (!extractor_) {
        close(dupFd);
        return false;
    }

    if (AMediaExtractor_setDataSourceFd(
            extractor_, dupFd, offset, length) != AMEDIA_OK) {
        close(dupFd);
        return false;
    }

    gAudioDebug.openStage.store(2);

    // ─── Find audio track ───
    int audioTrack = -1;
    size_t trackCount = AMediaExtractor_getTrackCount(extractor_);

    for (size_t i = 0; i < trackCount; ++i) {
        AMediaFormat* fmt = AMediaExtractor_getTrackFormat(extractor_, i);
        const char* mime = nullptr;

        if (AMediaFormat_getString(fmt, AMEDIAFORMAT_KEY_MIME, &mime)) {
            if (mime && !strncmp(mime, "audio/", 6)) {
                format_ = fmt;
                audioTrack = (int)i;
                break;
            }
        }
        AMediaFormat_delete(fmt);
    }

    if (audioTrack < 0) {
        return false;
    }

    gAudioDebug.openStage.store(3);

    AMediaExtractor_selectTrack(extractor_, audioTrack);

    const char* mime = nullptr;
    AMediaFormat_getString(format_, AMEDIAFORMAT_KEY_MIME, &mime);

    codec_ = AMediaCodec_createDecoderByType(mime);
    if (!codec_) return false;

    gAudioDebug.openStage.store(4);

    if (AMediaCodec_configure(codec_, format_, nullptr, nullptr, 0) != AMEDIA_OK)
        return false;

    gAudioDebug.openStage.store(5);

    if (AMediaCodec_start(codec_) != AMEDIA_OK)
        return false;

    gAudioDebug.openStage.store(6);

    if (!setupAAudio())
        return false;

    gAudioDebug.openStage.store(7);
    return true;
}

/* ===================== Start / Stop ===================== */

void AudioEngine::start() {
    if (!stream_) return;

    aaudio_result_t res = AAudioStream_requestStart(stream_);
    if (res != AAUDIO_OK) {
        gAudioDebug.aaudioError.store(res);
        return;
    }

    isPlaying_.store(true);

    if (decodeThread_.joinable()) {
        decodeThread_.join();
    }

    // reset heads before the decode thread starts to avoid delivering stale data
    flushRingBuffer();

    decodeThread_ = std::thread(&AudioEngine::decodeLoop, this);
}

void AudioEngine::stop() {
    isPlaying_.store(false);

    if (decodeThread_.joinable())
        decodeThread_.join();

    if (stream_)
        AAudioStream_requestStop(stream_);
}

void AudioEngine::seekUs(int64_t us) {
    // One-time sync on seek: reset decoder/state but do NOT update any clock.

    // Stop audio delivery briefly so we can flush buffers safely.
    if (stream_)
        AAudioStream_requestStop(stream_);

    // Clear ring buffer to avoid delivering stale audio after seek
    flushRingBuffer();
    memset(ringBuffer_, 0, sizeof(ringBuffer_));

    // Move extractor to requested position
    if (extractor_)
        AMediaExtractor_seekTo(extractor_, us, AMEDIAEXTRACTOR_SEEK_CLOSEST_SYNC);

    // Flush decoder state
    if (codec_)
        AMediaCodec_flush(codec_);

    // Restart audio delivery. Do NOT set or modify any software clock here.
    if (stream_)
        AAudioStream_requestStart(stream_);
}

/* ===================== AAudio ===================== */

bool AudioEngine::setupAAudio() {

    gAudioDebug.aaudioError.store(-999); // probe

    AAudioStreamBuilder* builder = nullptr;
    aaudio_result_t result = AAudio_createStreamBuilder(&builder);

    if (result != AAUDIO_OK) {
        gAudioDebug.aaudioError.store(result);
        return false;
    }

        AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
        #if __ANDROID_API__ >= 28
            AAudioStreamBuilder_setUsage(
                builder, AAUDIO_USAGE_MEDIA);

            AAudioStreamBuilder_setContentType(
                builder, AAUDIO_CONTENT_TYPE_MOVIE);
        #endif

        AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
        AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_NONE);
        AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
        AAudioStreamBuilder_setChannelCount(builder, 2);
        AAudioStreamBuilder_setSampleRate(builder, sampleRate_);
    AAudioStreamBuilder_setDataCallback(builder, audioCallback, this);
#if __ANDROID_API__ >= 28
    AAudioStreamBuilder_setStateCallback(builder, aaudioStateCallback, nullptr);
#endif

    // Force callback scheduling (critical on many OEM devices)
    AAudioStreamBuilder_setFramesPerDataCallback(builder, 192);

    result = AAudioStreamBuilder_openStream(builder, &stream_);
    AAudioStreamBuilder_delete(builder);

    if (result != AAUDIO_OK || !stream_) {
        gAudioDebug.aaudioError.store(result);
        return false;
    }

    /* 🔑 Read back ACTUAL hardware format */
    sampleRate_   = AAudioStream_getSampleRate(stream_);
    channelCount_ = AAudioStream_getChannelCount(stream_);

    // Invariant: ring buffer stores SAMPLES (samples = frames * channelCount_)
    if (channelCount_ <= 0) {
        LOGE("Invalid channelCount_%d, defaulting to 2", channelCount_);
        channelCount_ = 2;
    }

    if ((kRingBufferSize % channelCount_) != 0) {
        LOGE("kRingBufferSize (%d) is not a multiple of channelCount (%d)", kRingBufferSize, channelCount_);
    }

    gAudioDebug.aaudioOpened.store(true);
    return true;
}

void AudioEngine::cleanupAAudio() {
    if (stream_) {
        AAudioStream_close(stream_);
        stream_ = nullptr;
    }
}

void AudioEngine::cleanupMedia() {
    // Ensure decoder thread is stopped before touching MediaCodec
    isPlaying_.store(false);
    if (decodeThread_.joinable()) {
        decodeThread_.join();
    }
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

/* ===================== Ring Buffer ===================== */

int AudioEngine::readPcm(int16_t* out, int frames) {
    int32_t r = readHead_.load(std::memory_order_relaxed);
    int32_t w = writeHead_.load(std::memory_order_acquire);
    int32_t availSamples = w - r;
    if (availSamples <= 0) return 0;

    int32_t availFrames = availSamples / channelCount_;
    int n = std::min<int32_t>(frames, availFrames);

    for (int f = 0; f < n; ++f) {
        int32_t base = (r + f * channelCount_) % kRingBufferSize;
        for (int c = 0; c < channelCount_; ++c) {
            out[f * channelCount_ + c] = ringBuffer_[(base + c) % kRingBufferSize];
        }
    }

    readHead_.store(r + n * channelCount_, std::memory_order_release);
    gAudioDebug.bufferFill.store((w - (r + n * channelCount_)) / channelCount_);
    return n;
}

void AudioEngine::writePcmBlocking(const int16_t* in, int frames) {
    for (int f = 0; f < frames && isPlaying_.load(); ++f) {
        while ((writeHead_.load() - readHead_.load()) >= kRingBufferSize) {
            std::this_thread::sleep_for(std::chrono::milliseconds(1));
        }

        int32_t w = writeHead_.load();
        int32_t base = w % kRingBufferSize;

        for (int c = 0; c < channelCount_; ++c) {
            ringBuffer_[(base + c) % kRingBufferSize] = in[f * channelCount_ + c];
        }

        writeHead_.fetch_add(channelCount_);
    }

    gAudioDebug.bufferFill.store((writeHead_.load() - readHead_.load()) / channelCount_);
}

/* ===================== MediaCodec Decode ===================== */

void AudioEngine::decodeLoop() {
    while (isPlaying_.load()) {
        // ---- FEED INPUT ----
        ssize_t inIndex = AMediaCodec_dequeueInputBuffer(codec_, 0);
        if (inIndex >= 0) {
            size_t bufSize = 0;
            uint8_t* buf = AMediaCodec_getInputBuffer(codec_, inIndex, &bufSize);

            ssize_t sampleSize = AMediaExtractor_readSampleData(extractor_, buf, bufSize);

            if (sampleSize > 0) {
                int64_t pts = AMediaExtractor_getSampleTime(extractor_);
                AMediaCodec_queueInputBuffer(
                    codec_,
                    inIndex,
                    0,
                    sampleSize,
                    pts,
                    0
                );
                AMediaExtractor_advance(extractor_);
            } else {
                // End of stream
                AMediaCodec_queueInputBuffer(
                    codec_,
                    inIndex,
                    0,
                    0,
                    0,
                    AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM
                );
            }
        }

        AMediaCodecBufferInfo info{};
        ssize_t index = AMediaCodec_dequeueOutputBuffer(codec_, &info, 2000);

        if (index >= 0) {
            uint8_t* buf = AMediaCodec_getOutputBuffer(codec_, index, nullptr);

            if (buf && info.size > 0) {
                int16_t* samples =
                    reinterpret_cast<int16_t*>(buf + info.offset);
                int count = info.size / sizeof(int16_t);

                bool written = false;

                while (isPlaying_.load(std::memory_order_acquire)) {
                    if (writeAudio(samples, count)) {
                        written = true;
                        break; // ✅ success
                    }

                    // 🟡 buffer full → wait for audio callback to consume
                    std::this_thread::sleep_for(std::chrono::milliseconds(2));

                    // 🛑 SAFETY: if audio stopped, do not deadlock
                    if (!gAudioDebug.aaudioStarted.load()) {
                        break;
                    }
                }

                if (written) {
                    gAudioDebug.decoderProduced.store(true);
                }
            }

            AMediaCodec_releaseOutputBuffer(codec_, index, false);
        }
        else if (index == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
            AMediaFormat* newFormat = AMediaCodec_getOutputFormat(codec_);
            AMediaFormat_delete(newFormat);
        }
        else if (index == AMEDIACODEC_INFO_TRY_AGAIN_LATER) {
            std::this_thread::sleep_for(std::chrono::milliseconds(2));
        }
    }
}

/* ===================== Producer (lock-free) ===================== */

bool AudioEngine::writeAudio(const int16_t* data, int32_t samples) {
    // Sanity: samples should be a multiple of channelCount_ (samples = frames * channelCount_)
    if ((samples % channelCount_) != 0) {
        LOGE("writeAudio called with samples (%d) not multiple of channelCount (%d)", samples, channelCount_);
        // continue anyway for robustness in release; this helps catch misuse during testing
    }
    int32_t head = writeHead_.load(std::memory_order_acquire);
    int32_t tail = readHead_.load(std::memory_order_acquire);

    int32_t used = head - tail;
    int32_t available = kRingBufferSize - used;

    // 🔴 DO NOT DROP AUDIO DURING PLAYBACK
    if (available < samples) {
        return false; // buffer full
    }

    for (int32_t i = 0; i < samples; i++) {
        ringBuffer_[head % kRingBufferSize] = data[i];
        head++;
    }

    writeHead_.store(head, std::memory_order_release);
    // Debug: expose raw buffer fill (samples) to help detect producer/consumer mismatch
    gAudioDebug.bufferFill.store(writeHead_.load(std::memory_order_relaxed) -
                                readHead_.load(std::memory_order_relaxed));
    return true;
}

void AudioEngine::renderAudio(int16_t* out, int32_t samples) {
    int32_t head = writeHead_.load(std::memory_order_acquire);
    int32_t tail = readHead_.load(std::memory_order_acquire);
    int32_t available = head - tail;

    int32_t toRead = std::min(samples, available);

    for (int i = 0; i < toRead; i++) {
        out[i] = ringBuffer_[tail % kRingBufferSize];
        tail++;
    }

    // 🔇 Fill silence on underrun
    for (int i = toRead; i < samples; i++) {
        out[i] = 0;
    }

    readHead_.store(tail, std::memory_order_release);
}

void AudioEngine::flushRingBuffer() {
    readHead_.store(0);
    writeHead_.store(0);
}

int64_t AudioEngine::getClockUs() const {
    if (!stream_) return 0;

    int64_t framePos = 0;
    int64_t timeNs = 0;

    if (AAudioStream_getTimestamp(
            stream_,
            CLOCK_MONOTONIC,
            &framePos,
            &timeNs) != AAUDIO_OK) {
        return 0;
    }

    return timeNs / 1000;
}

int32_t AudioEngine::framesToSamples(int32_t frames) const {
    return frames * channelCount_;
}

/* ===================== AAudio Callback ===================== */

aaudio_data_callback_result_t AudioEngine::audioCallback(
        AAudioStream*,
        void* userData,
        void* audioData,
        int32_t numFrames) {

        auto* engine = static_cast<AudioEngine*>(userData);
        auto* out = static_cast<int16_t*>(audioData);

        int32_t samplesNeeded = engine->framesToSamples(numFrames);
        engine->renderAudio(out, samplesNeeded);

        gAudioDebug.callbackCalled.store(true);
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }