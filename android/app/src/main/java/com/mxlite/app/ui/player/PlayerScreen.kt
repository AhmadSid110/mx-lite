package com.mxlite.app.ui.player

import android.view.SurfaceView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mxlite.app.player.MediaCodecEngine
import com.mxlite.app.player.PlayerEngine
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    file: File,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    // 🔑 CRITICAL FIX: type as PlayerEngine
    val engine: PlayerEngine = remember {
        MediaCodecEngine()
    }

    DisposableEffect(Unit) {
        onDispose { engine.release() }
    }

    Column(modifier = Modifier.fillMaxSize()) {

        TopAppBar(
            title = { Text(file.name) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Text("←")
                }
            }
        )

        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
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

        // --- Controls ---
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {

            Slider(
                value = engine.currentPositionMs.toFloat(),
                valueRange = 0f..engine.durationMs.toFloat().coerceAtLeast(1f),
                onValueChange = { engine.seekTo(it.toLong()) }
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
