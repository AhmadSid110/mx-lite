#include <android/log.h>
#include <jni.h>

#define LOGE(tag, fmt, ...)                                                    \
  __android_log_print(ANDROID_LOG_ERROR, tag, fmt, ##__VA_ARGS__)

#include "player/AudioDebug.h"
#include "player/AudioEngine.h"
#include "player/VirtualClock.h"
#include <aaudio/AAudio.h>
#include <atomic>

/*
 * Global singletons
 */
static VirtualClock gVirtualClock;
static AudioEngine *gAudio = nullptr;

/*
 * Audio debug state (defined in AudioDebug.cpp)
 */
extern AudioDebug gAudioDebug;
extern std::atomic<bool> gAudioHealthy;

/* ───────────────────────────── */
/* Playback control JNI */
/* ───────────────────────────── */

extern "C" JNIEXPORT void JNICALL
Java_com_mxlite_app_player_NativePlayer_nativePlay(JNIEnv *env,
                                                   jobject /*thiz*/,
                                                   jstring path) {

  gAudioDebug.nativePlayCalled.store(true);

  const char *cpath = env->GetStringUTFChars(path, nullptr);

  if (!gAudio) {
    gAudio = new AudioEngine(&gVirtualClock);
  }

  if (gAudio->open(cpath)) {
    gAudio->start();
    // 🔴 FIX #1: Mandatory clock start
    if (!gVirtualClock.isRunning()) {
      gVirtualClock.start();
    }
  }

  env->ReleaseStringUTFChars(path, cpath);
}

extern "C" JNIEXPORT void JNICALL
Java_com_mxlite_app_player_NativePlayer_nativePlayFd(JNIEnv *, jobject, jint fd,
                                                     jlong offset,
                                                     jlong length) {

  if (!gAudio) {
    gAudio = new AudioEngine(&gVirtualClock);
  }

  if (gAudio->openFd(fd, offset, length)) {
    gAudio->start();
    // 🔴 FIX #1: Mandatory clock start
    if (!gVirtualClock.isRunning()) {
      gVirtualClock.start();
    }
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_mxlite_app_player_NativePlayer_nativeStop(JNIEnv *, jobject) {

  if (gAudio) {
    gAudio->stop();
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_mxlite_app_player_NativePlayer_nativeSeek(JNIEnv *, jobject,
                                                   jlong posUs) {

  if (gAudio) {
    gAudio->seekUs((int64_t)posUs);
  } else {
    gVirtualClock.seekUs((int64_t)posUs);
  }
  // 🔴 FIX #3: Resume clock after seek
  gVirtualClock.resume();
}

extern "C" JNIEXPORT void JNICALL
Java_com_mxlite_app_player_NativePlayer_nativeRelease(JNIEnv *, jobject) {

  if (gAudio) {
    // Ensure decoder thread stops before deleting
    gAudio->stop();
    delete gAudio;
    gAudio = nullptr;
  }
  gVirtualClock.reset();
}

extern "C" JNIEXPORT void JNICALL
Java_com_mxlite_app_player_NativePlayer_nativePause(JNIEnv *, jobject) {

  if (gAudio) {
    gAudio->pause();
  } else {
    gVirtualClock.pause();
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_mxlite_app_player_NativePlayer_nativeResume(JNIEnv *, jobject) {

  if (gAudio) {
    gAudio->start();
  }
  // 🔴 FIX #2: Mandatory clock resume
  gVirtualClock.resume();
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_mxlite_app_player_NativePlayer_nativeGetDurationUs(JNIEnv *, jobject) {

  if (gAudio) {
    return gAudio->getDurationUs();
  }
  return 0;
}

/* ───────────────────────────── */
/* Clock JNI */
/* ───────────────────────────── */

extern "C" JNIEXPORT jlong JNICALL
Java_com_mxlite_app_player_NativePlayer_nativeGetClockUs(JNIEnv *, jobject) {

  return gVirtualClock.positionUs();
}

/* ───────────────────────────── */
/* 🔍 DEBUG / DIAGNOSTIC JNI */
/* ───────────────────────────── */

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mxlite_app_player_NativePlayer_dbgEngineCreated(JNIEnv *, jobject) {

  return gAudioDebug.engineCreated.load() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mxlite_app_player_NativePlayer_dbgAAudioOpened(JNIEnv *, jobject) {

  return gAudioDebug.aaudioOpened.load() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mxlite_app_player_NativePlayer_dbgAAudioStarted(JNIEnv *, jobject) {

  return gAudioDebug.aaudioStarted.load() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mxlite_app_player_NativePlayer_isAudioClockHealthy(JNIEnv *, jobject) {
  return gAudioHealthy.load() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mxlite_app_player_NativePlayer_dbgCallbackCalled(JNIEnv *, jobject) {

  return gAudioDebug.callbackCalled.load() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mxlite_app_player_NativePlayer_dbgDecoderProduced(JNIEnv *, jobject) {

  return gAudioDebug.decoderProduced.load() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_mxlite_app_player_NativePlayer_dbgBufferFill(JNIEnv *, jobject) {

  return gAudioDebug.bufferFill.load();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mxlite_app_player_NativePlayer_dbgNativePlayCalled(JNIEnv *, jobject) {

  return gAudioDebug.nativePlayCalled.load() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_mxlite_app_player_NativePlayer_dbgAAudioError(JNIEnv *, jobject) {
  return gAudioDebug.aaudioError.load();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_mxlite_app_player_NativePlayer_dbgAAudioErrorString(JNIEnv *env,
                                                             jobject) {
  int code = gAudioDebug.aaudioError.load();
  const char *txt =
      AAudio_convertResultToText(static_cast<aaudio_result_t>(code));
  if (!txt)
    txt = "UNKNOWN";
  return env->NewStringUTF(txt);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_mxlite_app_player_NativePlayer_dbgOpenStage(JNIEnv *, jobject) {
  return gAudioDebug.openStage.load();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mxlite_app_player_NativePlayer_dbgHasAudioTrack(JNIEnv *, jobject) {
  if (!gAudio)
    return JNI_FALSE;
  return gAudio->hasAudioTrack() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mxlite_app_player_NativePlayer_dbgDecodeActive(JNIEnv *, jobject) {
  return gAudioDebug.decodeActive.load(std::memory_order_acquire) ? JNI_TRUE
                                                                  : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_mxlite_app_player_NativePlayer_dbgGetClockLog(JNIEnv *env, jobject) {
  char buf[256];
  gVirtualClock.getLastLog(buf, sizeof(buf));
  return env->NewStringUTF(buf);
}

extern "C" JNIEXPORT void JNICALL
Java_com_mxlite_app_player_NativePlayer_playFd(JNIEnv *env, jobject /* thiz */,
                                               jint fd, jlong offset,
                                               jlong length) {

  if (!gAudio) {
    LOGE("MX-AUDIO", "AudioEngine is NULL, creating new AudioEngine");
    gAudio = new AudioEngine(&gVirtualClock);
  }

  // Mark native play call for diagnostics
  gAudioDebug.nativePlayCalled.store(true);

  bool ok = gAudio->openFd(fd, offset, length);
  if (!ok) {
    LOGE("MX-AUDIO", "openFd FAILED");
    return;
  }

  LOGE("MX-AUDIO", "openFd OK, starting audio");
  gAudio->start();
  // 🔴 FIX #1 (Redundant check for safe keeping): Mandatory clock start
  if (!gVirtualClock.isRunning()) {
    gVirtualClock.start();
  }
  LOGE("MX-AUDIO", "AudioEngine STARTED");
}