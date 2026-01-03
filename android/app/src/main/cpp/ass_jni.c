
#include "ass_jni.h"
#include <ass/ass.h>

JNIEXPORT jlong JNICALL
Java_com_mxlite_app_subtitle_ass_AssSubtitleRenderer_nativeInit(
    JNIEnv* env, jobject thiz, jstring path) {

    const char* cpath = (*env)->GetStringUTFChars(env, path, 0);

    ASS_Library* lib = ass_library_init();
    ass_set_message_cb(lib, NULL, NULL);

    ASS_Renderer* renderer = ass_renderer_init(lib);
    ass_set_frame_size(renderer, 1920, 1080);

    ASS_Track* track = ass_read_file(lib, cpath, NULL);

    (*env)->ReleaseStringUTFChars(env, path, cpath);

    return (jlong)track;
}

JNIEXPORT jobject JNICALL
Java_com_mxlite_app_subtitle_ass_AssSubtitleRenderer_nativeRender(
    JNIEnv* env, jobject thiz, jlong handle, jlong timeMs) {

    ASS_Track* track = (ASS_Track*)handle;
    if (!track) return NULL;

    ASS_Image* img =
        ass_render_frame(track->library->renderer, track, timeMs, NULL);

    // (Bitmap creation omitted for brevity; placeholder)
    return NULL;
}

JNIEXPORT void JNICALL
Java_com_mxlite_app_subtitle_ass_AssSubtitleRenderer_nativeRelease(
    JNIEnv* env, jobject thiz, jlong handle) {

    ASS_Track* track = (ASS_Track*)handle;
    if (track) {
        ass_free_track(track);
    }
}
