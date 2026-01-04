package com.mxlite.app.ui.player

import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import com.mxlite.app.player.PlayerEngine
import kotlin.math.abs
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    file: File,
    engine: PlayerEngine,
    onBack: () -> Unit
) {
    var showControls by remember { mutableStateOf(true) }
    var duration by remember { mutableStateOf(0L) }
    var position by remember { mutableStateOf(0L) }

    // 🔁 Poll audio clock (SAFE)
    LaunchedEffect(Unit) {
        while (true) {
            duration = engine.durationMs
            position = engine.currentPositionMs
            kotlinx.coroutines.delay(200)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = {},
                    onDrag = { change, dragAmount ->
                        // Horizontal seek
                        if (abs(dragAmount.x) > abs(dragAmount.y)) {
                            val deltaMs = (dragAmount.x * 50).toLong()
                            engine.seekTo((position + deltaMs).coerceIn(0, duration))
                        }
                    }
                )
            }
    ) {

        if (showControls) {
            TopAppBar(
                title = { Text(file.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text("←")
                    }
                }
            )
        }

        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .pointerInput(Unit) {
                    detectTapGestures {
                        showControls = !showControls
                    }
                },
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

        if (showControls) {
            Column(modifier = Modifier.padding(12.dp)) {

                Slider(
                    value = if (duration > 0) position.toFloat() / duration else 0f,
                    onValueChange = {
                        engine.seekTo((it * duration).toLong())
                    }
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
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
}
