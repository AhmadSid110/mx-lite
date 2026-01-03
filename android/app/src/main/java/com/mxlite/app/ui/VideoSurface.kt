package com.mxlite.app

import android.app.Activity

import com.mxlite.app.player.AudioRenderer

import com.mxlite.app.player.PlayerController

import com.mxlite.app.player.PlayerGestureController

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

            setOnTouchListener(
                PlayerGestureController(
                    activity = ctx as Activity,
                    playerController = playerController,
                    getCurrentPositionMs = {
                        audioRenderer.getClockMs()
                    }
                )
            )

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