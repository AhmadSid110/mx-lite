package com.mxlite.app.player

import android.net.Uri
import android.view.Surface
import java.io.File

interface PlayerEngine {
    fun attachSurface(surface: Surface)

    fun play(file: File)
    fun play(uri: Uri)        // 🔥 SAF entry point

    fun pause()
    fun seekTo(positionMs: Long)
    fun release()

    val durationMs: Long
    val currentPositionMs: Long
    val isPlaying: Boolean
}
