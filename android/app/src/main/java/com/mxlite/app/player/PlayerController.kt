package com.mxlite.app.player

import android.content.Context
import android.view.Surface
import java.io.File

class PlayerController(
    private val context: Context
) {
    private var engine: PlayerEngine? = null
    private var surface: Surface? = null

    fun attachSurface(surface: Surface) {
        this.surface = surface
        engine?.attachSurface(surface)
    }

    fun play(file: File) {
        release()

        try {
            engine = MediaCodecEngine().also {
                surface?.let(it::attachSurface)
                it.play(file)
            }
        } catch (e: Exception) {
            engine = ExoPlayerEngine(context).also {
                surface?.let(it::attachSurface)
                it.play(file)
            }
        }
    }

    fun pause() {
        engine?.pause()
    }

    fun release() {
        engine?.release()
        engine = null
    }
}
