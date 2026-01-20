package com.mxlite.app.player

import android.view.Surface
import android.net.Uri

interface PlayerEngine {

    val currentUri: Uri?
    val durationMs: Long
    val currentPositionMs: Long
    val isPlaying: Boolean
    val videoWidth: Int
    val videoHeight: Int
    val decoderName: String
    val outputFps: Float
    val droppedFrames: Int

    fun play(uri: Uri)
    fun pause()
    fun resume()

    // 🆕 HARD STOP
    fun stop()

    fun seekTo(positionMs: Long)

    fun attachSurface(surface: Surface)
    fun detachSurface()
    fun recreateVideo()
    fun release()

    fun onSeekStart()
    fun onSeekPreview(positionMs: Long)
    fun onSeekCommit(positionMs: Long)

    // 🧪 DEVELOPER TOOLS
    fun switchDecoder()
}