package com.mxlite.app.player

import android.net.Uri
import android.view.Surface
import java.io.File

class PlayerController : PlayerEngine {

    private val audio = AudioCodecEngine()
    private val video = MediaCodecEngine(clock = audio)

    override val durationMs: Long
        get() = video.durationMs

    override val currentPositionMs: Long
        get() = audio.positionMs

    override val isPlaying: Boolean
        get() = video.isPlaying

    override fun attachSurface(surface: Surface) {
        video.attachSurface(surface)
    }

    // ───────── FILE PLAYBACK (WORKING) ─────────
    override fun play(file: File) {
        audio.reset()
        audio.play()
        video.play(file)
    }

    // ───────── SAF PLAYBACK (COMING IN SAF-5) ─────────
    override fun play(uri: Uri) {
        throw IllegalStateException(
            "SAF playback not implemented yet (SAF-5)"
        )
    }

    override fun pause() {
        audio.pause()
        video.pause()
    }

    override fun seekTo(positionMs: Long) {
        // Implemented in D2-D+
    }

    override fun release() {
        audio.pause()
        video.release()
    }
}
