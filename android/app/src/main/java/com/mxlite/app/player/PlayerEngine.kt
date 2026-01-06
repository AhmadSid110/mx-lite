package com.mxlite.app.player

import android.net.Uri
import android.view.Surface
import java.io.File

interface PlayerEngine {
    fun attachSurface(surface: Surface)

    // Filesystem playback
    fun play(file: File)

    // SAF playback (NEW)
    fun play(uri: Uri)

    fun pause()
    fun seekTo(positionMs: Long)
    fun release()

    val durationMs: Long
    val currentPositionMs: Long
    val isPlaying: Boolean
}
