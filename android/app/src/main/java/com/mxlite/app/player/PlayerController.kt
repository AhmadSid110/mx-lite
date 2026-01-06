package com.mxlite.app.player

import android.content.Context
import android.net.Uri
import android.view.Surface
import java.io.File

class PlayerController(context: Context) : PlayerEngine {

    private val audio = AudioCodecEngine()
    private val video = MediaCodecEngine(
        context = context,
        clock = audio
    )

    override val durationMs: Long
        get() = video.durationMs

    override val currentPositionMs: Long
        get() = audio.positionMs

    override val isPlaying: Boolean
        get() = video.isPlaying

    override fun attachSurface(surface: Surface) {
        video.attachSurface(surface)
    }

    override fun play(file: File) {
        audio.reset()
        audio.play()
        video.play(file)
    }

    override fun play(uri: Uri) {
        audio.reset()
        audio.play()
        video.play(uri)
    }

    override fun pause() {
        audio.pause()
        video.pause()
    }

    override fun seekTo(positionMs: Long) {
        video.seekTo(positionMs)
    }

    override fun release() {
        audio.pause()
        video.release()
    }
}
