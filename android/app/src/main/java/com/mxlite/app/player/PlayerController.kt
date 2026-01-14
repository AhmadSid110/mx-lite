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
    private var playing = false

    override val durationMs: Long
        get() = video.durationMs

    override val currentPositionMs: Long
        get() = if (hasAudio) {
            NativePlayer.getClockUs() / 1000
        } else {
            video.currentPositionMs
        }

    override val isPlaying: Boolean
        get() = playing

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
        playing = true
    }

    override fun pause() {
        if (!playing) return

        playing = false

        // 1️⃣ Pause video FIRST
        video.pause()

        // 2️⃣ Pause audio (master)
        NativePlayer.nativePause()
    }

    override fun resume() {
        if (playing) return

        playing = true

        // 1️⃣ Resume audio FIRST (master clock)
        NativePlayer.nativeResume()

        // 2️⃣ Resume video
        video.resume()
    }

    override fun seekTo(positionMs: Long) {
        val wasPlaying = playing

        // 1️⃣ FULL PAUSE
        if (wasPlaying) {
            pause()
        }

        // 2️⃣ SEEK AUDIO (MASTER)
        NativePlayer.nativeSeek(positionMs * 1000)

        // 3️⃣ SEEK VIDEO
        video.seekTo(positionMs)

        // 4️⃣ RESUME
        if (wasPlaying) {
            resume()
        }
    }

    override fun release() {
        if (hasAudio) {
            NativePlayer.release()
        }
        legacyAudio.release()
        video.release()
        hasAudio = false
        playing = false
    }
}