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
#include <sys/stat.h>
#include <atomic>

#define LOG_TAG "AudioEngine"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern AudioDebug gAudioDebug;

// Global indicator of audio health. True when audio track is running and timestamps are valid.
std::atomic<bool> gAudioHealthy{false};

#if __ANDROID_API__ >= 28
static void aaudioStateCallback(
        AAudioStream*,
        void* userData,
        aaudio_stream_state_t state,
        aaudio_stream_state_t /* previous */) {
    // Legacy callback - currently unused in Fire & Forget model
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

    // Reset audio-track flag for this new file (MANDATORY)
    hasAudioTrack_ = false;
    
    // ✅ Reset Clocks on new file
    seekOffsetUs_.store(0);
    startFramePosition_ = 0;

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

    // Mark that we found an audio track
    hasAudioTrack_ = true;

    gAudioDebug.openStage.store(3);

    AMediaExtractor_selectTrack(extractor_, audioTrack);

    const char* mime = nullptr;
    AMediaFormat_getString(format_, AMEDIAFORMAT_KEY_MIME, &mime);

    // Read audio format from the track and set safe defaults if missing
    int32_t sr = 0;
    int32_t ch = 0;
    AMediaFormat_getInt32(format_, AMEDIAFORMAT_KEY_SAMPLE_RATE, &sr);
    AMediaFormat_getInt32(format_, AMEDIAFORMAT_KEY_CHANNEL_COUNT, &ch);

    sampleRate_   = (sr > 0) ? sr : 48000;
    channelCount_ = (ch > 0) ? ch : 2;

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

    // Reset audio-track flag for this new file (MANDATORY)
    hasAudioTrack_ = false;

    // Rewind fix: ensure duplicated fd points to file start. Some Java callers
    // may have advanced the shared offset which breaks native extraction.
    if (lseek(dupFd, 0, SEEK_SET) < 0) {
        close(dupFd);
        return false;
    }
    
    // ✅ Reset Clocks on new file
    seekOffsetUs_.store(0);
    startFramePosition_ = 0;

    gAudioDebug.openStage.store(1);

    extractor_ = AMediaExtractor_new();
    if (!extractor_) {
        close(dupFd);
        return false;
    }

    // NDK requirement: AMediaExtractor_setDataSourceFd does NOT accept length=-1.
    // Calculate actual file length using fstat and pass it explicitly.
    struct stat st {};
    if (fstat(dupFd, &st) != 0) {
        close(dupFd);
        LOGE("fstat(dupFd) failed");
        return false;
    }

    int64_t fileLength = st.st_size;

    // Ensure fd is at start
    if (lseek(dupFd, 0, SEEK_SET) < 0) {
        close(dupFd);
        LOGE("lseek(dupFd) failed");
        return false;
    }

    if (AMediaExtractor_setDataSourceFd(
            extractor_, dupFd, offset, fileLength) != AMEDIA_OK) {
        close(dupFd);
        LOGE("Extractor setDataSourceFd FAILED");
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

    // Mark that we found an audio track
    hasAudioTrack_ = true;

    gAudioDebug.openStage.store(3);

    AMediaExtractor_selectTrack(extractor_, audioTrack);

    const char* mime = nullptr;
    AMediaFormat_getString(format_, AMEDIAFORMAT_KEY_MIME, &mime);

    // Read audio format from the track and set safe defaults if missing
    int32_t sr = 0;
    int32_t ch = 0;
    AMediaFormat_getInt32(format_, AMEDIAFORMAT_KEY_SAMPLE_RATE, &sr);
    AMediaFormat_getInt32(format_, AMEDIAFORMAT_KEY_CHANNEL_COUNT, &ch);

    sampleRate_   = (sr > 0) ? sr : 48000;
    channelCount_ = (ch > 0) ? ch : 2;

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

    // Enable output and decoding
    audioOutputEnabled_.store(true);
    decodeEnabled_.store(true);
    isPlaying_.store(true);

    // Fire & Forget Start
    AAudioStream_requestStart(stream_);
    // Capture frame position after starting to ensure baseline aligns with
    // the moment the hardware begins producing frames.
    startFramePosition_ = AAudioStream_getFramesRead(stream_);
    gAudioDebug.aaudioStarted.store(true);
    // Mark audio as healthy when we start the hardware stream
    gAudioHealthy.store(true);

    if (decodeThread_.joinable()) {
        decodeThread_.join();
    }
    decodeThread_ = std::thread(&AudioEngine::decodeLoop, this);
}

void AudioEngine::pause() {
    // ⛔ HARD STOP AUDIO OUTPUT FIRST
    audioOutputEnabled_.store(false);
    decodeEnabled_.store(false);
    isPlaying_.store(false);

    if (decodeThread_.joinable())
        decodeThread_.join();

    if (stream_)
        AAudioStream_requestStop(stream_);

    gAudioHealthy.store(false);
}

void AudioEngine::stop() {
    isPlaying_.store(false);

    if (decodeThread_.joinable())
        decodeThread_.join();

    if (stream_) {
        AAudioStream_requestStop(stream_);
        gAudioHealthy.store(false);
    }
}

void AudioEngine::seekUs(int64_t us) {
    // 1. STOP EVERYTHING
    audioOutputEnabled_.store(false);
    decodeEnabled_.store(false);
    isPlaying_.store(false);

    if (decodeThread_.joinable())
        decodeThread_.join();

    if (stream_)
        AAudioStream_requestStop(stream_);

    // 2. RESET CLOCK BASELINE
    seekOffsetUs_.store(us);

    // 3. CLEAR BUFFERS
    flushRingBuffer();
    memset(ringBuffer_, 0, sizeof(ringBuffer_));

    // 4. RESET MEDIA PIPELINE
    if (extractor_)
        AMediaExtractor_seekTo(extractor_, us, AMEDIAEXTRACTOR_SEEK_CLOSEST_SYNC);

    if (codec_)
        AMediaCodec_flush(codec_);

    // 5. RESTART AUDIO
    audioOutputEnabled_.store(true);
    decodeEnabled_.store(true);
    isPlaying_.store(true);

    if (stream_) {
        AAudioStream_requestStart(stream_);
        startFramePosition_ = AAudioStream_getFramesRead(stream_);
    }

    // 6. RESTART DECODE
    if (decodeThread_.joinable()) {
        decodeThread_.join();
    }
    decodeThread_ = std::thread(&AudioEngine::decodeLoop, this);
}

/* ===================== AAudio ===================== */

bool AudioEngine::setupAAudio() {

    gAudioDebug.aaudioError.store(-999); // probe

    AAudioStreamBuilder* builder = nullptr;
    aaudio_result_t result = AAudio_createStreamBuilder(&builder);

    if (result != AAUDIO_OK) {
        gAudioDebug.aaudioError.store(result);
        gAudioHealthy.store(false);
        LOGE("AAudio createStreamBuilder failed: %s", AAudio_convertResultToText(result));
        return false;
    }

    // Configure builder with SAFE parameters (do NOT auto-detect or use float)
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
    // Use format values discovered from MediaCodec if available
    AAudioStreamBuilder_setChannelCount(builder, channelCount_);
    AAudioStreamBuilder_setSampleRate(builder, sampleRate_);

    AAudioStreamBuilder_setSharingMode(
        builder, AAUDIO_SHARING_MODE_SHARED);

    // Explicit direction is required on some OEM ROMs (MIUI/ColorOS) where
    // implicit direction can cause the stream to hang and the callback to never fire.
    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);

    AAudioStreamBuilder_setPerformanceMode(
        builder, AAUDIO_PERFORMANCE_MODE_NONE);

    // VERY IMPORTANT: use data callback for delivery
    // Use the exact symbol name required by the platform/QA
    AAudioStreamBuilder_setDataCallback(
        builder, AudioEngine::dataCallback, this);

    // Try opening the stream and log detailed reason if it fails
    result = AAudioStreamBuilder_openStream(builder, &stream_);
    AAudioStreamBuilder_delete(builder);

    if (result != AAUDIO_OK || !stream_) {
        gAudioDebug.aaudioError.store(result);
        gAudioHealthy.store(false);
        LOGE("AAudio open failed: %s", AAudio_convertResultToText(result));
        return false;
    }

    /* 🔑 Read back ACTUAL hardware format */
    sampleRate_   = AAudioStream_getSampleRate(stream_);
    channelCount_ = AAudioStream_getChannelCount(stream_);

    if (channelCount_ <= 0) {
        LOGE("Invalid channelCount_%d, defaulting to 2", channelCount_);
        channelCount_ = 2;
    }

    gAudioDebug.aaudioOpened.store(true);

    LOGD("AAudio stream opened (sampleRate=%d channels=%d)", sampleRate_, channelCount_);

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
    // Legacy function - used by blocking implementations only
    return 0; 
}

void AudioEngine::writePcmBlocking(const int16_t* in, int frames) {
    // Legacy function
}

/* ===================== MediaCodec Decode ===================== */

void AudioEngine::decodeLoop() {
    while (isPlaying_.load() && decodeEnabled_.load()) {

        // =============================
        // INPUT STAGE (MANDATORY)
        // =============================
        ssize_t inIndex = AMediaCodec_dequeueInputBuffer(codec_, 0);
        if (inIndex >= 0) {
            size_t bufSize;
            uint8_t* buf =
                AMediaCodec_getInputBuffer(codec_, inIndex, &bufSize);

            if (buf) {
                ssize_t size =
                    AMediaExtractor_readSampleData(extractor_, buf, bufSize);

                if (size > 0) {
                    int64_t pts =
                        AMediaExtractor_getSampleTime(extractor_);
                    AMediaCodec_queueInputBuffer(
                        codec_, inIndex, 0, size, pts, 0);
                    AMediaExtractor_advance(extractor_);
                } else {
                    AMediaCodec_queueInputBuffer(
                        codec_, inIndex, 0, 0, 0,
                        AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
                }
            }
        }

        // =============================
        // OUTPUT STAGE
        // =============================
        AMediaCodecBufferInfo info;
        ssize_t outIndex =
            AMediaCodec_dequeueOutputBuffer(codec_, &info, 2000);

        if (outIndex >= 0) {
            uint8_t* buf =
                AMediaCodec_getOutputBuffer(codec_, outIndex, nullptr);

            if (buf && info.size > 0) {
                int16_t* samples =
                    reinterpret_cast<int16_t*>(buf + info.offset);

                int32_t count = info.size / sizeof(int16_t);

                // BACKPRESSURE LOOP
                while (isPlaying_.load()) {
                    if (writeAudio(samples, count)) break;
                    
                    if (!stream_) break; // Bail if audio engine died
                    
                    std::this_thread::sleep_for(
                        std::chrono::milliseconds(2));
                }
            }

            AMediaCodec_releaseOutputBuffer(codec_, outIndex, false);
        }
        else if (outIndex ==
                 AMEDIACODEC_INFO_TRY_AGAIN_LATER) {
            std::this_thread::sleep_for(
                std::chrono::milliseconds(2));
        }
    }
}

/* ===================== Producer (lock-free) ===================== */

bool AudioEngine::writeAudio(const int16_t* data, int32_t samples) {
    if ((samples % channelCount_) != 0) {
        // Robustness: ignore partial frames logic for now, just warn
    }
    int32_t head = writeHead_.load(std::memory_order_acquire);
    int32_t tail = readHead_.load(std::memory_order_acquire);

    int32_t used = head - tail;
    int32_t available = kRingBufferSize - used;

    if (available < samples) {
        return false; // buffer full
    }

    for (int32_t i = 0; i < samples; i++) {
        ringBuffer_[head % kRingBufferSize] = data[i];
        head++;
    }

    writeHead_.store(head, std::memory_order_release);
    gAudioDebug.bufferFill.store((writeHead_.load(std::memory_order_relaxed) -
                                readHead_.load(std::memory_order_relaxed)) / channelCount_);
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

// 🔴 THE FIX: ADD 'const' TO MATCH HEADER 🔴
int64_t AudioEngine::getClockUs() const {
    if (!stream_) return seekOffsetUs_.load();

    // 🔒 PAUSE SAFETY: 
    // If paused, hardware clock might drift or report stale values.
    // Force return the static seek position to guarantee video freeze.
    if (!isPlaying_) {
        return seekOffsetUs_.load();
    }

    int64_t framePos = 0;
    int64_t timeNs = 0;

    aaudio_result_t res = AAudioStream_getTimestamp(
            stream_, CLOCK_MONOTONIC, &framePos, &timeNs);

    // If timestamp failed, return fallback
    if (res != AAUDIO_OK) {
        return seekOffsetUs_.load();
    }

    // 1. How many frames played since the last seek?
    int64_t framesPlayed = framePos - startFramePosition_;
    
    // 2. Safe sample rate
    int32_t rate = (sampleRate_ > 0) ? sampleRate_ : 48000;
    
    // 3. Convert to time
    // Logic: Total Time = Seek Start + Time Elapsed
    return seekOffsetUs_.load() + (framesPlayed * 1000000LL) / rate;
}

int32_t AudioEngine::framesToSamples(int32_t frames) const {
    return frames * channelCount_;
}

/* ===================== AAudio Callback ===================== */

aaudio_data_callback_result_t AudioEngine::dataCallback(
        AAudioStream* /*stream*/,
        void* userData,
        void* audioData,
        int32_t numFrames) {

        auto* engine = static_cast<AudioEngine*>(userData);
        auto* out = static_cast<int16_t*>(audioData);

        // Mark that the callback fired
        gAudioDebug.callbackCalled.store(true);

        // 🔒 HARD GATE: if audio output is disabled, write silence and return
        if (!engine->audioOutputEnabled_.load()) {
            memset(out, 0, engine->framesToSamples(numFrames) * sizeof(int16_t));
            return AAUDIO_CALLBACK_RESULT_CONTINUE;
        }

        int32_t samplesNeeded = engine->framesToSamples(numFrames);
        engine->renderAudio(out, samplesNeeded);

        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }
