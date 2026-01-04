package com.mxlite.app.player

import android.view.Surface
import java.io.File

interface PlayerEngine {
    fun attachSurface(surface: Surface)
    fun play(file: File)
    fun pause()
    fun release()
}
