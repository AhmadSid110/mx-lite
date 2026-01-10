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

    // JNI stays PRIVATE
    private external fun nativePlay(path: String)

    // PUBLIC API used by PlayerController
    fun play(context: Context, path: String) {
        nativePlay(path)
    }
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