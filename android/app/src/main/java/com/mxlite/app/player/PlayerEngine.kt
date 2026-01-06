package com.mxlite.app.player

import android.net.Uri
import android.view.Surface
import java.io.File

interface PlayerEngine {

    fun attachSurface(surface: Surface)

    // Normal filesystem
    fun play(file: File)

    // SAF playback (implemented in SAF-5)
    fun play(uri: Uri)

    fun pause()
    fun seekTo(positionMs: Long)
    fun release()

    val durationMs: Long
    val currentPositionMs: Long
    val isPlaying: Boolean
}
