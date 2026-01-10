package com.mxlite.app.player

import android.content.Context

/**
 * JNI bridge to native audio engine.
 *
 * 🔑 Audio is the MASTER clock.
 * All timing comes from native C++ AudioEngine.
 *
 * RULES:
 * - JNI methods are PRIVATE
 * - Kotlin exposes a SAFE public API
 * - PlayerController NEVER touches JNI directly
 */
object NativePlayer {

    init {
        System.loadLibrary("mxplayer")
    }

    /* ───────────────────────────── */
    /* JNI (PRIVATE — DO NOT USE) */
    /* ───────────────────────────── */

    private external fun nativePlay(path: String)
    private external fun nativeStop()
    private external fun nativeSeek(positionUs: Long)
    private external fun nativeRelease()
    private external fun nativeGetClockUs(): Long

    /* ───────────────────────────── */
    /* PUBLIC API (USED BY APP) */
    /* ───────────────────────────── */

    fun play(context: Context, path: String) {
        // Context kept for future SAF / content:// handling
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

    /* ───────────────────────────── */
    /* DEBUG / DIAGNOSTIC JNI */
    /* (USED BY ON-SCREEN DEBUG UI) */
    /* ───────────────────────────── */

    external fun dbgEngineCreated(): Boolean
    external fun dbgAAudioOpened(): Boolean
    external fun dbgAAudioStarted(): Boolean
    external fun dbgCallbackCalled(): Boolean
    external fun dbgDecoderProduced(): Boolean
    external fun dbgBufferFill(): Int
}