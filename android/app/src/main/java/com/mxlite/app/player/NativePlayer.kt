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

    /* ───────────────────────────── */
    /* INTERNAL JNI CALLS (DO NOT USE DIRECTLY) */
    /* ───────────────────────────── */

    private external fun nativePlay(path: String)
    external fun nativeStop()
    external fun nativeGetClockUs(): Long
    external fun nativeSeek(positionUs: Long)
    external fun nativeRelease()

    /* ───────────────────────────── */
    /* PUBLIC SAFE API */
    /* ───────────────────────────── */

    /**
     * Start native audio playback.
     * This is the ONLY function the app should call.
     */
    fun play(context: Context, path: String) {
        val nativePath = NativeFileResolver.resolveToInternalPath(
            context = context,
            uri = Uri.parse(path)
        )

        nativePlay(nativePath)
    }

    /* ───────────────────────────── */
    /* DEBUG / DIAGNOSTIC JNI */
    /* ───────────────────────────── */

    external fun dbgEngineCreated(): Boolean
    external fun dbgAAudioOpened(): Boolean
    external fun dbgAAudioStarted(): Boolean
    external fun dbgCallbackCalled(): Boolean
    external fun dbgDecoderProduced(): Boolean
    external fun dbgBufferFill(): Int
}