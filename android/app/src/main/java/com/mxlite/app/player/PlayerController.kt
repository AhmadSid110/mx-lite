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
    private var paused = false

    override val durationMs: Long
        get() = video.durationMs

    override val currentPositionMs: Long
        get() = if (hasAudio) {
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
        paused = false
    }

    override fun pause() {
        if (!paused) {
            NativePlayer.pause()
            video.pause()
            paused = true
        }
    }

    fun resume() {
        if (paused) {
            NativePlayer.resume()
            video.resume()
            paused = false
        }
    }

    override fun seekTo(positionMs: Long) {
        val wasPlaying = isPlaying

        // 1. Pause
        pause()

        // 2. Seek audio (MASTER)
        NativePlayer.nativeSeek(positionMs * 1000)

        // 3. Seek video
        video.seekTo(positionMs)

        // 4. Resume if needed
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
        paused = false
    }
}