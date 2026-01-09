#include "AudioEngine.h"
#include <android/log.h>
#include <cstring>
#include <vector>

#define LOG_TAG "AudioEngine"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Buffer queue for PCM samples
static std::vector<uint8_t> gPcmBuffer;
static const size_t BUFFER_SIZE = 8192;

AudioEngine::AudioEngine(Clock* clock) : clock_(clock) {
    LOGD("AudioEngine created");
}

AudioEngine::~AudioEngine() {
    stop();
    cleanupCodec();
    cleanupOpenSL();
}

bool AudioEngine::open(const char* path) {
    LOGD("Opening audio: %s", path);
    
    // Create extractor
    extractor_ = AMediaExtractor_new();
    if (!extractor_) {
        LOGE("Failed to create extractor");
        return false;
    }
    
    media_status_t status = AMediaExtractor_setDataSource(extractor_, path);
    if (status != AMEDIA_OK) {
        LOGE("Failed to set data source: %d", status);
        AMediaExtractor_delete(extractor_);
        extractor_ = nullptr;
        return false;
    }
    
    // Find audio track
    size_t numTracks = AMediaExtractor_getTrackCount(extractor_);
    int audioTrackIndex = -1;
    
    for (size_t i = 0; i < numTracks; i++) {
        AMediaFormat* format = AMediaExtractor_getTrackFormat(extractor_, i);
        const char* mime = nullptr;
        AMediaFormat_getString(format, AMEDIAFORMAT_KEY_MIME, &mime);
        
        if (mime && strncmp(mime, "audio/", 6) == 0) {
            audioTrackIndex = i;
            format_ = format;
            LOGD("Found audio track %d: %s", i, mime);
            break;
        } else {
            AMediaFormat_delete(format);
        }
    }
    
    if (audioTrackIndex < 0) {
        LOGE("No audio track found");
        AMediaExtractor_delete(extractor_);
        extractor_ = nullptr;
        return false;
    }
    
    AMediaExtractor_selectTrack(extractor_, audioTrackIndex);
    
    // Create codec
    const char* mime = nullptr;
    AMediaFormat_getString(format_, AMEDIAFORMAT_KEY_MIME, &mime);
    codec_ = AMediaCodec_createDecoderByType(mime);
    
    if (!codec_) {
        LOGE("Failed to create codec");
        return false;
    }
    
    status = AMediaCodec_configure(codec_, format_, nullptr, nullptr, 0);
    if (status != AMEDIA_OK) {
        LOGE("Failed to configure codec: %d", status);
        return false;
    }
    
    // Setup OpenSL ES
    if (!setupOpenSL()) {
        LOGE("Failed to setup OpenSL ES");
        return false;
    }
    
    status = AMediaCodec_start(codec_);
    if (status != AMEDIA_OK) {
        LOGE("Failed to start codec: %d", status);
        return false;
    }
    
    LOGD("Audio opened successfully");
    return true;
}

bool AudioEngine::setupOpenSL() {
    SLresult result;
    
    // Create engine
    result = slCreateEngine(&engineObj_, 0, nullptr, 0, nullptr, nullptr);
    if (result != SL_RESULT_SUCCESS) {
        LOGE("slCreateEngine failed: %d", result);
        return false;
    }
    
    result = (*engineObj_)->Realize(engineObj_, SL_BOOLEAN_FALSE);
    if (result != SL_RESULT_SUCCESS) {
        LOGE("Engine Realize failed: %d", result);
        return false;
    }
    
    result = (*engineObj_)->GetInterface(engineObj_, SL_IID_ENGINE, &engine_);
    if (result != SL_RESULT_SUCCESS) {
        LOGE("GetInterface SL_IID_ENGINE failed: %d", result);
        return false;
    }
    
    // Create output mix
    result = (*engine_)->CreateOutputMix(engine_, &outputMix_, 0, nullptr, nullptr);
    if (result != SL_RESULT_SUCCESS) {
        LOGE("CreateOutputMix failed: %d", result);
        return false;
    }
    
    result = (*outputMix_)->Realize(outputMix_, SL_BOOLEAN_FALSE);
    if (result != SL_RESULT_SUCCESS) {
        LOGE("OutputMix Realize failed: %d", result);
        return false;
    }
    
    // Get audio format from codec
    int32_t sampleRate = 44100;
    int32_t channelCount = 2;
    
    AMediaFormat_getInt32(format_, AMEDIAFORMAT_KEY_SAMPLE_RATE, &sampleRate);
    AMediaFormat_getInt32(format_, AMEDIAFORMAT_KEY_CHANNEL_COUNT, &channelCount);
    
    LOGD("Audio format: %d Hz, %d channels", sampleRate, channelCount);
    
    // Configure audio source
    SLDataLocator_AndroidSimpleBufferQueue loc_bufq = {
        SL_DATALOCATOR_ANDROIDSIMPLEBUFFERQUEUE,
        2  // 2 buffers
    };
    
    SLDataFormat_PCM format_pcm = {
        SL_DATAFORMAT_PCM,
        static_cast<SLuint32>(channelCount),
        static_cast<SLuint32>(sampleRate * 1000),  // milliHz
        SL_PCMSAMPLEFORMAT_FIXED_16,
        SL_PCMSAMPLEFORMAT_FIXED_16,
        channelCount == 1 ? SL_SPEAKER_FRONT_CENTER : 
                           (SL_SPEAKER_FRONT_LEFT | SL_SPEAKER_FRONT_RIGHT),
        SL_BYTEORDER_LITTLEENDIAN
    };
    
    SLDataSource audioSrc = {&loc_bufq, &format_pcm};
    
    // Configure audio sink
    SLDataLocator_OutputMix loc_outmix = {
        SL_DATALOCATOR_OUTPUTMIX,
        outputMix_
    };
    SLDataSink audioSnk = {&loc_outmix, nullptr};
    
    // Create audio player
    const SLInterfaceID ids[1] = {SL_IID_BUFFERQUEUE};
    const SLboolean req[1] = {SL_BOOLEAN_TRUE};
    
    result = (*engine_)->CreateAudioPlayer(engine_, &playerObj_, &audioSrc, &audioSnk,
                                           1, ids, req);
    if (result != SL_RESULT_SUCCESS) {
        LOGE("CreateAudioPlayer failed: %d", result);
        return false;
    }
    
    result = (*playerObj_)->Realize(playerObj_, SL_BOOLEAN_FALSE);
    if (result != SL_RESULT_SUCCESS) {
        LOGE("AudioPlayer Realize failed: %d", result);
        return false;
    }
    
    // Get play interface
    result = (*playerObj_)->GetInterface(playerObj_, SL_IID_PLAY, &player_);
    if (result != SL_RESULT_SUCCESS) {
        LOGE("GetInterface SL_IID_PLAY failed: %d", result);
        return false;
    }
    
    // Get buffer queue interface
    result = (*playerObj_)->GetInterface(playerObj_, SL_IID_BUFFERQUEUE, &bufferQueue_);
    if (result != SL_RESULT_SUCCESS) {
        LOGE("GetInterface SL_IID_BUFFERQUEUE failed: %d", result);
        return false;
    }
    
    // Register callback
    result = (*bufferQueue_)->RegisterCallback(bufferQueue_, bufferQueueCallback, this);
    if (result != SL_RESULT_SUCCESS) {
        LOGE("RegisterCallback failed: %d", result);
        return false;
    }
    
    LOGD("OpenSL ES setup complete");
    return true;
}

void AudioEngine::start() {
    LOGD("Starting audio playback");
    
    if (player_) {
        (*player_)->SetPlayState(player_, SL_PLAYSTATE_PLAYING);
    }
    
    running_ = true;
    lastPtsUs_ = 0;
    clock_->setUs(0);
    
    decodeThread_ = std::thread(&AudioEngine::decodeLoop, this);
}

void AudioEngine::decodeLoop() {
    LOGD("Decode loop started");
    
    AMediaCodecBufferInfo info;
    bool sawInputEOS = false;
    bool sawOutputEOS = false;
    
    gPcmBuffer.resize(BUFFER_SIZE);
    
    while (running_ && !sawOutputEOS) {
        // Feed input
        if (!sawInputEOS) {
            ssize_t bufferIndex = AMediaCodec_dequeueInputBuffer(codec_, 10000);
            if (bufferIndex >= 0) {
                size_t bufferSize;
                uint8_t* buffer = AMediaCodec_getInputBuffer(codec_, bufferIndex, &bufferSize);
                
                ssize_t sampleSize = AMediaExtractor_readSampleData(extractor_, buffer, bufferSize);
                
                if (sampleSize < 0) {
                    LOGD("Input EOS");
                    AMediaCodec_queueInputBuffer(codec_, bufferIndex, 0, 0, 0, 
                                                 AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
                    sawInputEOS = true;
                } else {
                    int64_t presentationTimeUs = AMediaExtractor_getSampleTime(extractor_);
                    AMediaCodec_queueInputBuffer(codec_, bufferIndex, 0, sampleSize,
                                                 presentationTimeUs, 0);
                    AMediaExtractor_advance(extractor_);
                }
            }
        }
        
        // Get output
        ssize_t bufferIndex = AMediaCodec_dequeueOutputBuffer(codec_, &info, 10000);
        
        if (bufferIndex >= 0) {
            size_t bufferSize;
            uint8_t* buffer = AMediaCodec_getOutputBuffer(codec_, bufferIndex, &bufferSize);
            
            if (info.size > 0 && buffer) {
                // Update master clock BEFORE enqueueing PCM
                lastPtsUs_ = info.presentationTimeUs;
                
                // Copy PCM data and enqueue to OpenSL ES
                size_t copySize = std::min(static_cast<size_t>(info.size), gPcmBuffer.size());
                memcpy(gPcmBuffer.data(), buffer + info.offset, copySize);
                
                if (bufferQueue_) {
                    SLresult result = (*bufferQueue_)->Enqueue(bufferQueue_, 
                                                               gPcmBuffer.data(), copySize);
                    if (result == SL_RESULT_SUCCESS) {
                        // Clock is updated AFTER successful enqueue
                        clock_->setUs(lastPtsUs_);
                    }
                }
            }
            
            AMediaCodec_releaseOutputBuffer(codec_, bufferIndex, false);
            
            if (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) {
                LOGD("Output EOS");
                sawOutputEOS = true;
            }
        } else if (bufferIndex == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
            LOGD("Output format changed");
        }
    }
    
    LOGD("Decode loop finished");
}

void AudioEngine::bufferQueueCallback(SLAndroidSimpleBufferQueueItf bq, void* context) {
    // Callback when buffer is consumed - could be used for flow control
    // Currently we handle everything in decode loop
}

void AudioEngine::onBufferComplete() {
    // Buffer consumption notification
}

void AudioEngine::stop() {
    LOGD("Stopping audio");
    running_ = false;
    
    if (decodeThread_.joinable()) {
        decodeThread_.join();
    }
    
    if (player_) {
        (*player_)->SetPlayState(player_, SL_PLAYSTATE_STOPPED);
    }
}

void AudioEngine::seekUs(int64_t us) {
    LOGD("Seeking to %lld us", (long long)us);
    
    if (extractor_) {
        AMediaExtractor_seekTo(extractor_, us, AMEDIAEXTRACTOR_SEEK_CLOSEST_SYNC);
    }
    
    if (codec_) {
        AMediaCodec_flush(codec_);
    }
    
    lastPtsUs_ = us;
    clock_->setUs(us);
}

void AudioEngine::cleanupCodec() {
    if (codec_) {
        AMediaCodec_stop(codec_);
        AMediaCodec_delete(codec_);
        codec_ = nullptr;
    }
    
    if (format_) {
        AMediaFormat_delete(format_);
        format_ = nullptr;
    }
    
    if (extractor_) {
        AMediaExtractor_delete(extractor_);
        extractor_ = nullptr;
    }
}

void AudioEngine::cleanupOpenSL() {
    if (playerObj_) {
        (*playerObj_)->Destroy(playerObj_);
        playerObj_ = nullptr;
        player_ = nullptr;
        bufferQueue_ = nullptr;
    }
    
    if (outputMix_) {
        (*outputMix_)->Destroy(outputMix_);
        outputMix_ = nullptr;
    }
    
    if (engineObj_) {
        (*engineObj_)->Destroy(engineObj_);
        engineObj_ = nullptr;
        engine_ = nullptr;
    }
}
