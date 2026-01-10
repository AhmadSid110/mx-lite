#include <jni.h>
#include "player/AudioEngine.h"
#include "player/Clock.h"

static Clock gClock;
static AudioEngine* gAudio = nullptr;

extern "C"
JNIEXPORT void JNICALL
Java_com_mxlite_app_player_NativePlayer_nativePlay(
        JNIEnv* env, jobject, jstring path) {

    const char* cpath = env->GetStringUTFChars(path, nullptr);

    delete gAudio;            // hard reset
    gAudio = new AudioEngine(&gClock);

    gAudio->open(cpath);      // 🔑 audio STARTS HERE

    env->ReleaseStringUTFChars(path, cpath);
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_mxlite_app_player_NativePlayer_nativeGetClockUs(
        JNIEnv*, jobject) {
    return gClock.getUs();
}

extern "C"
JNIEXPORT void JNICALL
Java_com_mxlite_app_player_NativePlayer_nativeStop(
        JNIEnv*, jobject) {
    delete gAudio;
    gAudio = nullptr;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_mxlite_app_player_NativePlayer_nativeRelease(
        JNIEnv*, jobject) {
    delete gAudio;
    gAudio = nullptr;
}