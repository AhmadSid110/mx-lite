#include <jni.h>
#include "player/AudioEngine.h"
#include "player/Clock.h"

static Clock gClock;
static AudioEngine* gAudio = nullptr;

extern "C"
JNIEXPORT void JNICALL
Java_com_mxlite_app_player_NativePlayer_nativePlay(
        JNIEnv* env, jobject, jstring path) {

    const char* p = env->GetStringUTFChars(path, nullptr);

    if (!gAudio)
        gAudio = new AudioEngine(&gClock);

    if (gAudio->open(p))
        gAudio->start();

    env->ReleaseStringUTFChars(path, p);
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_mxlite_app_player_NativePlayer_nativeGetClockUs(
        JNIEnv*, jobject) {
    return gClock.getUs();
}

extern "C"
JNIEXPORT void JNICALL
Java_com_mxlite_app_player_NativePlayer_nativeSeek(
        JNIEnv*, jobject, jlong us) {
    if (gAudio)
        gAudio->seekUs(us);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_mxlite_app_player_NativePlayer_nativeStop(
        JNIEnv*, jobject) {
    if (gAudio)
        gAudio->stop();
}

extern "C"
JNIEXPORT void JNICALL
Java_com_mxlite_app_player_NativePlayer_nativeRelease(
        JNIEnv*, jobject) {
    delete gAudio;
    gAudio = nullptr;
}