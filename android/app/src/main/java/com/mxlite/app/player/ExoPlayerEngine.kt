package com.mxlite.app.player

import android.content.Context
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.io.File

class ExoPlayerEngine(
    context: Context
) : PlayerEngine {

    private val player = ExoPlayer.Builder(context).build()

    override fun attachSurface(surface: Surface) {
        player.setVideoSurface(surface)
    }

    override fun play(file: File) {
        val item = MediaItem.fromUri(file.toURI().toString())
        player.setMediaItem(item)
        player.prepare()
        player.playWhenReady = true
    }

    override fun pause() {
        player.pause()
    }

    override fun seekTo(positionMs: Long) {
        player.seekTo(positionMs)
    }

    override fun release() {
        player.release()
    }

    override val durationMs: Long
        get() = if (player.duration >= 0) player.duration else 0L

    override val currentPositionMs: Long
        get() = player.currentPosition

    override val isPlaying: Boolean
        get() = player.isPlaying
}
