package com.mxlite.app.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.ParcelFileDescriptor
import java.io.File

object NativePlayer {
    init {
        System.loadLibrary("mxplayer")
    }

    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    /* ================= JNI (PRIVATE) ================= */

    private external fun nativePlay(path: String)
    private external fun nativePlayFd(
        fd: Int,
        offset: Long,
        length: Long
    )
    // ✅ NEW: Play using File Descriptor (public)
    external fun playFd(fd: Int, offset: Long, length: Long)
    private external fun nativeStop()
    external fun nativeSeek(positionUs: Long)
    private external fun nativeRelease()
    private external fun nativeGetClockUs(): Long
    external fun nativePause()
    external fun nativeResume()
    external fun nativeGetDurationUs(): Long

    var initialized = false
        private set

    val durationMs: Long
        get() = nativeGetDurationUs() / 1000L

    fun pause() {
        nativePause()
    }

    fun resume() {
        nativeResume()
    }

    /* ================= PUBLIC API ================= */

    fun play(path: String) {
        try {
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

            // ❗ DO NOT close pfd here
            // Native side dup()'d it

            initialized = true
        } catch (e: Exception) {
            e.printStackTrace()
            initialized = false
        }
    }

    fun stop() {
        if (initialized) {
            audioFocusRequest?.let {
                audioManager?.abandonAudioFocusRequest(it)
            }
            nativeStop()
        }
        initialized = false
    }

    fun seek(positionUs: Long) {
        nativeSeek(positionUs)
    }

    fun release() {
        nativeRelease()
        initialized = false
    }

    fun virtualClockUs(): Long {
        return nativeGetClockUs()
    }

    /* ================= DEBUG JNI ================= */

    external fun dbgEngineCreated(): Boolean
    external fun dbgAAudioOpened(): Boolean
    external fun dbgAAudioStarted(): Boolean
    external fun dbgAAudioError(): Int
    external fun dbgAAudioErrorString(): String
    external fun dbgHasAudioTrack(): Boolean
    external fun dbgOpenStage(): Int
    external fun dbgCallbackCalled(): Boolean
    external fun dbgDecoderProduced(): Boolean
    external fun dbgNativePlayCalled(): Boolean
    external fun dbgBufferFill(): Int

    // Returns true when audio track is running and timestamps are valid.
    external fun isAudioClockHealthy(): Boolean
}