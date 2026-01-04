package com.mxlite.app.player

import android.view.Surface
import java.io.File

class MediaCodecEngine : PlayerEngine {

    override fun attachSurface(surface: Surface) {
        // no-op
    }

    override fun play(file: File) {
        // Stub: pretend device does NOT support codec
        throw UnsupportedOperationException("MediaCodec unsupported")
    }

    override fun pause() {}
    override fun release() {}
}
