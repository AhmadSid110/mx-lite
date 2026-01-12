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

    aaudio_result_t result = AAudioStream_requestStart(stream_);
    if (result != AAUDIO_OK) {
        gAudioDebug.aaudioError.store(result);
        return;
    }

    // Force a usable buffer size after starting — some devices default to 0
    // which leaves the callback thread non-functional. Use 4x frames-per-burst.
    int32_t frames = AAudioStream_getFramesPerBurst(stream_);
    AAudioStream_setBufferSizeInFrames(stream_, frames * 4);

#if __ANDROID_API__ < 28
    // 🔁 API 26–27 fallback: poll state asynchronously
    std::thread([this]() {
        for (int i = 0; i < 50; ++i) { // ~500ms max
            aaudio_stream_state_t state =
                    AAudioStream_getState(stream_);
            if (state == AAUDIO_STREAM_STATE_STARTED) {
                gAudioDebug.aaudioStarted.store(true);
                return;
            }
            std::this_thread::sleep_for(
                    std::chrono::milliseconds(10));
        }
    }).detach();
#endif
}

void AudioEngine::stop() {
    running_.store(false);

    if (decodeThread_.joinable())
        decodeThread_.join();

    if (stream_)
        AAudioStream_requestStop(stream_);
}

void AudioEngine::seekUs(int64_t us) {
    stop();

    writePos_.store(0);
    readPos_.store(0);
    std::fill(ring_.begin(), ring_.end(), 0);

    if (clock_)
        clock_->setUs(us);

    if (extractor_)
        AMediaExtractor_seekTo(extractor_, us, AMEDIAEXTRACTOR_SEEK_CLOSEST_SYNC);

    if (codec_)
        AMediaCodec_flush(codec_);

    start();
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
    int64_t r = readPos_.load();
    int64_t w = writePos_.load();
    int64_t avail = w - r;
    if (avail <= 0) return 0;

    int n = (int)std::min<int64_t>(frames, avail);

    for (int f = 0; f < n; ++f) {
        size_t base = ((r + f) % ringFrames_) * channelCount_;
        memcpy(out + f * channelCount_,
               &ring_[base],
               channelCount_ * sizeof(int16_t));
    }

    readPos_.store(r + n);
    gAudioDebug.bufferFill.store(w - (r + n));
    return n;
}

void AudioEngine::writePcmBlocking(const int16_t* in, int frames) {
    for (int f = 0; f < frames && running_.load(); ++f) {
        while ((writePos_.load() - readPos_.load()) >= ringFrames_) {
            std::this_thread::sleep_for(std::chrono::milliseconds(1));
        }

        int64_t w = writePos_.load();
        size_t base = (w % ringFrames_) * channelCount_;

        memcpy(&ring_[base],
               in + f * channelCount_,
               channelCount_ * sizeof(int16_t));

        writePos_.fetch_add(1);
    }

    gAudioDebug.bufferFill.store(writePos_.load() - readPos_.load());
}

/* ===================== MediaCodec Decode ===================== */

void AudioEngine::decodeLoop() {

    AMediaCodecBufferInfo info;
    std::vector<int16_t> tmp;

    while (running_.load()) {

        ssize_t in = AMediaCodec_dequeueInputBuffer(codec_, 2000);
        if (in >= 0) {
            size_t cap;
            uint8_t* buf = AMediaCodec_getInputBuffer(codec_, in, &cap);
            ssize_t sz = AMediaExtractor_readSampleData(extractor_, buf, cap);

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

        ssize_t out = AMediaCodec_dequeueOutputBuffer(codec_, &info, 2000);
        if (out >= 0 && info.size > 0) {

            uint8_t* raw = AMediaCodec_getOutputBuffer(codec_, out, nullptr);

            bool isFloat = (info.size % (sizeof(float) * channelCount_) == 0);
            int samples = info.size / (isFloat ? sizeof(float) : sizeof(int16_t));
            int frames = samples / channelCount_;

            tmp.resize(samples);

            if (isFloat) {
                float* f = (float*)(raw + info.offset);
                for (int i = 0; i < samples; ++i)
                    tmp[i] = floatToPcm16(f[i]);
            } else {
                memcpy(tmp.data(), raw + info.offset, info.size);
            }

            gAudioDebug.decoderProduced.store(true);
            writePcmBlocking(tmp.data(), frames);

            AMediaCodec_releaseOutputBuffer(codec_, out, false);
        }
    }
}

/* ===================== AAudio Callback ===================== */

aaudio_data_callback_result_t AudioEngine::audioCallback(
        AAudioStream*,
        void*,
        void* audioData,
        int32_t numFrames) {

    gAudioDebug.callbackCalled.store(true);

    // Touch memory to force fault if callback fires
    ((int16_t*)audioData)[0] = 0;

    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}