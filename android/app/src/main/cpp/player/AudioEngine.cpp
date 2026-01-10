#include "AudioEngine.h"
#include "AudioDebug.h"
#include <thread>
#include <chrono>

extern AudioDebug gAudioDebug;

AudioEngine::AudioEngine(Clock* clock) : clock_(clock) {
    gAudioDebug.engineCreated.store(true);
}

AudioEngine::~AudioEngine() {
    stop();
    cleanupAAudio();
}

bool AudioEngine::open(const char* path) {
    // Setup AAudio stream
    if (!setupAAudio()) {
        return false;
    }
    return true;
}

void AudioEngine::start() {
    if (stream_) {
        AAudioStream_requestStart(stream_);
        
        // Check if stream actually started
        aaudio_stream_state_t state = AAudioStream_getState(stream_);
        if (state == AAUDIO_STREAM_STATE_STARTED) {
            gAudioDebug.aaudioStarted.store(true);
        }
    }
}

void AudioEngine::stop() {
    running_.store(false);
    if (decodeThread_.joinable()) {
        decodeThread_.join();
    }
    
    if (stream_) {
        AAudioStream_requestStop(stream_);
    }
}

void AudioEngine::seekUs(int64_t us) {
    if (clock_) {
        clock_->setUs(us);
    }
}

bool AudioEngine::setupAAudio() {
    AAudioStreamBuilder* builder = nullptr;
    aaudio_result_t result = AAudio_createStreamBuilder(&builder);
    
    if (result != AAUDIO_OK) {
        return false;
    }
    
    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_EXCLUSIVE);
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setChannelCount(builder, 2);
    AAudioStreamBuilder_setSampleRate(builder, 48000);
    AAudioStreamBuilder_setDataCallback(builder, audioCallback, this);
    
    result = AAudioStreamBuilder_openStream(builder, &stream_);
    AAudioStreamBuilder_delete(builder);
    
    if (result != AAUDIO_OK) {
        return false;
    }
    
    sampleRate_ = AAudioStream_getSampleRate(stream_);
    channelCount_ = AAudioStream_getChannelCount(stream_);
    
    // Allocate ring buffer (1 second capacity)
    ringFrames_ = sampleRate_;
    ring_.resize(ringFrames_ * channelCount_);
    
    gAudioDebug.aaudioOpened.store(true);
    return true;
}

void AudioEngine::cleanupAAudio() {
    if (stream_) {
        AAudioStream_close(stream_);
        stream_ = nullptr;
    }
}

aaudio_data_callback_result_t AudioEngine::audioCallback(
        AAudioStream* stream,
        void* userData,
        void* audioData,
        int32_t numFrames) {
    
    auto* engine = static_cast<AudioEngine*>(userData);
    
    // Mark callback as called
    if (!gAudioDebug.callbackCalled.load()) {
        gAudioDebug.callbackCalled.store(true);
    }
    gAudioDebug.callbackCount.fetch_add(1);
    
    // Read from ring buffer
    auto* out = static_cast<int16_t*>(audioData);
    int framesRead = engine->readPcm(out, numFrames);
    
    // Zero fill if underrun
    if (framesRead < numFrames) {
        int samplesRemaining = (numFrames - framesRead) * engine->channelCount_;
        std::fill_n(out + framesRead * engine->channelCount_, samplesRemaining, 0);
    }
    
    // Update clock
    if (engine->clock_ && framesRead > 0) {
        int64_t deltaUs = (framesRead * 1000000LL) / engine->sampleRate_;
        engine->clock_->addUs(deltaUs);
    }
    
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

int AudioEngine::readPcm(int16_t* out, int frames) {
    int64_t read = readPos_.load();
    int64_t write = writePos_.load();
    int64_t available = write - read;
    
    if (available <= 0) {
        return 0;
    }
    
    int framesToRead = std::min<int64_t>(frames, available);
    int samples = framesToRead * channelCount_;
    
    for (int i = 0; i < samples; ++i) {
        int64_t idx = (read * channelCount_ + i) % ring_.size();
        out[i] = ring_[idx];
    }
    
    readPos_.store(read + framesToRead);
    
    // Update buffer fill debug info
    int64_t newAvailable = writePos_.load() - readPos_.load();
    gAudioDebug.bufferFill.store(newAvailable);
    
    return framesToRead;
}

void AudioEngine::writePcmBlocking(const int16_t* in, int frames) {
    int samples = frames * channelCount_;
    
    for (int i = 0; i < samples; ++i) {
        int64_t write = writePos_.load();
        int64_t read = readPos_.load();
        
        // Wait if buffer is full
        while ((write - read) >= ringFrames_) {
            std::this_thread::sleep_for(std::chrono::milliseconds(1));
            read = readPos_.load();
        }
        
        int64_t idx = (write * channelCount_ + (i % channelCount_)) % ring_.size();
        ring_[idx] = in[i];
        
        if ((i + 1) % channelCount_ == 0) {
            writePos_.fetch_add(1);
        }
    }
    
    // Update buffer fill
    int64_t available = writePos_.load() - readPos_.load();
    gAudioDebug.bufferFill.store(available);
}

void AudioEngine::decodeLoop() {
    // Placeholder for decode loop implementation
    // This would extract and decode audio data
    while (running_.load()) {
        std::this_thread::sleep_for(std::chrono::milliseconds(10));
    }
}