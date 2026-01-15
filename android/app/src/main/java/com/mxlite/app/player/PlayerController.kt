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
    private var currentFile: File? = null

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

        // Store current file for seek operations
        currentFile = file

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

        // ONLY audio pauses
        if (hasAudio) {
            NativePlayer.nativePause()
        }
    }

    override fun resume() {
        if (playing) return

        playing = true

        // ONLY audio resumes
        if (hasAudio) {
            NativePlayer.nativeResume()
        }
    }

    override fun seekTo(positionMs: Long) {
        val wasPlaying = playing

        // 1. Pause audio (if present)
        if (hasAudio) {
            NativePlayer.nativePause()
        }

        // 2. Seek audio (MASTER - if present)
        if (hasAudio) {
            NativePlayer.nativeSeek(positionMs * 1000)
        }

        // 3. RECREATE VIDEO ENGINE
        currentFile?.let { file ->
            video.release()
            video.play(file)
        }

        // 4. Resume audio if needed
        if (wasPlaying && hasAudio) {
            NativePlayer.nativeResume()
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
        currentFile = null
    }
}