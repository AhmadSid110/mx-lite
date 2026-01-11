package com.mxlite.app.player

import android.content.Context
import android.os.ParcelFileDescriptor
import java.io.File

object NativePlayer {
    init {
        System.loadLibrary("mxplayer")
    }

    /* ================= JNI (PRIVATE) ================= */

    private external fun nativePlay(path: String)
    private external fun nativePlayFd(
        fd: Int,
        offset: Long,
        length: Long
    )
    private external fun nativeStop()
    private external fun nativeSeek(positionUs: Long)
    private external fun nativeRelease()
    private external fun nativeGetClockUs(): Long

    /* ================= PUBLIC API ================= */

    fun play(context: Context, path: String) {
        val file = File(path)

        val pfd = ParcelFileDescriptor.open(
            file,
            ParcelFileDescriptor.MODE_READ_ONLY
        )

        nativePlayFd(
            pfd.fd,
            0L,
            pfd.statSize
        )

        // DO NOT close pfd yet (native uses it)
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
    external fun dbgAAudioError(): Int
    external fun dbgOpenStage(): Int
    external fun dbgCallbackCalled(): Boolean
    external fun dbgDecoderProduced(): Boolean
    external fun dbgNativePlayCalled(): Boolean
    external fun dbgBufferFill(): Int
}