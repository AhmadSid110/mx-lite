
#ifndef MXLITE_FFMPEG_JNI_H
#define MXLITE_FFMPEG_JNI_H

#include <jni.h>

#ifdef __cplusplus
extern "C" {
#endif

JNIEXPORT jboolean JNICALL
Java_com_mxlite_app_player_FFmpegPlayer_nativeOpen(
        JNIEnv*, jobject, jstring);

JNIEXPORT jlong JNICALL
Java_com_mxlite_app_player_FFmpegPlayer_nativeGetDurationMs(
        JNIEnv*, jobject);

JNIEXPORT jobject JNICALL
Java_com_mxlite_app_player_FFmpegPlayer_nativeReadVideoFrame(
        JNIEnv*, jobject);

JNIEXPORT jobject JNICALL
Java_com_mxlite_app_player_FFmpegPlayer_nativeReadAudioFrame(
        JNIEnv*, jobject);

JNIEXPORT void JNICALL
Java_com_mxlite_app_player_FFmpegPlayer_nativeSeekTo(
        JNIEnv*, jobject, jlong);

JNIEXPORT void JNICALL
Java_com_mxlite_app_player_FFmpegPlayer_nativeClose(
        JNIEnv*, jobject);

#ifdef __cplusplus
}
#endif

#endif
