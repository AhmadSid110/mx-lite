
package com.mxlite.app.player

import android.net.Uri
import android.view.Surface

class FFmpegPlayer {

    fun prepare(uri: Uri, surface: Surface) {
        // Phase A: codec pack not installed
        throw CodecPackMissingException()
    }

    fun play() {}
    fun pause() {}
    fun release() {}
}

class CodecPackMissingException : RuntimeException(
    "Codec pack not installed"
)
