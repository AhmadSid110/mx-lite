package com.mxlite.app.player

import android.content.Context

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

    fun play(context: Context, path: String) {
        nativePlay(path)
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

    fun getClockUs(): Long {
        return nativeGetClockUs()
    }

    /* ================= DEBUG JNI ================= */

    external fun dbgEngineCreated(): Boolean
    external fun dbgAAudioOpened(): Boolean
    external fun dbgAAudioStarted(): Boolean
    external fun dbgCallbackCalled(): Boolean
    external fun dbgDecoderProduced(): Boolean
    external fun dbgNativePlayCalled(): Boolean
    external fun dbgBufferFill(): Int
}