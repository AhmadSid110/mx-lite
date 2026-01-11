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
    private external fun nativeStop()
    private external fun nativeSeek(positionUs: Long)
    private external fun nativeRelease()
    private external fun nativeGetClockUs(): Long

    /* ================= PUBLIC API ================= */

    fun play(context: Context, path: String) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener { }
            .build()

        audioManager = am
        audioFocusRequest = focusRequest

        val focusResult = am.requestAudioFocus(focusRequest)

        if (focusResult == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            nativePlay(path)
        }
    }

    fun stop() {
        audioFocusRequest?.let {
            audioManager?.abandonAudioFocusRequest(it)
        }
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