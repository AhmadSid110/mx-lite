#include <jni.h>
#include <android/log.h>
#include "player/AudioEngine.h"
#include "player/Clock.h"

#define LOG_TAG "JniBridge"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

static Clock gClock;
static AudioEngine* gAudio = nullptr;

extern "C"
JNIEXPORT void JNICALL
Java_com_mxlite_app_player_NativePlayer_nativePlay(
        JNIEnv* env, jobject, jstring path) {

    const char* cpath = env->GetStringUTFChars(path, nullptr);
    LOGD("nativePlay: %s", cpath);

    if (!gAudio) {
        gAudio = new AudioEngine(&gClock);
    }

    if (gAudio->open(cpath)) {
        gAudio->start();
    }

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
    LOGD("nativeStop");
    if (gAudio) {
        gAudio->stop();
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_mxlite_app_player_NativePlayer_nativeSeek(
        JNIEnv*, jobject, jlong positionUs) {
    LOGD("nativeSeek: %lld", (long long)positionUs);
    if (gAudio) {
        gAudio->seekUs(positionUs);
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_mxlite_app_player_NativePlayer_nativeRelease(
        JNIEnv*, jobject) {
    LOGD("nativeRelease");
    if (gAudio) {
        delete gAudio;
        gAudio = nullptr;
    }
}
