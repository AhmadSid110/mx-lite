
#include "ffmpeg_jni.h"

#include <stdlib.h>
#include <string.h>

#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libavutil/time.h>
#include <libavutil/imgutils.h>
#include <libswresample/swresample.h>

typedef struct {
    AVFormatContext *fmt;
    AVCodecContext  *vcodec;
    AVCodecContext  *acodec;
    int video_stream;
    int audio_stream;
    AVPacket *pkt;
    AVFrame  *frame;
    int64_t audio_clock_us;
} PlayerState;

static PlayerState *state = NULL;

// --------------------------------------------------
// OPEN
// --------------------------------------------------
JNIEXPORT jboolean JNICALL
Java_com_mxlite_app_player_FFmpegPlayer_nativeOpen(
        JNIEnv *env, jobject thiz, jstring path_) {

    const char *path = (*env)->GetStringUTFChars(env, path_, 0);

    state = calloc(1, sizeof(PlayerState));
    state->pkt   = av_packet_alloc();
    state->frame = av_frame_alloc();

    if (avformat_open_input(&state->fmt, path, NULL, NULL) < 0)
        return JNI_FALSE;

    avformat_find_stream_info(state->fmt, NULL);

    state->video_stream = -1;
    state->audio_stream = -1;

    for (int i = 0; i < state->fmt->nb_streams; i++) {
        AVCodecParameters *par = state->fmt->streams[i]->codecpar;
        if (par->codec_type == AVMEDIA_TYPE_VIDEO && state->video_stream < 0)
            state->video_stream = i;
        if (par->codec_type == AVMEDIA_TYPE_AUDIO && state->audio_stream < 0)
            state->audio_stream = i;
    }

    AVCodec *vcodec = avcodec_find_decoder(
        state->fmt->streams[state->video_stream]->codecpar->codec_id);

    state->vcodec = avcodec_alloc_context3(vcodec);
    avcodec_parameters_to_context(
        state->vcodec,
        state->fmt->streams[state->video_stream]->codecpar);
    avcodec_open2(state->vcodec, vcodec, NULL);

    AVCodec *acodec = avcodec_find_decoder(
        state->fmt->streams[state->audio_stream]->codecpar->codec_id);

    state->acodec = avcodec_alloc_context3(acodec);
    avcodec_parameters_to_context(
        state->acodec,
        state->fmt->streams[state->audio_stream]->codecpar);
    avcodec_open2(state->acodec, acodec, NULL);

    (*env)->ReleaseStringUTFChars(env, path_, path);
    return JNI_TRUE;
}

// --------------------------------------------------
// READ AUDIO (MASTER CLOCK)
// --------------------------------------------------
JNIEXPORT jobject JNICALL
Java_com_mxlite_app_player_FFmpegPlayer_nativeReadAudioFrame(
        JNIEnv *env, jobject thiz) {

    while (av_read_frame(state->fmt, state->pkt) >= 0) {

        if (state->pkt->stream_index != state->audio_stream) {
            av_packet_unref(state->pkt);
            continue;
        }

        avcodec_send_packet(state->acodec, state->pkt);
        av_packet_unref(state->pkt);

        if (avcodec_receive_frame(state->acodec, state->frame) == 0) {

            int size = av_get_bytes_per_sample(state->acodec->sample_fmt)
                       * state->frame->nb_samples
                       * state->acodec->ch_layout.nb_channels;

            jbyteArray pcm = (*env)->NewByteArray(env, size);
            (*env)->SetByteArrayRegion(
                env, pcm, 0, size,
                (jbyte*)state->frame->data[0]);

            state->audio_clock_us = av_rescale_q(
                state->frame->pts,
                state->fmt->streams[state->audio_stream]->time_base,
                AV_TIME_BASE_Q);

            jclass cls = (*env)->FindClass(env,
                "com/mxlite/app/player/AudioFrame");

            jmethodID ctor = (*env)->GetMethodID(
                env, cls, "<init>", "(III[B)V");

            return (*env)->NewObject(
                env, cls, ctor,
                state->acodec->sample_rate,
                state->acodec->ch_layout.nb_channels,
                (jint)(state->audio_clock_us / 1000),
                pcm);
        }
    }
    return NULL;
}

// --------------------------------------------------
// READ VIDEO (SYNC TO AUDIO)
// --------------------------------------------------
JNIEXPORT jobject JNICALL
Java_com_mxlite_app_player_FFmpegPlayer_nativeReadVideoFrame(
        JNIEnv *env, jobject thiz) {

    while (av_read_frame(state->fmt, state->pkt) >= 0) {

        if (state->pkt->stream_index != state->video_stream) {
            av_packet_unref(state->pkt);
            continue;
        }

        avcodec_send_packet(state->vcodec, state->pkt);
        av_packet_unref(state->pkt);

        if (avcodec_receive_frame(state->vcodec, state->frame) == 0) {

            int64_t pts_us = av_rescale_q(
                state->frame->pts,
                state->fmt->streams[state->video_stream]->time_base,
                AV_TIME_BASE_Q);

            if (pts_us > state->audio_clock_us + 30000)
                return NULL;

            int size = av_image_get_buffer_size(
                AV_PIX_FMT_YUV420P,
                state->frame->width,
                state->frame->height,
                1);

            jbyteArray data = (*env)->NewByteArray(env, size);
            av_image_copy_to_buffer(
                (uint8_t*)(*env)->GetByteArrayElements(env, data, NULL),
                size,
                (const uint8_t * const *)state->frame->data,
                state->frame->linesize,
                AV_PIX_FMT_YUV420P,
                state->frame->width,
                state->frame->height,
                1);

            jclass cls = (*env)->FindClass(env,
                "com/mxlite/app/player/VideoFrame");

            jmethodID ctor = (*env)->GetMethodID(
                env, cls, "<init>", "(III[B)V");

            return (*env)->NewObject(
                env, cls, ctor,
                state->frame->width,
                state->frame->height,
                (jint)(pts_us / 1000),
                data);
        }
    }
    return NULL;
}

// --------------------------------------------------
// CLOSE
// --------------------------------------------------
JNIEXPORT void JNICALL
Java_com_mxlite_app_player_FFmpegPlayer_nativeClose(
        JNIEnv *env, jobject thiz) {

    if (!state) return;

    avcodec_free_context(&state->vcodec);
    avcodec_free_context(&state->acodec);
    avformat_close_input(&state->fmt);
    av_frame_free(&state->frame);
    av_packet_free(&state->pkt);

    free(state);
    state = NULL;
}
