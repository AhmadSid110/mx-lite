
package com.mxlite.app.player

import android.net.Uri
import android.view.Surface

class SoftwarePlayer {

    fun prepare(uri: Uri, surface: Surface) {
        throw UnsupportedOperationException(
            "Software decoder not installed. Install codec pack."
        )
    }

    fun play() {}
    fun pause() {}
    fun release() {}
}
