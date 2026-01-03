
#include "ffmpeg_jni.h"

JNIEXPORT jboolean JNICALL
Java_com_mxlite_app_player_FFmpegPlayer_nativeOpen(
        JNIEnv* env, jobject thiz, jstring path) {
    return JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_com_mxlite_app_player_FFmpegPlayer_nativeGetDurationMs(
        JNIEnv* env, jobject thiz) {
    return 0;
}

JNIEXPORT jobject JNICALL
Java_com_mxlite_app_player_FFmpegPlayer_nativeReadVideoFrame(
        JNIEnv* env, jobject thiz) {
    return NULL;
}

JNIEXPORT jobject JNICALL
Java_com_mxlite_app_player_FFmpegPlayer_nativeReadAudioFrame(
        JNIEnv* env, jobject thiz) {
    return NULL;
}

JNIEXPORT void JNICALL
Java_com_mxlite_app_player_FFmpegPlayer_nativeSeekTo(
        JNIEnv* env, jobject thiz, jlong ms) {
}

JNIEXPORT void JNICALL
Java_com_mxlite_app_player_FFmpegPlayer_nativeClose(
        JNIEnv* env, jobject thiz) {
}
