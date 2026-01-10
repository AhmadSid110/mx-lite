#include <jni.h>
#include "player/AudioEngine.h"
#include "player/Clock.h"

static Clock gClock;
static AudioEngine* gAudio = nullptr;

/* ───────────────────────── PLAY ───────────────────────── */

extern "C"
JNIEXPORT void JNICALL
Java_com_mxlite_app_player_NativePlayer_nativePlay(
        JNIEnv* env, jobject /*thiz*/, jstring path) {

    const char* cpath = env->GetStringUTFChars(path, nullptr);

    if (!gAudio) {
        gAudio = new AudioEngine(&gClock);
    }

    if (gAudio->open(cpath)) {
        gAudio->start();
    }

    env->ReleaseStringUTFChars(path, cpath);
}

/* ───────────────────────── CLOCK ───────────────────────── */

extern "C"
JNIEXPORT jlong JNICALL
Java_com_mxlite_app_player_NativePlayer_nativeGetClockUs(
        JNIEnv*, jobject /*thiz*/) {
    return gClock.getUs();
}

/* ───────────────────────── SEEK ───────────────────────── */

extern "C"
JNIEXPORT void JNICALL
Java_com_mxlite_app_player_NativePlayer_nativeSeek(
        JNIEnv*, jobject /*thiz*/, jlong posUs) {

    if (gAudio) {
        gAudio->seekUs((int64_t) posUs);
    }
}

/* ───────────────────────── STOP ───────────────────────── */

extern "C"
JNIEXPORT void JNICALL
Java_com_mxlite_app_player_NativePlayer_nativeStop(
        JNIEnv*, jobject /*thiz*/) {

    if (gAudio) {
        gAudio->stop();
    }
}

/* ───────────────────────── RELEASE ───────────────────────── */

extern "C"
JNIEXPORT void JNICALL
Java_com_mxlite_app_player_NativePlayer_nativeRelease(
        JNIEnv*, jobject /*thiz*/) {

    delete gAudio;
    gAudio = nullptr;
}

/* ───────────────────────── DEBUG API ───────────────────────── */
/* These are READ-ONLY. No side effects. Safe to call from UI.   */

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_mxlite_app_player_NativePlayer_nativeIsAAudioOpened(
        JNIEnv*, jobject /*thiz*/) {

    return (gAudio && gAudio->isAAudioOpened()) ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_mxlite_app_player_NativePlayer_nativeIsAAudioStarted(
        JNIEnv*, jobject /*thiz*/) {

    return (gAudio && gAudio->isAAudioStarted()) ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_mxlite_app_player_NativePlayer_nativeIsAudioCallbackRunning(
        JNIEnv*, jobject /*thiz*/) {

    return (gAudio && gAudio->isCallbackRunning()) ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_mxlite_app_player_NativePlayer_nativeGetAudioCallbackCount(
        JNIEnv*, jobject /*thiz*/) {

    return gAudio ? gAudio->getCallbackCount() : 0;
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_mxlite_app_player_NativePlayer_nativeGetAudioFramesPlayed(
        JNIEnv*, jobject /*thiz*/) {

    return gAudio ? gAudio->getFramesPlayed() : 0;
}