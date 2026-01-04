package com.mxlite.app.ui.player

import android.app.Activity
import android.media.AudioManager
import android.view.SurfaceView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import com.mxlite.app.player.ExoPlayerEngine
import kotlin.math.abs
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    file: File,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as Activity
    val audioManager = context.getSystemService(AudioManager::class.java)

    val engine = remember { ExoPlayerEngine(context) }
    var controlsVisible by remember { mutableStateOf(true) }

    DisposableEffect(Unit) {
        onDispose { engine.release() }
    }

    Column(modifier = Modifier.fillMaxSize()) {

        if (controlsVisible) {
            TopAppBar(
                title = { Text(file.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text("←")
                    }
                }
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .pointerInput(Unit) {
                    detectTapGestures {
                        controlsVisible = !controlsVisible
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        val (dx, dy) = dragAmount

                        if (abs(dx) > abs(dy)) {
                            val delta = (dx * 50).toLong()
                            engine.seekTo(
                                (engine.currentPositionMs + delta)
                                    .coerceIn(0, engine.durationMs)
                            )
                        } else {
                            if (change.position.x < size.width / 2) {
                                val lp = activity.window.attributes
                                lp.screenBrightness =
                                    (lp.screenBrightness - dy / 3000f)
                                        .coerceIn(0.05f, 1f)
                                activity.window.attributes = lp
                            } else {
                                audioManager.adjustStreamVolume(
                                    AudioManager.STREAM_MUSIC,
                                    if (dy < 0)
                                        AudioManager.ADJUST_RAISE
                                    else
                                        AudioManager.ADJUST_LOWER,
                                    0
                                )
                            }
                        }
                    }
                }
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    SurfaceView(ctx).apply {
                        holder.addCallback(
                            object : android.view.SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: android.view.SurfaceHolder) {
                                    engine.attachSurface(holder.surface)
                                    engine.play(file)
                                }
                                override fun surfaceChanged(
                                    holder: android.view.SurfaceHolder,
                                    format: Int,
                                    width: Int,
                                    height: Int
                                ) {}
                                override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {}
                            }
                        )
                    }
                }
            )
        }

        if (controlsVisible) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Button(onClick = { engine.play(file) }) {
                    Text("Play")
                }
                Button(onClick = { engine.pause() }) {
                    Text("Pause")
                }
            }
        }
    }
}
