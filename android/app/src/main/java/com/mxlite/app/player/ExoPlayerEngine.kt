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
) {

    private var player: ExoPlayer? = null

    private fun ensurePlayer() {
        if (player != null) return

        val trackSelector = DefaultTrackSelector(context)

        player = ExoPlayer.Builder(context)
            .setTrackSelector(trackSelector)
            .build().apply {
                playWhenReady = false
                repeatMode = Player.REPEAT_MODE_OFF
            }
    }

    fun attachSurface(surface: Surface) {
        ensurePlayer()
        player?.setVideoSurface(surface)
    }

    fun play(file: File) {
        ensurePlayer()
        val item = MediaItem.fromUri(Uri.fromFile(file))
        player?.setMediaItem(item)
        player?.prepare()
        player?.play()
    }

    fun pause() {
        player?.pause()
    }

    fun release() {
        player?.clearVideoSurface()
        player?.release()
        player = null
    }
}
