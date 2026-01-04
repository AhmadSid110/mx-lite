package com.mxlite.app.player

import android.content.Context
import android.net.Uri
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import java.io.File

class ExoPlayerEngine(
    private val context: Context
) : PlayerEngine {

    private var player: ExoPlayer? = null
    private var surface: Surface? = null

    private fun ensurePlayer() {
        if (player != null) return

        val trackSelector = DefaultTrackSelector(context)

        player = ExoPlayer.Builder(context)
            .setTrackSelector(trackSelector)
            .build().apply {
                repeatMode = Player.REPEAT_MODE_OFF
                playWhenReady = false
            }
    }

    override fun attachSurface(surface: Surface) {
        ensurePlayer()
        this.surface = surface
        player?.setVideoSurface(surface)
    }

    override fun play(file: File) {
        ensurePlayer()
        val mediaItem = MediaItem.fromUri(Uri.fromFile(file))
        player?.apply {
            setMediaItem(mediaItem)
            prepare()
            play()
        }
    }

    override fun pause() {
        player?.pause()
    }

    override fun seekTo(positionMs: Long) {
        player?.seekTo(positionMs)
    }

    override fun release() {
        player?.clearVideoSurface()
        player?.release()
        player = null
        surface = null
    }

    override val durationMs: Long
        get() = player?.duration ?: 0L

    override val currentPositionMs: Long
        get() = player?.currentPosition ?: 0L

    override val isPlaying: Boolean
        get() = player?.isPlaying ?: false
}
