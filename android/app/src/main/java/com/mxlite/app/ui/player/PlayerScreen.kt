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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.documentfile.provider.DocumentFile
import com.mxlite.app.player.PlayerEngine
import com.mxlite.app.player.AudioTrackInfo
import com.mxlite.app.player.AudioTrackExtractor
import com.mxlite.app.player.AudioTrackPrefsStore
import com.mxlite.app.player.PlaybackSpeedPrefsStore
import com.mxlite.app.subtitle.SubtitleController
import com.mxlite.app.subtitle.SubtitleCue
import com.mxlite.app.subtitle.SubtitlePrefsStore
import com.mxlite.app.subtitle.SubtitleTrack
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

private val SubtitleMimeTypes = arrayOf(
    "application/x-subrip",
    "text/plain", 
    "text/x-srt",
    "text/x-ssa",
    "text/x-ass"
)
private const val MinSubtitleFontSize = 12f
private const val MaxSubtitleFontSize = 28f
private const val MinSubtitleOpacity = 0f
private const val MaxSubtitleOpacity = 1f
private const val MinBottomMargin = 16f
private const val MaxBottomMargin = 120f
private const val MaxSubtitleLines = 3
private const val SubtitleShadowBlurRadius = 8f
private const val PrefsSaveDebounceMs = 500L
private const val MaxTrackDisplayNameLength = 15

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    file: File,
    engine: PlayerEngine,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    val prefsStore = remember { SubtitlePrefsStore(context) }
    val audioTrackPrefsStore = remember { AudioTrackPrefsStore(context) }
    val playbackSpeedPrefsStore = remember { PlaybackSpeedPrefsStore(context) }
    val videoId = remember(file) { SubtitlePrefsStore.videoIdFromFile(file) }

    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }
    var controlsVisible by remember { mutableStateOf(true) }
    var subtitleLine by remember { mutableStateOf<SubtitleCue?>(null) }

    var userSeeking by remember { mutableStateOf(false) }
    var seekPositionMs by remember { mutableStateOf(0L) }

    var subtitleController by remember { mutableStateOf<SubtitleController?>(null) }
    var subtitleOffsetMs by remember { mutableStateOf(0L) }
    var subtitlesEnabled by remember { mutableStateOf(true) }
    var subtitleFontSizeSp by remember { mutableStateOf(18f) }
    var subtitleBgOpacity by remember { mutableStateOf(0.6f) }
    var subtitleColor by remember { mutableStateOf(Color.White) }
    var subtitleBottomMargin by remember { mutableStateOf(48f) }
    var subtitleError by remember { mutableStateOf<String?>(null) }
    var selectedTrackId by remember { mutableStateOf<String?>(null) }
    var showTrackSelector by remember { mutableStateOf(false) }
    
    // Audio track selection state
    var availableAudioTracks by remember { mutableStateOf<List<AudioTrackInfo>>(emptyList()) }
    var selectedAudioTrackIndex by remember { mutableStateOf<Int?>(null) }
    var showAudioTrackSelector by remember { mutableStateOf(false) }
    
    // Playback speed state
    var playbackSpeed by remember { mutableStateOf(1.0f) }

    val subtitlePicker =
        rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri: Uri? ->
            if (uri != null) {
                scope.launch {
                    val docFile = DocumentFile.fromSingleUri(context, uri)
                    val track = SubtitleTrack.SafTrack(
                        uri = uri,
                        name = docFile?.name ?: "Subtitle"
                    )
                    subtitleController?.addTrack(track)
                    subtitleController?.selectTrack(track.id)
                    selectedTrackId = track.id
                    subtitleLine = null
                    subtitleError = null
                    
                    // Save selected track
                    prefsStore.save(
                        videoId,
                        SubtitlePrefsStore.SubtitlePrefs(
                            enabled = subtitlesEnabled,
                            offsetMs = subtitleOffsetMs,
                            fontSizeSp = subtitleFontSizeSp,
                            textColor = subtitleColor,
                            bgOpacity = subtitleBgOpacity,
                            selectedTrackId = track.id,
                            bottomMarginDp = subtitleBottomMargin
                        )
                    )
                }
            }
        }

    // Load preferences and initialize subtitle controller
    LaunchedEffect(file) {
        // Load saved preferences
        val savedPrefs = prefsStore.load(videoId)
        subtitlesEnabled = savedPrefs.enabled
        subtitleOffsetMs = savedPrefs.offsetMs
        subtitleFontSizeSp = savedPrefs.fontSizeSp
        subtitleColor = savedPrefs.textColor
        subtitleBgOpacity = savedPrefs.bgOpacity
        subtitleBottomMargin = savedPrefs.bottomMarginDp
        selectedTrackId = savedPrefs.selectedTrackId
        
        // Load playback speed
        playbackSpeed = playbackSpeedPrefsStore.loadSpeed(videoId)
        
        // Load available audio tracks
        availableAudioTracks = AudioTrackExtractor.extractAudioTracks(file)
        selectedAudioTrackIndex = audioTrackPrefsStore.loadTrackIndex(videoId)
        
        // Initialize subtitle controller
        subtitleController = SubtitleController(context)
        subtitleLine = null
        
        // Auto-load subtitle from same folder
        val parent = file.parentFile
        if (parent != null) {
            // Try SRT first, then ASS
            val subtitleFiles = listOf(
                File(parent, file.nameWithoutExtension + ".srt"),
                File(parent, file.nameWithoutExtension + ".ass"),
                File(parent, file.nameWithoutExtension + ".ssa")
            )
            
            for (subtitleFile in subtitleFiles) {
                if (subtitleFile.exists()) {
                    val track = SubtitleTrack.FileTrack(subtitleFile)
                    subtitleController?.addTrack(track)
                    
                    // Restore saved track or use default
                    if (savedPrefs.selectedTrackId == track.id) {
                        subtitleController?.selectTrack(track.id)
                        selectedTrackId = track.id
                    } else if (savedPrefs.selectedTrackId == null) {
                        // No saved preference, use first available track
                        subtitleController?.selectTrack(track.id)
                        selectedTrackId = track.id
                    }
                    break  // Use first found subtitle file
                }
            }
        }
        
        subtitleError = null
    }

    // Save preferences when they change
    LaunchedEffect(
        subtitlesEnabled,
        subtitleOffsetMs,
        subtitleFontSizeSp,
        subtitleColor,
        subtitleBgOpacity,
        selectedTrackId,
        subtitleBottomMargin
    ) {
        // Debounce saves
        delay(PrefsSaveDebounceMs)
        prefsStore.save(
            videoId,
            SubtitlePrefsStore.SubtitlePrefs(
                enabled = subtitlesEnabled,
                offsetMs = subtitleOffsetMs,
                fontSizeSp = subtitleFontSizeSp,
                textColor = subtitleColor,
                bgOpacity = subtitleBgOpacity,
                selectedTrackId = selectedTrackId,
                bottomMarginDp = subtitleBottomMargin
            )
        )
    }

    // Save playback speed when it changes
    LaunchedEffect(playbackSpeed) {
        // Debounce saves
        delay(PrefsSaveDebounceMs)
        playbackSpeedPrefsStore.saveSpeed(videoId, playbackSpeed)
    }

    // ⏱ Poll engine clock
    LaunchedEffect(Unit) {
        while (true) {
            if (!userSeeking) {
                positionMs = engine.currentPositionMs
                durationMs = engine.durationMs
            }
            // Adjust position for playback speed and subtitle offset
            // At 2x speed, video advances faster, so we scale up time for subtitle matching
            val adjustedPositionMs = (engine.currentPositionMs * playbackSpeed).toLong()
            val effectiveTimeMs = (adjustedPositionMs + subtitleOffsetMs).coerceAtLeast(0L)
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
                            .padding(bottom = subtitleBottomMargin.dp)
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        OutlinedSubtitleText(
                            text = line.text,
                            fontSizeSp = (line.style.fontSizeSp ?: subtitleFontSizeSp)
                                .coerceIn(MinSubtitleFontSize, MaxSubtitleFontSize),
                            textColor = line.style.color ?: subtitleColor,
                            outlineColor = Color.Black,
                            outlineWidthDp = 3f,
                            style = line.style,
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
                        Text("Load")
                    }
                }

                // Track selector
                subtitleController?.let { controller ->
                    if (controller.availableTracks.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Text(
                            text = "Subtitle Track",
                            style = MaterialTheme.typography.labelMedium
                        )
                        
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        selectedTrackId = null
                                        controller.selectTrack(null)
                                    }
                                }
                            ) {
                                Text("None")
                            }
                            
                            controller.availableTracks.forEach { track ->
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            selectedTrackId = track.id
                                            controller.selectTrack(track.id)
                                        }
                                    }
                                ) {
                                    Text(
                                        text = track.displayName.take(MaxTrackDisplayNameLength),
                                        color = if (selectedTrackId == track.id)
                                            MaterialTheme.colorScheme.primary
                                        else
                                            MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
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
                    text = "Font Size: ${subtitleFontSizeSp.toInt()}sp",
                    style = MaterialTheme.typography.labelMedium
                )
                Slider(
                    value = subtitleFontSizeSp,
                    onValueChange = { subtitleFontSizeSp = it },
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
                    text = "Bottom Margin: ${subtitleBottomMargin.toInt()}dp",
                    style = MaterialTheme.typography.labelMedium
                )
                Slider(
                    value = subtitleBottomMargin,
                    onValueChange = { subtitleBottomMargin = it },
                    valueRange = MinBottomMargin..MaxBottomMargin
                )

                Text(
                    text = "Subtitle Color",
                    style = MaterialTheme.typography.labelMedium
                )
                SubtitleColorSelector(
                    selected = subtitleColor,
                    onColorSelected = { subtitleColor = it }
                )
                
                // Playback Speed Controls
                Spacer(modifier = Modifier.height(12.dp))
                
                Text(
                    text = "Playback Speed: ${playbackSpeed}x",
                    style = MaterialTheme.typography.titleSmall
                )
                
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    listOf(0.5f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { speed ->
                        OutlinedButton(
                            onClick = { playbackSpeed = speed },
                            modifier = Modifier.weight(1f),
                            colors = if (playbackSpeed == speed) {
                                ButtonDefaults.outlinedButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer
                                )
                            } else {
                                ButtonDefaults.outlinedButtonColors()
                            }
                        ) {
                            Text("${speed}x")
                        }
                    }
                }
                
                // Audio Track Selection
                if (availableAudioTracks.size > 1) {
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Audio Track", style = MaterialTheme.typography.titleSmall)
                        OutlinedButton(onClick = { showAudioTrackSelector = true }) {
                            Text("Select")
                        }
                    }
                    
                    selectedAudioTrackIndex?.let { index ->
                        availableAudioTracks.find { it.trackIndex == index }?.let { track ->
                            Text(
                                text = track.displayName,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }
        
        // Audio Track Selector Dialog
        if (showAudioTrackSelector) {
            AudioTrackSelectorDialog(
                tracks = availableAudioTracks,
                selectedTrackIndex = selectedAudioTrackIndex,
                onTrackSelected = { trackIndex ->
                    scope.launch {
                        selectedAudioTrackIndex = trackIndex
                        audioTrackPrefsStore.saveTrackIndex(videoId, trackIndex)
                        // Note: Actual track switching would require engine restart
                        // which is beyond UI scope - user would need to re-open video
                    }
                    showAudioTrackSelector = false
                },
                onDismiss = { showAudioTrackSelector = false }
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

/**
 * OLED-optimized subtitle text with stroke outline.
 * Uses double-draw technique: stroke layer + fill layer for crisp edges.
 * Supports ASS/SSA styling: bold, italic, underline, color, font size.
 */
@Composable
fun OutlinedSubtitleText(
    text: String,
    fontSizeSp: Float,
    textColor: Color,
    outlineColor: Color,
    outlineWidthDp: Float,
    style: com.mxlite.app.subtitle.SubtitleStyle = com.mxlite.app.subtitle.SubtitleStyle(),
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        // Build text style with ASS styling applied
        val baseTextStyle = TextStyle(
            fontSize = fontSizeSp.sp,
            fontWeight = if (style.bold) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal,
            fontStyle = if (style.italic) androidx.compose.ui.text.font.FontStyle.Italic else androidx.compose.ui.text.font.FontStyle.Normal,
            textDecoration = if (style.underline) androidx.compose.ui.text.style.TextDecoration.Underline else null
        )
        
        // First pass: Draw text with stroke (outline)
        Text(
            text = text,
            color = outlineColor,
            textAlign = TextAlign.Center,
            maxLines = MaxSubtitleLines,
            style = baseTextStyle.copy(
                drawStyle = Stroke(
                    width = outlineWidthDp
                )
            ),
            modifier = Modifier.matchParentSize()
        )
        
        // Second pass: Draw text filled (on top of stroke)
        Text(
            text = text,
            color = textColor,
            textAlign = TextAlign.Center,
            maxLines = MaxSubtitleLines,
            style = baseTextStyle,
            modifier = Modifier.matchParentSize()
        )
    }
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

/**
 * Audio track selector dialog.
 * Displays available audio tracks with metadata and allows selection.
 */
@Composable
fun AudioTrackSelectorDialog(
    tracks: List<AudioTrackInfo>,
    selectedTrackIndex: Int?,
    onTrackSelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Audio Track") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                tracks.forEach { track ->
                    val isSelected = selectedTrackIndex == track.trackIndex
                    OutlinedButton(
                        onClick = { onTrackSelected(track.trackIndex) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = if (isSelected) {
                            ButtonDefaults.outlinedButtonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        } else {
                            ButtonDefaults.outlinedButtonColors()
                        }
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.Start
                        ) {
                            Text(
                                text = track.displayName,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = track.mimeType,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
                
                if (tracks.isEmpty()) {
                    Text(
                        text = "No audio tracks available",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}
