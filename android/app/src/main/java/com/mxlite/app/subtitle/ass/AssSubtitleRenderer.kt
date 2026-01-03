
package com.mxlite.app.subtitle.ass

import android.graphics.Bitmap

class AssSubtitleRenderer(path: String) {

    private val handle: Long = nativeInit(path)

    external fun nativeInit(path: String): Long
    external fun nativeRender(handle: Long, timeMs: Long): Bitmap?
    external fun nativeRelease(handle: Long)

    fun render(timeMs: Long): Bitmap? =
        nativeRender(handle, timeMs)

    fun release() {
        nativeRelease(handle)
    }

    companion object {
        init {
            System.loadLibrary("ffmpeg_jni")
        }
    }
}
