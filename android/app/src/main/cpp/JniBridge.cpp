#include <jni.h>

#include "player/AudioEngine.h"
#include "player/Clock.h"
#include "player/AudioDebug.h"

/*
 * Global singletons
 */
static Clock gClock;
static AudioEngine* gAudio = nullptr;

/*
 * Audio debug state (defined in AudioDebug.cpp)
 */
extern AudioDebug gAudioDebug;

/* ───────────────────────────── */
/* Playback control JNI */
/* ───────────────────────────── */

extern "C"
JNIEXPORT void JNICALL
Java_com_mxlite_app_player_NativePlayer_nativePlay(
        JNIEnv* env,
        jobject /*thiz*/,
        jstring path) {

    gAudioDebug.nativePlayCalled.store(true); // ✅ ADD THIS LINE

    const char* cpath = env->GetStringUTFChars(path, nullptr);

    if (!gAudio) {
        gAudio = new AudioEngine(&gClock);
    }

    if (gAudio->open(cpath)) {
        gAudio->start();
    }

    env->ReleaseStringUTFChars(path, cpath);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_mxlite_app_player_NativePlayer_nativeStop(
        JNIEnv*,
        jobject) {

    if (gAudio) {
        gAudio->stop();
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_mxlite_app_player_NativePlayer_nativeSeek(
        JNIEnv*,
        jobject,
        jlong posUs) {

    if (gAudio) {
        gAudio->seekUs((int64_t) posUs);
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_mxlite_app_player_NativePlayer_nativeRelease(
        JNIEnv*,
        jobject) {

    delete gAudio;
    gAudio = nullptr;
}

/* ───────────────────────────── */
/* Clock JNI */
/* ───────────────────────────── */

extern "C"
JNIEXPORT jlong JNICALL
Java_com_mxlite_app_player_NativePlayer_nativeGetClockUs(
        JNIEnv*,
        jobject) {

    return gClock.getUs();
}

/* ───────────────────────────── */
/* 🔍 DEBUG / DIAGNOSTIC JNI */
/* ───────────────────────────── */

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_mxlite_app_player_NativePlayer_dbgEngineCreated(
        JNIEnv*,
        jobject) {

    return gAudioDebug.engineCreated.load() ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_mxlite_app_player_NativePlayer_dbgAAudioOpened(
        JNIEnv*,
        jobject) {

    return gAudioDebug.aaudioOpened.load() ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_mxlite_app_player_NativePlayer_dbgAAudioStarted(
        JNIEnv*,
        jobject) {

    return gAudioDebug.aaudioStarted.load() ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_mxlite_app_player_NativePlayer_dbgCallbackCalled(
        JNIEnv*,
        jobject) {

    return gAudioDebug.callbackCalled.load() ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_mxlite_app_player_NativePlayer_dbgDecoderProduced(
        JNIEnv*,
        jobject) {

    return gAudioDebug.decoderProduced.load() ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_mxlite_app_player_NativePlayer_dbgBufferFill(
        JNIEnv*,
        jobject) {

    return gAudioDebug.bufferFill.load();
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_mxlite_app_player_NativePlayer_dbgNativePlayCalled(
        JNIEnv*,
        jobject) {

    return gAudioDebug.nativePlayCalled.load() ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_mxlite_app_player_NativePlayer_dbgAAudioError(
        JNIEnv*, jobject) {
    return gAudioDebug.aaudioError.load();
}