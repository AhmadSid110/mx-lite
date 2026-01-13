package com.mxlite.app.player

import android.content.Context
import android.view.Surface
import java.io.File

class PlayerController(
    private val context: Context
) : PlayerEngine {

    private val nativeClock = NativeClock()
    private val video = MediaCodecEngine(clock = nativeClock)

    // ONLY for detecting audio track
    private val legacyAudio = AudioCodecEngine()

    private var hasAudio = false

    override val durationMs: Long
        get() = video.durationMs

    override val currentPositionMs: Long
        get() =
            if (hasAudio) {
                NativePlayer.getClockUs() / 1000
            } else {
                video.currentPositionMs
            }

    override val isPlaying: Boolean
        get() = video.isPlaying

    override fun attachSurface(surface: Surface) {
        video.attachSurface(surface)
    }

    override fun play(file: File) {
        release()

        // 1️⃣ Detect audio track
        hasAudio = legacyAudio.hasAudioTrack(file)

        // 2️⃣ Start native audio FIRST (if present)
        if (hasAudio) {
            NativePlayer.play(context, file.absolutePath)
        }

        // 3️⃣ Start video immediately
        video.play(file)
    }

    override fun pause() {
        if (hasAudio) {
            NativePlayer.stop()
        }
        video.pause()
    }

    override fun seekTo(positionMs: Long) {
        if (hasAudio) {
            NativePlayer.seek(positionMs * 1000)
        }
        video.seekTo(positionMs)
    }

    override fun release() {
        if (hasAudio) {
            NativePlayer.release()
        }
        legacyAudio.release()
        video.release()
        hasAudio = false
    }
}