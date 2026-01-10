package com.mxlite.app.player

import android.content.Context

/**
 * JNI bridge to native audio engine.
 * Audio is the MASTER clock.
 * All timing comes from C++.
 */
object NativePlayer {
    init {
        System.loadLibrary("mxplayer")
    }

    /* ───────── JNI (internal) ───────── */

    internal external fun nativePlay(path: String)
    internal external fun nativeStop()
    internal external fun nativeSeek(positionUs: Long)
    internal external fun nativeRelease()
    internal external fun nativeGetClockUs(): Long

    /* ───────── Public API ───────── */

    fun play(context: Context, path: String) {
        nativePlay(path)
    }

    fun stop() = nativeStop()
    fun seek(positionUs: Long) = nativeSeek(positionUs)
    fun release() = nativeRelease()

    /* ───────── DEBUG JNI ───────── */

    external fun dbgEngineCreated(): Boolean
    external fun dbgAAudioOpened(): Boolean
    external fun dbgAAudioStarted(): Boolean
    external fun dbgCallbackCalled(): Boolean
    external fun dbgDecoderProduced(): Boolean
    external fun dbgBufferFill(): Int
}