package com.mxlite.app.player

import android.view.Surface
import java.io.File

class MediaCodecEngine : PlayerEngine {

    override fun attachSurface(surface: Surface) {}
    override fun play(file: File) {}
    override fun pause() {}
    override fun seekTo(positionMs: Long) {}
    override fun release() {}

    override val durationMs: Long = 0
    override val currentPositionMs: Long = 0
    override val isPlaying: Boolean = false
}
