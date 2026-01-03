
#include <jni.h>

JNIEXPORT jlong JNICALL
Java_com_mxlite_app_subtitle_ass_AssSubtitleRenderer_nativeInit(
    JNIEnv*, jobject, jstring path);

JNIEXPORT jobject JNICALL
Java_com_mxlite_app_subtitle_ass_AssSubtitleRenderer_nativeRender(
    JNIEnv*, jobject, jlong handle, jlong timeMs);

JNIEXPORT void JNICALL
Java_com_mxlite_app_subtitle_ass_AssSubtitleRenderer_nativeRelease(
    JNIEnv*, jobject, jlong handle);
