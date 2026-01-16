package com.mxlite.app.player

import android.view.Surface
import android.net.Uri

interface PlayerEngine {

    fun attachSurface(surface: Surface)

    // New URI-based play API
    fun play(uri: Uri)

    // Current playing URI (nullable)
    val currentUri: Uri?

    // Recreate only the video pipeline when surface is recreated
    fun recreateVideo()

    // Detach the surface and free video resources
    fun detachSurface()

    fun pause()

    fun resume()

    fun seekTo(positionMs: Long)

    fun release()

    val durationMs: Long
    val currentPositionMs: Long
    val isPlaying: Boolean
}