#include "AudioEngine.h"
#include <android/log.h>
#include <cstring>
#include <unistd.h>
#include <algorithm>

#define LOG_TAG "AudioEngine"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static SLuint32 toSlSampleRate(int sr) {
    switch (sr) {
        case 8000:  return SL_SAMPLINGRATE_8;
        case 16000: return SL_SAMPLINGRATE_16;
        case 22050: return SL_SAMPLINGRATE_22_05;
        case 32000: return SL_SAMPLINGRATE_32;
        case 44100: return SL_SAMPLINGRATE_44_1;
        case 48000: return SL_SAMPLINGRATE_48;
        default:    return SL_SAMPLINGRATE_44_1;
    }
}

AudioEngine::AudioEngine(Clock* clock)
        : clock_(clock) {}

AudioEngine::~AudioEngine() {
    stop();
    cleanupCodec();
    cleanupOpenSL();
}

bool AudioEngine::open(const char* path) {
    extractor_ = AMediaExtractor_new();
    if (!extractor_) return false;
    if (AMediaExtractor_setDataSource(extractor_, path) != AMEDIA_OK)
        return false;

    int track = -1;
    for (size_t i = 0; i < AMediaExtractor_getTrackCount(extractor_); i++) {
        AMediaFormat* fmt = AMediaExtractor_getTrackFormat(extractor_, i);
        const char* mime = nullptr;
        AMediaFormat_getString(fmt, AMEDIAFORMAT_KEY_MIME, &mime);
        if (mime && !strncmp(mime, "audio/", 6)) {
            format_ = fmt;
            track = i;
            break;
        }
        AMediaFormat_delete(fmt);
    }
    if (track < 0) return false;

    AMediaExtractor_selectTrack(extractor_, track);
    AMediaFormat_getInt32(format_, AMEDIAFORMAT_KEY_SAMPLE_RATE, &sampleRate_);
    AMediaFormat_getInt32(format_, AMEDIAFORMAT_KEY_CHANNEL_COUNT, &channelCount_);

    // Detect PCM encoding
    pcmEncoding_ = AMEDIAFORMAT_PCM_ENCODING_PCM_16BIT;
    AMediaFormat_getInt32(
        format_, AMEDIAFORMAT_KEY_PCM_ENCODING, &pcmEncoding_
    );

    const char* mime = nullptr;
    AMediaFormat_getString(format_, AMEDIAFORMAT_KEY_MIME, &mime);
    codec_ = AMediaCodec_createDecoderByType(mime);
    if (!codec_) return false;

    if (AMediaCodec_configure(codec_, format_, nullptr, nullptr, 0) != AMEDIA_OK)
        return false;

    if (!setupOpenSL()) return false;
    return AMediaCodec_start(codec_) == AMEDIA_OK;
}

bool AudioEngine::setupOpenSL() {
    slCreateEngine(&engineObj_, 0, nullptr, 0, nullptr, nullptr);
    (*engineObj_)->Realize(engineObj_, SL_BOOLEAN_FALSE);
    (*engineObj_)->GetInterface(engineObj_, SL_IID_ENGINE, &engine_);

    (*engine_)->CreateOutputMix(engine_, &outputMix_, 0, nullptr, nullptr);
    (*outputMix_)->Realize(outputMix_, SL_BOOLEAN_FALSE);

    SLDataLocator_AndroidSimpleBufferQueue locBufQ =
        {SL_DATALOCATOR_ANDROIDSIMPLEBUFFERQUEUE, 2};

    SLDataFormat_PCM pcm = {
        SL_DATAFORMAT_PCM,
        (SLuint32)channelCount_,
        toSlSampleRate(sampleRate_),
        SL_PCMSAMPLEFORMAT_FIXED_16,
        SL_PCMSAMPLEFORMAT_FIXED_16,
        channelCount_ == 1
            ? SL_SPEAKER_FRONT_CENTER
            : SL_SPEAKER_FRONT_LEFT | SL_SPEAKER_FRONT_RIGHT,
        SL_BYTEORDER_LITTLEENDIAN
    };

    SLDataSource src = {&locBufQ, &pcm};
    SLDataLocator_OutputMix outMix =
        {SL_DATALOCATOR_OUTPUTMIX, outputMix_};
    SLDataSink sink = {&outMix, nullptr};

    const SLInterfaceID ids[] = {SL_IID_BUFFERQUEUE};
    const SLboolean req[] = {SL_BOOLEAN_TRUE};

    (*engine_)->CreateAudioPlayer(
        engine_, &playerObj_, &src, &sink, 1, ids, req
    );
    (*playerObj_)->Realize(playerObj_, SL_BOOLEAN_FALSE);
    (*playerObj_)->GetInterface(playerObj_, SL_IID_PLAY, &player_);
    (*playerObj_)->GetInterface(playerObj_, SL_IID_BUFFERQUEUE, &bufferQueue_);
    (*bufferQueue_)->RegisterCallback(
        bufferQueue_, bufferQueueCallback, this
    );

    buffersAvailable_ = 2;
    return true;
}

void AudioEngine::start() {
    running_ = true;
    clock_->setUs(0);
    (*player_)->SetPlayState(player_, SL_PLAYSTATE_PLAYING);
    decodeThread_ = std::thread(&AudioEngine::decodeLoop, this);
}

void AudioEngine::stop() {
    running_ = false;
    if (decodeThread_.joinable())
        decodeThread_.join();
}

void AudioEngine::seekUs(int64_t us) {
    if (!extractor_ || !codec_) return;
    AMediaExtractor_seekTo(
        extractor_, us, AMEDIAEXTRACTOR_SEEK_CLOSEST_SYNC
    );
    AMediaCodec_flush(codec_);
    clock_->setUs(us);
}

void AudioEngine::decodeLoop() {
    AMediaCodecBufferInfo info;
    std::vector<int16_t> pcm16;

    while (running_) {
        ssize_t in = AMediaCodec_dequeueInputBuffer(codec_, 10000);
        if (in >= 0) {
            size_t cap;
            uint8_t* buf = AMediaCodec_getInputBuffer(codec_, in, &cap);
            ssize_t sz = AMediaExtractor_readSampleData(extractor_, buf, cap);
            if (sz < 0) {
                AMediaCodec_queueInputBuffer(
                    codec_, in, 0, 0, 0,
                    AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM
                );
            } else {
                int64_t pts = AMediaExtractor_getSampleTime(extractor_);
                AMediaCodec_queueInputBuffer(codec_, in, 0, sz, pts, 0);
                AMediaExtractor_advance(extractor_);
            }
        }

        ssize_t out = AMediaCodec_dequeueOutputBuffer(codec_, &info, 10000);
        if (out >= 0 && info.size > 0) {
            while (buffersAvailable_ == 0 && running_)
                usleep(1000);

            buffersAvailable_--;

            size_t cap;
            uint8_t* buf = AMediaCodec_getOutputBuffer(codec_, out, &cap);

            int frames;
            if (pcmEncoding_ == AMEDIAFORMAT_PCM_ENCODING_PCM_FLOAT) {
                int samples = info.size / sizeof(float);
                pcm16.resize(samples);
                float* f = (float*)(buf + info.offset);
                for (int i = 0; i < samples; i++) {
                    float v = std::max(-1.0f, std::min(1.0f, f[i]));
                    pcm16[i] = (int16_t)(v * 32767.0f);
                }
                (*bufferQueue_)->Enqueue(
                    bufferQueue_,
                    pcm16.data(),
                    pcm16.size() * sizeof(int16_t)
                );
                frames = samples / channelCount_;
            } else {
                (*bufferQueue_)->Enqueue(
                    bufferQueue_,
                    buf + info.offset,
                    info.size
                );
                frames = info.size / (2 * channelCount_);
            }

            int64_t deltaUs =
                (int64_t)frames * 1000000LL / sampleRate_;
            clock_->setUs(clock_->getUs() + deltaUs);

            AMediaCodec_releaseOutputBuffer(codec_, out, false);
        }
    }
}

void AudioEngine::bufferQueueCallback(
    SLAndroidSimpleBufferQueueItf, void* ctx
) {
    auto* self = static_cast<AudioEngine*>(ctx);
    self->buffersAvailable_++;
}

void AudioEngine::cleanupCodec() {
    if (codec_) AMediaCodec_delete(codec_);
    if (format_) AMediaFormat_delete(format_);
    if (extractor_) AMediaExtractor_delete(extractor_);
}

void AudioEngine::cleanupOpenSL() {
    if (playerObj_) (*playerObj_)->Destroy(playerObj_);
    if (outputMix_) (*outputMix_)->Destroy(outputMix_);
    if (engineObj_) (*engineObj_)->Destroy(engineObj_);
}