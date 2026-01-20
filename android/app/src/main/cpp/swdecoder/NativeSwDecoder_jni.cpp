#include "NativeSwDecoder.h"
#include <jni.h>


extern "C" {

JNIEXPORT jlong JNICALL
Java_com_mxlite_player_decoder_sw_NativeSwDecoder_nativeCreate(JNIEnv *,
                                                               jobject) {
  return reinterpret_cast<jlong>(new NativeSwDecoder());
}

JNIEXPORT void JNICALL
Java_com_mxlite_player_decoder_sw_NativeSwDecoder_nativePrepare(JNIEnv *,
                                                                jobject,
                                                                jlong ptr,
                                                                jint fd) {
  reinterpret_cast<NativeSwDecoder *>(ptr)->prepare(fd);
}

JNIEXPORT void JNICALL
Java_com_mxlite_player_decoder_sw_NativeSwDecoder_nativePlay(JNIEnv *, jobject,
                                                             jlong ptr) {
  reinterpret_cast<NativeSwDecoder *>(ptr)->play();
}

JNIEXPORT void JNICALL
Java_com_mxlite_player_decoder_sw_NativeSwDecoder_nativePause(JNIEnv *, jobject,
                                                              jlong ptr) {
  reinterpret_cast<NativeSwDecoder *>(ptr)->pause();
}

JNIEXPORT void JNICALL
Java_com_mxlite_player_decoder_sw_NativeSwDecoder_nativeSeek(JNIEnv *, jobject,
                                                             jlong ptr,
                                                             jlong posMs) {
  reinterpret_cast<NativeSwDecoder *>(ptr)->seek(posMs);
}

JNIEXPORT void JNICALL
Java_com_mxlite_player_decoder_sw_NativeSwDecoder_nativeStop(JNIEnv *, jobject,
                                                             jlong ptr) {
  reinterpret_cast<NativeSwDecoder *>(ptr)->stop();
}

JNIEXPORT void JNICALL
Java_com_mxlite_player_decoder_sw_NativeSwDecoder_nativeRelease(JNIEnv *,
                                                                jobject,
                                                                jlong ptr) {
  delete reinterpret_cast<NativeSwDecoder *>(ptr);
}
}
