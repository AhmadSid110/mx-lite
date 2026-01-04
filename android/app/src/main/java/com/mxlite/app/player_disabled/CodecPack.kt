
package com.mxlite.app.player

object CodecPack {

    fun isInstalled(): Boolean {
        return try {
            System.loadLibrary("ffmpeg_jni")
            true
        } catch (e: UnsatisfiedLinkError) {
            false
        }
    }
}
