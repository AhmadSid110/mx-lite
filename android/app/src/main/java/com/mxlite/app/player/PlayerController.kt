package com.mxlite.app.player

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
        get() = true

    override fun attachSurface(surface: Surface) {
        video.attachSurface(surface)
    }

    override fun play(file: File) {
        audio.reset()
        audio.play(file)
        video.play(file)
    }

    override fun pause() {
        audio.pause()
        video.pause()
    }

    override fun seekTo(positionMs: Long) {
        // F-8
    }

    override fun release() {
        audio.release()
        video.release()
    }
}
