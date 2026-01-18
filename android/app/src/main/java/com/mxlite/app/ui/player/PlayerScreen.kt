package com.mxlite.app.ui.player

import android.net.Uri
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.viewinterop.AndroidView
import androidx.documentfile.provider.DocumentFile
import com.mxlite.app.player.PlayerEngine
import com.mxlite.app.player.AudioTrackInfo
import com.mxlite.app.player.AudioTrackExtractor
import com.mxlite.app.player.AudioTrackPrefsStore
import com.mxlite.app.player.CodecInfoController
import com.mxlite.app.player.TrackCodecInfo
import com.mxlite.app.player.CodecCapability
// removed native debug polling; using AudioDebugOverlay composable
import com.mxlite.app.subtitle.SubtitleController
import com.mxlite.app.subtitle.SubtitleCue
import com.mxlite.app.subtitle.SubtitlePrefsStore
import com.mxlite.app.subtitle.SubtitleTrack
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

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
@Suppress("UNUSED_PARAMETER")
@Composable
fun PlayerScreen(
    uri: android.net.Uri,
    engine: PlayerEngine,
    onBack: () -> Unit,
    _internal: Boolean = true
) {
    BackHandler(enabled = true) {
        onBack()
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val prefsStore = remember { SubtitlePrefsStore(context) }
    val audioTrackPrefsStore = remember { AudioTrackPrefsStore(context) }
    val videoId = remember(uri) { SubtitlePrefsStore.videoIdFromUri(uri) }

    val videoDisplayName = remember(uri) {
        DocumentFile.fromSingleUri(context, uri)?.name ?: uri.lastPathSegment ?: "Video"
    }

    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }
    var controlsVisible by remember { mutableStateOf(true) }
    var subtitleLine by remember { mutableStateOf<SubtitleCue?>(null) }
    var playbackStarted by remember { mutableStateOf(false) }

    // 🔒 ROOT CAUSE #1: Persistent Surface identity
    var managedSurface by remember { mutableStateOf<android.view.Surface?>(null) }
    var managedTexture by remember { mutableStateOf<android.graphics.SurfaceTexture?>(null) }

    var dragging by remember { mutableStateOf(false) }
    var sliderPos by remember { mutableStateOf(0f) }

    var subtitleController by remember { mutableStateOf<SubtitleController?>(null) }
    var subtitleOffsetMs by remember { mutableStateOf(0L) }
    var subtitlesEnabled by remember { mutableStateOf(true) }
    var subtitleFontSizeSp by remember { mutableStateOf(18f) }
    var subtitleBgOpacity by remember { mutableStateOf(0.6f) }
    var subtitleColor by remember { mutableStateOf(Color.White) }
    var subtitleBottomMargin by remember { mutableStateOf(48f) }
    var subtitleError by remember { mutableStateOf<String?>(null) }
    var selectedTrackId by remember { mutableStateOf<String?>(null) }
    
    // Audio track selection state
    var availableAudioTracks by remember { mutableStateOf<List<AudioTrackInfo>>(emptyList()) }
    var selectedAudioTrackIndex by remember { mutableStateOf<Int?>(null) }
    var showAudioTrackSelector by remember { mutableStateOf(false) }
    
    // Codec info state
    var showCodecInfo by remember { mutableStateOf(false) }
    var codecInfoList by remember { mutableStateOf<List<Pair<TrackCodecInfo, CodecCapability>>>(emptyList()) }
    var showUnsupportedCodecWarning by remember { mutableStateOf(false) }
    var unsupportedCodecs by remember { mutableStateOf<List<String>>(emptyList()) }
    
    // Native audio debug now displayed by AudioDebugOverlay

    val subtitlePicker =
        rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { pickedUri: Uri? ->
            if (pickedUri != null) {
                scope.launch {
                    val docFile = DocumentFile.fromSingleUri(context, pickedUri)
                    val track = SubtitleTrack.SafTrack(
                        uri = pickedUri,
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
    LaunchedEffect(uri) {
        // Load saved preferences
        val savedPrefs = prefsStore.load(videoId)
        subtitlesEnabled = savedPrefs.enabled
        subtitleOffsetMs = savedPrefs.offsetMs
        subtitleFontSizeSp = savedPrefs.fontSizeSp
        subtitleColor = savedPrefs.textColor
        subtitleBgOpacity = savedPrefs.bgOpacity
        subtitleBottomMargin = savedPrefs.bottomMarginDp
        selectedTrackId = savedPrefs.selectedTrackId

        // Use a ParcelFileDescriptor to query track metadata without creating temp files
        try {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return@LaunchedEffect

            // IMPORTANT: We open a dedicated PFD here for metadata queries and
            // close it immediately after. We MUST NOT close or affect the FD
            // that MediaCodecEngine uses for playback (the engine opens its own PFD).

            // Load available audio tracks from FD (do NOT close the fd inside)
            availableAudioTracks = AudioTrackExtractor.extractAudioTracks(pfd.fileDescriptor)
            selectedAudioTrackIndex = audioTrackPrefsStore.loadTrackIndex(videoId)

            // Extract codec information (only once)
            codecInfoList = CodecInfoController.getFileCodecInfo(pfd.fileDescriptor)

            // Filter for unsupported codecs from existing result
            unsupportedCodecs = codecInfoList
                .filter { !it.second.isSupported }
                .map { it.first.mimeType }

            // Show warning if unsupported codecs detected
            if (unsupportedCodecs.isNotEmpty()) {
                showUnsupportedCodecWarning = true
            }

            // Close our local PFD - the engine owns its PFD separately
            pfd.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Initialize subtitle controller (no auto-loading of sidecar files for URIs)
        subtitleController = SubtitleController(context)
        subtitleLine = null
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

    // ⏱ Poll engine clock
    LaunchedEffect(Unit) {
        while (true) {
            if (!dragging) {
                val currentPos = engine.currentPositionMs
                val currentDur = engine.durationMs
                
                positionMs = currentPos
                durationMs = currentDur
                
                if (currentDur > 0) {
                    sliderPos = currentPos.toFloat() / currentDur
                }
            }
            val effectiveTimeMs = (engine.currentPositionMs + subtitleOffsetMs).coerceAtLeast(0L)
            subtitleLine = subtitleController?.current(effectiveTimeMs)
            delay(500)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            playbackStarted = false
            engine.release()
            
            // 🔒 Final manual release of owned resources
            managedSurface?.release()
            managedTexture?.release()
        }
    }

    Column(modifier = Modifier.fillMaxSize().focusable()) {

        if (controlsVisible) {
            TopAppBar(
                title = { Text(videoDisplayName) },
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
                    .fillMaxSize()
                    .zIndex(1f)
                    .focusable(false)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { controlsVisible = !controlsVisible },
                            onDoubleTap = { offset ->
                                val half = size.width / 2
                                val delta = if (offset.x < half.toFloat()) -10_000L else 10_000L
                                engine.seekTo(
                                    (engine.currentPositionMs + delta)
                                        .coerceIn(0, engine.durationMs)
                                )
                            }
                        )
                    },
                factory = { ctx ->
                    android.view.TextureView(ctx).apply {
                        // 🔒 ROOT CAUSE #3: Forced HW Acceleration
                        setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                        
                        surfaceTextureListener =
                            object : android.view.TextureView.SurfaceTextureListener {

                                override fun onSurfaceTextureAvailable(
                                    surfaceTexture: android.graphics.SurfaceTexture,
                                    width: Int,
                                    height: Int
                                ) {
                                    // 🛡️ Rule 2: Non-zero size guard
                                    check(width > 0 && height > 0) { "TextureView size is ZERO" }

                                    surfaceTexture.setDefaultBufferSize(width, height)
                                    if (managedTexture != surfaceTexture) {
                                        managedSurface?.release()
                                        managedSurface = android.view.Surface(surfaceTexture)
                                        managedTexture = surfaceTexture
                                    }
                                    engine.attachSurface(managedSurface!!)

                                    engine.play(uri)
                                }

                                override fun onSurfaceTextureSizeChanged(
                                    surface: android.graphics.SurfaceTexture,
                                    width: Int,
                                    height: Int
                                ) {
                                    surface.setDefaultBufferSize(width, height)
                                }

                                override fun onSurfaceTextureDestroyed(
                                    st: android.graphics.SurfaceTexture
                                ): Boolean {
                                    engine.detachSurface()
                                    // 🔒 ROOT CAUSE #2: Explicit ownership
                                    return false 
                                }

                                override fun onSurfaceTextureUpdated(
                                    surface: android.graphics.SurfaceTexture
                                ) = Unit
                            }
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
                            fontSizeSp = subtitleFontSizeSp,
                            textColor = subtitleColor,
                            outlineColor = Color.Black,
                            outlineWidthDp = 3f,
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
            
            // Debug overlay - always visible at top-left
            AudioDebugOverlay(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp)
            )

            // ENGINE STATE OVERLAY (TEMP) — shows playback state on-screen
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.01f))
            ) {
                Text(
                    text = buildString {
                        appendLine("ENGINE PLAYING: ${engine.isPlaying}")
                        appendLine("DURATION: ${engine.durationMs}")
                        appendLine("POSITION: ${engine.currentPositionMs}")
                    },
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .background(Color.Black.copy(alpha = 0.7f))
                        .padding(16.dp)
                )
            }
        }

        if (controlsVisible) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {

                val displayDuration = durationMs.coerceAtLeast(1L)

                Slider(
                    value = sliderPos,
                    onValueChange = {
                        if (!dragging) {
                            dragging = true
                            engine.onSeekStart()
                        }
                        sliderPos = it
                        val seekMs = (sliderPos * displayDuration).toLong()
                        engine.onSeekPreview(seekMs)
                    },
                    onValueChangeFinished = {
                        val seekMs = (sliderPos * displayDuration).toLong()
                        engine.onSeekCommit(seekMs)
                        dragging = false
                    }
                )

                // Removed conflicting LaunchedEffect; sliderPos is now updated in the polling loop


                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "${formatTime(positionMs)} / ${formatTime(durationMs)}",
                    style = MaterialTheme.typography.bodyMedium
                )

                Spacer(modifier = Modifier.height(12.dp))

                IconButton(
                    onClick = {
                        if (engine.isPlaying) {
                            engine.pause()
                        } else {
                            engine.resume()
                        }
                    }
                ) {
                    Icon(
                        imageVector = if (engine.isPlaying)
                            Icons.Filled.Pause
                        else
                            Icons.Filled.PlayArrow,
                        contentDescription = null
                    )
                }

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
                
                // Codec Info Section
                Spacer(modifier = Modifier.height(12.dp))
                
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        Text("Codec Info", style = MaterialTheme.typography.titleSmall)
                        if (unsupportedCodecs.isNotEmpty()) {
                            Text(
                                text = "⚠️ ${unsupportedCodecs.size} unsupported codec(s)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    OutlinedButton(onClick = { showCodecInfo = true }) {
                        Text("View")
                    }
                }
            }
        }
        
        // Codec Info Dialog
        if (showCodecInfo) {
            CodecInfoDialog(
                codecInfo = codecInfoList,
                onDismiss = { showCodecInfo = false }
            )
        }
        
        // Unsupported Codec Warning Dialog
        if (showUnsupportedCodecWarning) {
            UnsupportedCodecWarningDialog(
                unsupportedCodecs = unsupportedCodecs,
                onDismiss = { showUnsupportedCodecWarning = false },
                onViewDetails = { 
                    showUnsupportedCodecWarning = false
                    showCodecInfo = true
                }
            )
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
 */
@Composable
fun OutlinedSubtitleText(
    text: String,
    fontSizeSp: Float,
    textColor: Color,
    outlineColor: Color,
    outlineWidthDp: Float,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        // First pass: Draw text with stroke (outline)
        Text(
            text = text,
            color = outlineColor,
            textAlign = TextAlign.Center,
            fontSize = fontSizeSp.sp,
            maxLines = MaxSubtitleLines,
            style = TextStyle(
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
            fontSize = fontSizeSp.sp,
            maxLines = MaxSubtitleLines,
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

/**
 * Codec Info Dialog.
 * Displays comprehensive codec information for all tracks in the media file.
 * Shows video and audio codec support, decoder names, and hardware/software status.
 */
@Composable
fun CodecInfoDialog(
    codecInfo: List<Pair<TrackCodecInfo, CodecCapability>>,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Codec Information") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (codecInfo.isEmpty()) {
                    Text(
                        text = "No codec information available",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp)
                    )
                } else {
                    // Group by track type
                    val videoTracks = codecInfo.filter { 
                        it.first.trackType == TrackCodecInfo.TrackType.VIDEO 
                    }
                    val audioTracks = codecInfo.filter { 
                        it.first.trackType == TrackCodecInfo.TrackType.AUDIO 
                    }
                    
                    // Video Codecs Section
                    if (videoTracks.isNotEmpty()) {
                        Text(
                            text = "Video Codecs",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        
                        videoTracks.forEach { (track, capability) ->
                            CodecInfoItem(track = track, capability = capability)
                        }
                    }
                    
                    // Audio Codecs Section
                    if (audioTracks.isNotEmpty()) {
                        if (videoTracks.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        
                        Text(
                            text = "Audio Codecs",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        
                        audioTracks.forEach { (track, capability) ->
                            CodecInfoItem(track = track, capability = capability)
                        }
                    }
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

/**
 * Individual codec info item display.
 * Shows codec details including support status, decoder name, and type.
 */
@Composable
fun CodecInfoItem(
    track: TrackCodecInfo,
    capability: CodecCapability
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (capability.isSupported) 
                MaterialTheme.colorScheme.surfaceVariant
            else 
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = track.displayMimeType.uppercase(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                
                Text(
                    text = if (capability.isSupported) "✓ Supported" else "✗ Not Supported",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (capability.isSupported) 
                        MaterialTheme.colorScheme.primary
                    else 
                        MaterialTheme.colorScheme.error
                )
            }
            
            if (capability.isSupported && capability.decoderName != null) {
                Text(
                    text = "Decoder: ${capability.decoderName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Text(
                    text = "Type: ${capability.displayDecoderType}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (!capability.isSupported) {
                Text(
                    text = "No decoder available for this codec",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/**
 * Unsupported Codec Warning Dialog.
 * Shown before playback if unsupported codecs are detected.
 * Explains which codecs failed and why.
 */
@Composable
fun UnsupportedCodecWarningDialog(
    unsupportedCodecs: List<String>,
    onDismiss: () -> Unit,
    onViewDetails: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Text("⚠️", fontSize = 32.sp)
        },
        title = { 
            Text("Unsupported Codecs Detected") 
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "This media file contains ${unsupportedCodecs.size} codec(s) that may not be playable on this device:",
                    style = MaterialTheme.typography.bodyMedium
                )
                
                unsupportedCodecs.forEach { codec ->
                    Text(
                        text = "• ${codec.removePrefix("video/").removePrefix("audio/")}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = "Playback may fail or show a black screen. Consider converting the file to a supported format.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onViewDetails) {
                Text("View Details")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Continue Anyway")
            }
        }
    )
}