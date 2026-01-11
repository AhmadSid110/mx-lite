package com.mxlite.app.player

import android.content.Context
import android.net.Uri

/**
 * JNI bridge to native audio engine.
 * Audio is the MASTER clock.
 * All timing comes from C++.
 */
object NativePlayer {

    init {
        System.loadLibrary("mxplayer")
    }

    /* ================= JNI (PRIVATE) ================= */

    private external fun nativePlay(path: String)
    private external fun nativeStop()
    private external fun nativeSeek(positionUs: Long)
    private external fun nativeRelease()
    private external fun nativeGetClockUs(): Long

    /* ================= PUBLIC API ================= */

    /**
     * Start native audio playback.
     * This MUST be called before video playback.
     */
    fun play(context: Context, path: String) {
        val uri = Uri.parse(path)

        val nativePath =
            if (uri.scheme == "content") {
                // SAFELY resolve content:// URI to real file path
                NativeFileResolver.resolveToInternalPath(context, uri)
            } else {
                // file:// or absolute path
                path
            }

        nativePlay(nativePath)
    }

    fun stop() {
        nativeStop()
    }

    fun seek(positionUs: Long) {
        nativeSeek(positionUs)
    }

    fun release() {
        nativeRelease()
    }

    /**
     * MASTER CLOCK from native (microseconds)
     */
    fun getClockUs(): Long {
        return nativeGetClockUs()
    }

    /* ================= DEBUG / DIAGNOSTIC JNI ================= */

    external fun dbgEngineCreated(): Boolean
    external fun dbgAAudioOpened(): Boolean
    external fun dbgAAudioStarted(): Boolean
    external fun dbgAAudioError(): Int
    external fun dbgOpenStage(): Int
    external fun dbgCallbackCalled(): Boolean
    external fun dbgDecoderProduced(): Boolean
    external fun dbgNativePlayCalled(): Boolean
    external fun dbgBufferFill(): Int
}