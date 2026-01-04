package com.mxlite.app.ui.player

import android.view.SurfaceView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mxlite.app.player.PlayerEngine
import java.io.File
import kotlinx.coroutines.delay

@Composable
fun PlayerScreen(
    file: File,
    engine: PlayerEngine,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    // Timeline state
    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }

    // Poll engine every 500ms (READ-ONLY)
    LaunchedEffect(Unit) {
        while (true) {
            positionMs = engine.currentPositionMs
            durationMs = engine.durationMs
            delay(500)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            engine.release()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {

        // Top bar
        TopAppBar(
            title = { Text(file.name) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Text("←")
                }
            }
        )

        // Video surface
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

        // Timeline (READ-ONLY)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {

            // Disabled progress bar
            LinearProgressIndicator(
                progress = if (durationMs > 0)
                    positionMs.toFloat() / durationMs.toFloat()
                else 0f,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Time text
            Text(
                text = "${formatTime(positionMs)} / ${formatTime(durationMs)}",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}
