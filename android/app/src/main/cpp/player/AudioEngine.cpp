#include "AudioEngine.h"

#include <android/log.h>
#include <cstring>
#include <cmath>
#include <chrono>
#include <thread>
#include <algorithm>

#define LOG_TAG "AudioEngineDBG"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/* ───── PCM helper ───── */

static inline int16_t floatToPcm16(float v) {
    if (v > 1.0f) v = 1.0f;
    if (v < -1.0f) v = -1.0f;
    return static_cast<int16_t>(v * 32767.0f);
}

/* ───── Lifecycle ───── */

AudioEngine::AudioEngine(Clock* clock)
        : clock_(clock) {}

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

/* ───── DEBUG GETTERS ───── */

bool AudioEngine::isAAudioOpened() const { return aaudioOpened_.load(); }
bool AudioEngine::isAAudioStarted() const { return aaudioStarted_.load(); }
bool AudioEngine::isCallbackRunning() const { return callbackSeen_.load(); }
int64_t AudioEngine::getCallbackCount() const { return callbackCount_.load(); }
int64_t AudioEngine::getFramesPlayed() const { return framesPlayed_.load(); }

/* ───── Open ───── */

bool AudioEngine::open(const char* path) {
    LOGD("open()");

    extractor_ = AMediaExtractor_new();
    if (!extractor_) return false;

    if (AMediaExtractor_setDataSource(extractor_, path) != AMEDIA_OK)
        return false;

    int audioTrack = -1;
    size_t tracks = AMediaExtractor_getTrackCount(extractor_);

    for (size_t i = 0; i < tracks; i++) {
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

    ringFrames_ = sampleRate_ / 2;
    ring_.resize(ringFrames_ * channelCount_);

    if (!setupAAudio()) return false;
    if (AMediaCodec_start(codec_) != AMEDIA_OK) return false;

    LOGD("open() SUCCESS");
    return true;
}

/* ───── AAudio ───── */

bool AudioEngine::setupAAudio() {
    AAudioStreamBuilder* b = nullptr;
    AAudio_createStreamBuilder(&b);

    AAudioStreamBuilder_setDirection(b, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setSharingMode(b, AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setFormat(b, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setChannelCount(b, channelCount_);
    AAudioStreamBuilder_setSampleRate(b, sampleRate_);
    AAudioStreamBuilder_setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setDataCallback(b, audioCallback, this);

    if (AAudioStreamBuilder_openStream(b, &stream_) != AAUDIO_OK) {
        AAudioStreamBuilder_delete(b);
        LOGE("AAudio open FAILED");
        return false;
    }

    AAudioStreamBuilder_delete(b);
    aaudioOpened_.store(true);

    if (AAudioStream_requestStart(stream_) != AAUDIO_OK) {
        LOGE("AAudio start FAILED");
        return false;
    }

    aaudioStarted_.store(true);
    LOGD("AAudio started");
    return true;
}

void AudioEngine::cleanupAAudio() {
    if (stream_) {
        AAudioStream_requestStop(stream_);
        AAudioStream_close(stream_);
        stream_ = nullptr;
    }
}

/* ───── Control ───── */

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

void AudioEngine::seekUs(int64_t us) {
    stop();
    AMediaExtractor_seekTo(extractor_, us, AMEDIAEXTRACTOR_SEEK_CLOSEST_SYNC);
    AMediaCodec_flush(codec_);
    writePos_.store(0);
    readPos_.store(0);
    clock_->setUs(us);
    start();
}

/* ───── Ring buffer ───── */

void AudioEngine::writePcmBlocking(const int16_t* in, int frames) {
    int w = 0;
    while (w < frames && running_) {
        int64_t wp = writePos_.load();
        int64_t rp = readPos_.load();
        int64_t free = ringFrames_ - (wp - rp);
        if (free <= 0) {
            std::this_thread::sleep_for(std::chrono::milliseconds(2));
            continue;
        }
        int chunk = std::min((int)free, frames - w);
        for (int i = 0; i < chunk * channelCount_; i++)
            ring_[(wp * channelCount_ + i) % ring_.size()] =
                    in[w * channelCount_ + i];
        writePos_.store(wp + chunk);
        w += chunk;
    }
}

int AudioEngine::readPcm(int16_t* out, int frames) {
    int64_t rp = readPos_.load();
    int64_t wp = writePos_.load();
    int avail = (int)(wp - rp);
    if (avail <= 0) return 0;

    int chunk = std::min(avail, frames);
    for (int i = 0; i < chunk * channelCount_; i++)
        out[i] = ring_[(rp * channelCount_ + i) % ring_.size()];
    readPos_.store(rp + chunk);
    return chunk;
}

/* ───── Decoder ───── */

void AudioEngine::decodeLoop() {
    AMediaCodecBufferInfo info;
    std::vector<int16_t> tmp;

    while (running_) {
        ssize_t in = AMediaCodec_dequeueInputBuffer(codec_, 2000);
        if (in >= 0) {
            size_t cap;
            uint8_t* buf = AMediaCodec_getInputBuffer(codec_, in, &cap);
            ssize_t sz = AMediaExtractor_readSampleData(extractor_, buf, cap);
            if (sz < 0) break;
            AMediaCodec_queueInputBuffer(
                    codec_, in, 0, sz,
                    AMediaExtractor_getSampleTime(extractor_), 0);
            AMediaExtractor_advance(extractor_);
        }

        ssize_t out = AMediaCodec_dequeueOutputBuffer(codec_, &info, 2000);
        if (out >= 0 && info.size > 0) {
            uint8_t* raw = AMediaCodec_getOutputBuffer(codec_, out, nullptr);
            bool isFloat = info.size % (sizeof(float) * channelCount_) == 0;
            int samples = info.size / (isFloat ? sizeof(float) : sizeof(int16_t));
            int frames = samples / channelCount_;
            tmp.resize(samples);
            if (isFloat) {
                float* f = (float*)(raw + info.offset);
                for (int i = 0; i < samples; i++)
                    tmp[i] = floatToPcm16(f[i]);
            } else {
                memcpy(tmp.data(), raw + info.offset, info.size);
            }
            writePcmBlocking(tmp.data(), frames);
            AMediaCodec_releaseOutputBuffer(codec_, out, false);
        }
    }
}

/* ───── CALLBACK ───── */

aaudio_data_callback_result_t AudioEngine::audioCallback(
        AAudioStream*, void* userData, void* audioData, int32_t numFrames) {

    auto* self = static_cast<AudioEngine*>(userData);

    self->callbackSeen_.store(true);
    self->callbackCount_.fetch_add(1);

    int16_t* out = (int16_t*)audioData;
    int frames = self->readPcm(out, numFrames);

    if (frames < numFrames) {
        memset(out + frames * self->channelCount_,
               0,
               (numFrames - frames) *
               self->channelCount_ * sizeof(int16_t));
    }

    self->framesPlayed_.fetch_add(numFrames);
    self->clock_->addUs(
            (int64_t)numFrames * 1'000'000LL / self->sampleRate_);

    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}