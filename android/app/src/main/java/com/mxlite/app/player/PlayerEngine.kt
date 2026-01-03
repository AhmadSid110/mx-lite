
package com.mxlite.app.player

import android.content.Context
import android.net.Uri
import android.view.Surface

class PlayerEngine(private val context: Context) {

    var forceSoftware = false

    private var hw: MediaCodecPlayer? = null
    private var sw: SoftwarePlayer? = null

    fun prepare(uri: Uri, surface: Surface) {
        if (forceSoftware) {
            sw = SoftwarePlayer()
            sw!!.prepare(uri, surface)
            return
        }

        try {
            hw = MediaCodecPlayer(context)
            hw!!.prepare(uri, surface)
        } catch (e: Exception) {
            throw CodecException(e.message ?: "Unsupported codec")
        }
    }

    fun play() {
        hw?.play()
        sw?.play()
    }

    fun pause() {
        hw?.pause()
        sw?.pause()
    }

    fun release() {
        hw?.release()
        sw?.release()
    }
}

class CodecException(msg: String) : RuntimeException(msg)
