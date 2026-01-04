
package com.mxlite.app.player.gl

import android.content.Context
import android.opengl.GLSurfaceView

class GLVideoSurface(context: Context) : GLSurfaceView(context) {

    private val renderer = GLVideoRenderer()

    init {
        setEGLContextClientVersion(2)
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun requestRenderFrame() {
        requestRender()
    }
}
