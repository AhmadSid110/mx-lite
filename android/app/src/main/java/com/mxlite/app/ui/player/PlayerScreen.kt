package com.mxlite.app.ui.player

import android.net.Uri
import android.view.SurfaceView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorSaver
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import com.mxlite.app.player.PlayerEngine
import com.mxlite.app.subtitle.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

private val SubtitleMimeTypes = arrayOf("application/x-subrip", "text/plain", "text/x-srt")
private const val MinSubtitleFontSize = 12f
private const val MaxSubtitleFontSize = 28f
private const val MinSubtitleOpacity = 0f
private const val MaxSubtitleOpacity = 1f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    file: File,
    engine: PlayerEngine,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }
    var controlsVisible by remember { mutableStateOf(true) }
    var subtitleLine by remember { mutableStateOf<SubtitleCue?>(null) }

    var userSeeking by remember { mutableStateOf(false) }
    var seekPositionMs by remember { mutableStateOf(0L) }

    var subtitleController by remember { mutableStateOf<SubtitleController?>(null) }
    var subtitleOffsetMs by rememberSaveable { mutableStateOf(0L) }
    var subtitlesEnabled by rememberSaveable { mutableStateOf(true) }
    var subtitleFontSize by rememberSaveable { mutableStateOf(18f) }
    var subtitleBgOpacity by rememberSaveable { mutableStateOf(0.6f) }
    var subtitleColor by rememberSaveable(stateSaver = ColorSaver) { mutableStateOf(Color.White) }
    var subtitleError by rememberSaveable { mutableStateOf<String?>(null) }

    val subtitlePicker =
        rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri: Uri? ->
            if (uri != null) {
                scope.launch {
                    val cues = SubtitleLoader.loadFromUri(context, uri)
                    subtitleController = cues.takeIf { it.isNotEmpty() }?.let { SubtitleController(it) }
                    subtitleLine = null
                    subtitleError = if (cues.isNotEmpty()) null else "Failed to load subtitles"
                }
            }
        }

    LaunchedEffect(file) {
        subtitleController = null
        subtitleLine = null
        val parent = file.parentFile
        if (parent != null) {
            val srt = File(parent, file.nameWithoutExtension + ".srt")
            val cues = SubtitleLoader.loadFromFile(srt)
            subtitleController = cues.takeIf { it.isNotEmpty() }?.let { SubtitleController(it) }
            subtitleError = if (srt.exists() && cues.isEmpty()) "Failed to load ${srt.name}" else null
        } else {
            subtitleError = null
        }
    }

    // ⏱ Poll engine clock
    LaunchedEffect(Unit) {
        while (true) {
            if (!userSeeking) {
                positionMs = engine.currentPositionMs
                durationMs = engine.durationMs
            }
            val effectiveTimeMs = (engine.currentPositionMs + subtitleOffsetMs).coerceAtLeast(0L)
            subtitleLine = subtitleController?.current(effectiveTimeMs)
            delay(500)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            engine.release()
        }
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
        ) {
            AndroidView(
                modifier = Modifier
                    .matchParentSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { controlsVisible = !controlsVisible },
                            onDoubleTap = { offset ->
                                val half = size.width / 2
                                val delta = if (offset.x < half) -10_000 else 10_000
                                engine.seekTo(
                                    (engine.currentPositionMs + delta)
                                        .coerceIn(0, engine.durationMs)
                                )
                            }
                        )
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
                                ) = Unit

                                override fun surfaceDestroyed(holder: android.view.SurfaceHolder) = Unit
                            }
                        )
                    }
                }
            )

            subtitleLine?.let { line ->
                if (subtitlesEnabled) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 48.dp),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        Text(
                            text = line.text,
                            color = subtitleColor,
                            textAlign = TextAlign.Center,
                            fontSize = subtitleFontSize.sp,
                            modifier = Modifier
                                .background(
                                    Color.Black.copy(alpha = subtitleBgOpacity),
                                    RoundedCornerShape(8.dp)
                                )
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }

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

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        Text("Subtitles", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "Delay: ${if (subtitleOffsetMs >= 0) "+" else ""}${subtitleOffsetMs} ms",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    Switch(
                        checked = subtitlesEnabled,
                        onCheckedChange = { subtitlesEnabled = it }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedButton(onClick = { subtitleOffsetMs -= 250 }) {
                        Text("-250ms")
                    }
                    OutlinedButton(onClick = { subtitleOffsetMs += 250 }) {
                        Text("+250ms")
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { subtitlePicker.launch(SubtitleMimeTypes) }) {
                        Text("Load Subtitles")
                    }
                }

                subtitleError?.let { error ->
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Font Size: ${subtitleFontSize.toInt()}sp",
                    style = MaterialTheme.typography.labelMedium
                )
                Slider(
                    value = subtitleFontSize,
                    onValueChange = { subtitleFontSize = it },
                    valueRange = MinSubtitleFontSize..MaxSubtitleFontSize
                )

                Text(
                    text = "Background Opacity: ${"%.2f".format(subtitleBgOpacity)}",
                    style = MaterialTheme.typography.labelMedium
                )
                Slider(
                    value = subtitleBgOpacity,
                    onValueChange = { subtitleBgOpacity = it },
                    valueRange = MinSubtitleOpacity..MaxSubtitleOpacity
                )

                Text(
                    text = "Subtitle Color",
                    style = MaterialTheme.typography.labelMedium
                )
                SubtitleColorSelector(
                    selected = subtitleColor,
                    onColorSelected = { subtitleColor = it }
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

@Composable
fun SubtitleColorSelector(
    selected: Color,
    onColorSelected: (Color) -> Unit
) {
    val colors = listOf(
        Color.White,
        Color.Yellow,
        Color.Cyan,
        Color.Green
    )

    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(vertical = 8.dp)
    ) {
        colors.forEach { color ->
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(
                        color = color,
                        shape = CircleShape
                    )
                    .border(
                        width = if (selected == color) 3.dp else 1.dp,
                        color = if (selected == color)
                            MaterialTheme.colorScheme.primary
                        else
                            Color.Gray,
                        shape = CircleShape
                    )
                    .clickable {
                        onColorSelected(color)
                    }
            )
        }
    }
}
