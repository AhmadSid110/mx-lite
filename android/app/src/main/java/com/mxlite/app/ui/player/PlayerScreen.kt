package com.mxlite.app.ui.player

import android.net.Uri
import android.view.SurfaceView
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mxlite.app.player.PlayerEngine
import kotlinx.coroutines.delay
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    file: File?,
    safUri: Uri?,
    engine: PlayerEngine,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    // ───────── Playback State ─────────
    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }
    var controlsVisible by remember { mutableStateOf(true) }

    var userSeeking by remember { mutableStateOf(false) }
    var seekPositionMs by remember { mutableStateOf(0L) }

    // ⏱ Poll engine clock (audio-mastered)
    LaunchedEffect(Unit) {
        while (true) {
            if (!userSeeking) {
                positionMs = engine.currentPositionMs
                durationMs = engine.durationMs
            }
            delay(500)
        }
    }

    // 🔒 Lifecycle safety
    DisposableEffect(Unit) {
        onDispose {
            engine.release()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {

        // ───────── Top Bar ─────────
        if (controlsVisible) {
            TopAppBar(
                title = {
                    Text(
                        file?.name
                            ?: safUri?.lastPathSegment
                            ?: "Player"
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text("←")
                    }
                }
            )
        }

        // ───────── Video Surface + Gestures ─────────
        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            controlsVisible = !controlsVisible
                        },
                        onDoubleTap = { offset ->
                            val half = size.width / 2
                            val delta =
                                if (offset.x < half) -10_000 else 10_000

                            engine.seekTo(
                                (engine.currentPositionMs + delta)
                                    .coerceIn(0, engine.durationMs)
                            )
                        }
                    )
                },
            factory = {
                SurfaceView(it).apply {
                    holder.addCallback(
                        object : android.view.SurfaceHolder.Callback {

                            override fun surfaceCreated(holder: android.view.SurfaceHolder) {
                                engine.attachSurface(holder.surface)

                                // 🔹 Filesystem playback (SAF playback comes in SAF-5)
                                file?.let { engine.play(it) }
                            }

                            override fun surfaceChanged(
                                holder: android.view.SurfaceHolder,
                                format: Int,
                                width: Int,
                                height: Int
                            ) = Unit

                            override fun surfaceDestroyed(holder: android.view.SurfaceHolder) = Unit
                        }
                    )
                }
            }
        )

        // ───────── Controls ─────────
        if (controlsVisible) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {

                Slider(
                    value = if (durationMs > 0)
                        (if (userSeeking) seekPositionMs else positionMs).toFloat()
                    else 0f,
                    onValueChange = {
                        userSeeking = true
                        seekPositionMs = it.toLong()
                    },
                    onValueChangeFinished = {
                        engine.seekTo(seekPositionMs)
                        userSeeking = false
                    },
                    valueRange = 0f..maxOf(durationMs.toFloat(), 1f)
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "${formatTime(positionMs)} / ${formatTime(durationMs)}",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}
