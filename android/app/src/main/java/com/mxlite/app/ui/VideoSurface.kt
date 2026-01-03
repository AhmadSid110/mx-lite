package com.mxlite.app

import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun VideoSurface(onSurfaceReady: (Surface) -> Unit) {
    
AndroidView(
    modifier = GestureOverlay(vm),
factory = { ctx ->
        SurfaceView(ctx).apply {
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    onSurfaceReady(holder.surface)
                }
                override fun surfaceChanged(
                    holder: SurfaceHolder,
                    format: Int,
                    width: Int,
                    height: Int
                ) {}
                override fun surfaceDestroyed(holder: SurfaceHolder) {}
            })
        }
    })
}