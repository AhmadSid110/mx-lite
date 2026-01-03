
package com.mxlite.app.player

import android.content.Context
import android.net.Uri
import android.view.Surface

class PlayerEngine(private val context: Context) {

    var forceSoftware = false

    private var hw: MediaCodecPlayer? = null
    private var sw: FFmpegPlayer? = null

    fun prepare(uri: Uri, surface: Surface) {
        release()

        if (forceSoftware) {
            startSoftware(uri, surface)
            return
        }

        try {
            hw = MediaCodecPlayer(context)
            hw!!.prepare(uri, surface)
        } catch (e: Exception) {
            startSoftware(uri, surface)
        }
    }

    private fun startSoftware(uri: Uri, surface: Surface) {
        if (!CodecPack.isInstalled()) {
            throw CodecPackMissingException()
        }
        sw = FFmpegPlayer()
        sw!!.prepare(uri, surface)
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
        hw = null
        sw = null
    }
}
