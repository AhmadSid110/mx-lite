#include "AudioEngine.h"
#include "AudioDebug.h"

#include <aaudio/AAudio.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaExtractor.h>

#include <algorithm>
#include <android/log.h>
#include <atomic>
#include <cstring>
#include <sys/stat.h>
#include <thread>
#include <unistd.h>

#define LOG_TAG "AudioEngine"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern AudioDebug gAudioDebug;

// Global indicator of audio health. True when audio track is running and
// timestamps are valid.
std::atomic<bool> gAudioHealthy{false};

#if __ANDROID_API__ >= 28
static void aaudioStateCallback(AAudioStream *, void *userData,
                                aaudio_stream_state_t state,
                                aaudio_stream_state_t /* previous */) {
  // Legacy callback - currently unused in Fire & Forget model
}
#endif

/* ===================== PCM helper ===================== */

static inline int16_t floatToPcm16(float v) {
  if (v > 1.0f)
    v = 1.0f;
  if (v < -1.0f)
    v = -1.0f;
  return static_cast<int16_t>(v * 32767.0f);
}

/* ===================== Lifecycle ===================== */

AudioEngine::AudioEngine(VirtualClock *clock) : virtualClock_(clock) {
  gAudioDebug.engineCreated.store(true);
}

AudioEngine::~AudioEngine() {
  stop();
  cleanupAAudio();
  cleanupMedia();
}

/* ===================== Open ===================== */

bool AudioEngine::open(const char *path) {
  gAudioDebug.openStage.store(1);

  // Reset audio-track flag for this new file (MANDATORY)
  hasAudioTrack_ = false;

  extractor_ = AMediaExtractor_new();
  if (!extractor_)
    return false;

  if (AMediaExtractor_setDataSource(extractor_, path) != AMEDIA_OK) {
    return false;
  }
  gAudioDebug.openStage.store(2);

  int audioTrack = -1;
  size_t trackCount = AMediaExtractor_getTrackCount(extractor_);

  for (size_t i = 0; i < trackCount; ++i) {
    AMediaFormat *fmt = AMediaExtractor_getTrackFormat(extractor_, i);
    const char *mime = nullptr;

    if (AMediaFormat_getString(fmt, AMEDIAFORMAT_KEY_MIME, &mime) && mime &&
        !strncmp(mime, "audio/", 6)) {

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

  const char *mime = nullptr;
  AMediaFormat_getString(format_, AMEDIAFORMAT_KEY_MIME, &mime);

  // Read audio format from the track and set safe defaults if missing
  int32_t sr = 0;
  int32_t ch = 0;
  AMediaFormat_getInt32(format_, AMEDIAFORMAT_KEY_SAMPLE_RATE, &sr);
  AMediaFormat_getInt32(format_, AMEDIAFORMAT_KEY_CHANNEL_COUNT, &ch);

  sampleRate_ = (sr > 0) ? sr : 48000;
  channelCount_ = (ch > 0) ? ch : 2;

  // Extract duration from format
  int64_t durationUs = 0;
  if (AMediaFormat_getInt64(format_, AMEDIAFORMAT_KEY_DURATION, &durationUs)) {
    durationUs_ = durationUs;
  } else {
    durationUs_ = 0;
  }

  codec_ = AMediaCodec_createDecoderByType(mime);
  if (!codec_)
    return false;
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

  gAudioDebug.openStage.store(1);

  extractor_ = AMediaExtractor_new();
  if (!extractor_) {
    close(dupFd);
    return false;
  }

  // NDK requirement: AMediaExtractor_setDataSourceFd does NOT accept length=-1.
  // Calculate actual file length using fstat and pass it explicitly.
  struct stat st{};
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

  if (AMediaExtractor_setDataSourceFd(extractor_, dupFd, offset, fileLength) !=
      AMEDIA_OK) {
    close(dupFd);
    LOGE("Extractor setDataSourceFd FAILED");
    return false;
  }

  gAudioDebug.openStage.store(2);

  // ─── Find audio track ───
  int audioTrack = -1;
  size_t trackCount = AMediaExtractor_getTrackCount(extractor_);

  for (size_t i = 0; i < trackCount; ++i) {
    AMediaFormat *fmt = AMediaExtractor_getTrackFormat(extractor_, i);
    const char *mime = nullptr;

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

  const char *mime = nullptr;
  AMediaFormat_getString(format_, AMEDIAFORMAT_KEY_MIME, &mime);

  // Read audio format from the track and set safe defaults if missing
  int32_t sr = 0;
  int32_t ch = 0;
  AMediaFormat_getInt32(format_, AMEDIAFORMAT_KEY_SAMPLE_RATE, &sr);
  AMediaFormat_getInt32(format_, AMEDIAFORMAT_KEY_CHANNEL_COUNT, &ch);

  sampleRate_ = (sr > 0) ? sr : 48000;
  channelCount_ = (ch > 0) ? ch : 2;

  // Extract duration from format
  int64_t durationUs = 0;
  if (AMediaFormat_getInt64(format_, AMEDIAFORMAT_KEY_DURATION, &durationUs)) {
    durationUs_ = durationUs;
  } else {
    durationUs_ = 0;
  }

  codec_ = AMediaCodec_createDecoderByType(mime);
  if (!codec_)
    return false;

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

/*
 * Soft Pause Policy:
 * - Never stop the AAudio stream on pause.
 * - Never call driver APIs from the UI thread unless performing final
 * release/stop.
 * - Keep the stream alive; gate audio output in the callback and gate decoding
 * in the decode loop.
 * - Ensure decode loop is cooperative and non-blocking (timeout 0 on dequeue).
 * - This avoids freezes and guarantees instant silence on pause.
 */

void AudioEngine::start() {
  if (!stream_)
    return;

  // 🔴 REQUIRED ONCE: start the AAudio stream on the first start/play only
  if (!aaudioStarted_.exchange(true)) {
    aaudio_result_t r = AAudioStream_requestStart(stream_);
    if (r != AAUDIO_OK) {
      LOGE("AAudio start failed: %s", AAudio_convertResultToText(r));
      return;
    }
    gAudioDebug.aaudioStarted.store(true);
  }

  // Start or resume the VirtualClock (authoritative time source)
  if (!clockStarted_.exchange(true, std::memory_order_acq_rel)) {
    virtualClock_->start();
  } else {
    virtualClock_->resume();
  }

  audioOutputEnabled_.store(true, std::memory_order_release);
  decodeEnabled_.store(true, std::memory_order_release);
  threadRunning_.store(true, std::memory_order_release);
  gAudioHealthy.store(true);

  // Start decode thread if not already running (non-blocking)
  if (!decodeThread_.joinable()) {
    decodeThread_ = std::thread(&AudioEngine::decodeLoop, this);
  }
}

void AudioEngine::pause() {
  // Soft pause only: do not stop the driver or perform blocking operations on
  // UI thread.
  virtualClock_->pause();

  // 🔑 HARD GATE: Disable decoding and output
  decodeEnabled_.store(false, std::memory_order_release);
  audioOutputEnabled_.store(false, std::memory_order_release);

  // IMPORTANT: DO NOT call AAudioStream_requestStop(stream_);
  // DO NOT join threads, flush codec, or touch extractor here.

  gAudioHealthy.store(false, std::memory_order_release);
}

void AudioEngine::stop() {
  // 1️⃣ IMMEDIATELY mute audio and stop decoding
  audioOutputEnabled_.store(false, std::memory_order_release);
  decodeEnabled_.store(false, std::memory_order_release);
  threadRunning_.store(false,
                       std::memory_order_release); // Signal thread to exit

  // 2️⃣ NOW it is safe to join decode thread
  if (decodeThread_.joinable()) {
    decodeThread_.join();
  }

  // 3️⃣ Flush buffers
  flushRingBuffer();

  gAudioHealthy.store(false);
}

void AudioEngine::seekUs(int64_t us) {
  // 1. HARD STOP decode
  decodeEnabled_.store(false, std::memory_order_release);

  // 2. Reset buffers
  flushRingBuffer();
  if (codec_)
    AMediaCodec_flush(codec_);

  // 3. Seek extractor
  if (extractor_)
    AMediaExtractor_seekTo(extractor_, us, AMEDIAEXTRACTOR_SEEK_CLOSEST_SYNC);

  // 4. Reset virtual clock
  virtualClock_->seekUs(us);

  // 5. Resume decode
  decodeEnabled_.store(true, std::memory_order_release);
  audioOutputEnabled_.store(true, std::memory_order_release);
}

/* ===================== AAudio ===================== */

bool AudioEngine::setupAAudio() {

  gAudioDebug.aaudioError.store(-999); // probe

  AAudioStreamBuilder *builder = nullptr;
  aaudio_result_t result = AAudio_createStreamBuilder(&builder);

  if (result != AAUDIO_OK) {
    gAudioDebug.aaudioError.store(result);
    gAudioHealthy.store(false);
    LOGE("AAudio createStreamBuilder failed: %s",
         AAudio_convertResultToText(result));
    return false;
  }

  // Configure builder with SAFE parameters (do NOT auto-detect or use float)
  AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
  // Use format values discovered from MediaCodec if available
  AAudioStreamBuilder_setChannelCount(builder, channelCount_);
  AAudioStreamBuilder_setSampleRate(builder, sampleRate_);

  AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);

  // Explicit direction is required on some OEM ROMs (MIUI/ColorOS) where
  // implicit direction can cause the stream to hang and the callback to never
  // fire.
  AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);

  AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_NONE);

  // VERY IMPORTANT: use data callback for delivery
  // Use the exact symbol name required by the platform/QA
  AAudioStreamBuilder_setDataCallback(builder, AudioEngine::dataCallback, this);

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
  sampleRate_ = AAudioStream_getSampleRate(stream_);
  channelCount_ = AAudioStream_getChannelCount(stream_);

  if (channelCount_ <= 0) {
    LOGE("Invalid channelCount_%d, defaulting to 2", channelCount_);
    channelCount_ = 2;
  }

  gAudioDebug.aaudioOpened.store(true);

  LOGD("AAudio stream opened (sampleRate=%d channels=%d)", sampleRate_,
       channelCount_);

  return true;
}

void AudioEngine::cleanupAAudio() {
  if (stream_) {
    // Final destroy ONLY (stream must otherwise run forever)
    AAudioStream_close(stream_);
    stream_ = nullptr;
  }
}

void AudioEngine::cleanupMedia() {
  // Ensure decoder thread is stopped before touching MediaCodec
  decodeEnabled_.store(false, std::memory_order_release);
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

int AudioEngine::readPcm(int16_t *out, int frames) {
  // Legacy function - used by blocking implementations only
  return 0;
}

void AudioEngine::writePcmBlocking(const int16_t *in, int frames) {
  // Legacy function
}

/* ===================== MediaCodec Decode ===================== */

void AudioEngine::decodeLoop() {
  // Outer loop governed by threadRunning_
  while (threadRunning_.load(std::memory_order_acquire)) {

    // 🚨 CLOCK GATE - ABSOLUTE FIRST PRIORITY
    // DO NOTHING if clock is not running - no dequeue, no advance, no write
    if (!virtualClock_->isRunning()) {
      gAudioDebug.decodeActive.store(false);
      std::this_thread::sleep_for(std::chrono::milliseconds(2));
      continue;
    }

    // ⛔ HARD GATE: Sleep if decoding is disabled
    if (!decodeEnabled_.load(std::memory_order_acquire)) {
      gAudioDebug.decodeActive.store(false);
      std::this_thread::sleep_for(std::chrono::milliseconds(2));
      continue;
    }

    // 🛑 DEMAND GATE: Only decode if frames are requested by AAudio
    // This paces the decoder exactly to consumption.
    int32_t framesNeeded = framesRequested_.load(std::memory_order_acquire);
    if (framesNeeded <= 0) {
      gAudioDebug.decodeActive.store(false);
      std::this_thread::sleep_for(std::chrono::milliseconds(1));
      continue;
    }

    // ✅ All gates passed - decode is active
    gAudioDebug.decodeActive.store(true);

    // Decode ONE buffer cycle (Input + Output)
    // ----------------------------------------

    // INPUT STAGE
    ssize_t inIndex = AMediaCodec_dequeueInputBuffer(codec_, 0);
    if (inIndex >= 0) {
      size_t bufSize;
      uint8_t *buf = AMediaCodec_getInputBuffer(codec_, inIndex, &bufSize);
      if (buf) {
        ssize_t size = AMediaExtractor_readSampleData(extractor_, buf, bufSize);
        if (size > 0) {
          int64_t pts = AMediaExtractor_getSampleTime(extractor_);
          AMediaCodec_queueInputBuffer(codec_, inIndex, 0, size, pts, 0);
          AMediaExtractor_advance(extractor_);
        } else {
          AMediaCodec_queueInputBuffer(codec_, inIndex, 0, 0, 0,
                                       AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
        }
      }
    }

    // OUTPUT STAGE
    AMediaCodecBufferInfo info;
    ssize_t outIndex =
        AMediaCodec_dequeueOutputBuffer(codec_, &info, 0); // Non-blocking

    if (outIndex >= 0) {
      uint8_t *buf = AMediaCodec_getOutputBuffer(codec_, outIndex, nullptr);

      if (buf && info.size > 0) {
        int16_t *samples = reinterpret_cast<int16_t *>(buf + info.offset);
        int32_t count = info.size / sizeof(int16_t);

        // Write to Ring Buffer (blocking if full, but demand is limited so
        // rare)
        int32_t written = 0;
        while (decodeEnabled_.load(std::memory_order_acquire)) {
          if (writeAudio(samples, count)) {
            written = count;
            break;
          }
          std::this_thread::sleep_for(std::chrono::milliseconds(2));
        }

        // 📉 Decrement demand by what we actually produced
        if (written > 0) {
          framesRequested_.fetch_sub(written / channelCount_,
                                     std::memory_order_release);
        }
      }
      AMediaCodec_releaseOutputBuffer(codec_, outIndex, false);
    }
  }
}

/* ===================== Producer (lock-free) ===================== */

bool AudioEngine::writeAudio(const int16_t *data, int32_t samples) {
  if ((samples % channelCount_) != 0) {
    // Robustness: ignore partial frames logic for now
  }

  // Load head relaxed (producer local), tail acquire to observe consumer
  // progress
  int32_t head = writeHead_.load(std::memory_order_relaxed);
  int32_t tail = readHead_.load(std::memory_order_acquire);

  // Compute available space; if insufficient, drop audio (never wait)
  int32_t available = kRingBufferSize - (head - tail);
  if (available < samples) {
    // Drop: do not block
    return false;
  }

  for (int32_t i = 0; i < samples; i++) {
    ringBuffer_[head % kRingBufferSize] = data[i];
    head++;
  }

  // Publish new head (release)
  writeHead_.store(head, std::memory_order_release);
  // Update debug info with relaxed reads
  gAudioDebug.bufferFill.store((writeHead_.load(std::memory_order_relaxed) -
                                readHead_.load(std::memory_order_relaxed)) /
                               channelCount_);
  return true;
}

void AudioEngine::renderAudio(int16_t *out, int32_t samples) {
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
  readHead_.store(0, std::memory_order_release);
  writeHead_.store(0, std::memory_order_release);
}

int32_t AudioEngine::framesToSamples(int32_t frames) const {
  return frames * channelCount_;
}

/* ===================== AAudio Callback ===================== */

// ===================== Audio Callbacks =====================

aaudio_data_callback_result_t AudioEngine::dataCallback(AAudioStream *stream,
                                                        void *userData,
                                                        void *audioData,
                                                        int32_t numFrames) {

  auto *engine = static_cast<AudioEngine *>(userData);
  if (!engine)
    return AAUDIO_CALLBACK_RESULT_STOP;

  gAudioDebug.callbackCalled.store(true);

  int32_t numSamples = engine->framesToSamples(numFrames);

  // 🚨 CLOCK CHECK - Never stop callback, but respect clock state
  if (!engine->virtualClock_->isRunning()) {
    memset(audioData, 0, numSamples * sizeof(int16_t));
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
  }

  // 1️⃣ Signal demand to the producer (decodeLoop)
  engine->framesRequested_.fetch_add(numFrames, std::memory_order_release);

  // 2️⃣ Render audio (pull from ring buffer)
  if (engine->audioOutputEnabled_.load(std::memory_order_acquire)) {
    engine->renderAudio(static_cast<int16_t *>(audioData), numSamples);
  } else {
    // Output gated - write silence
    memset(audioData, 0, numSamples * sizeof(int16_t));
  }

  return AAUDIO_CALLBACK_RESULT_CONTINUE;
}
