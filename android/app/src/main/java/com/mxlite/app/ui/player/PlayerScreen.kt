package com.mxlite.app.ui.player

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.documentfile.provider.DocumentFile
import com.mxlite.app.player.AudioTrackExtractor
import com.mxlite.app.player.AudioTrackInfo
import com.mxlite.app.player.AudioTrackPrefsStore
import com.mxlite.app.player.CodecCapability
import com.mxlite.app.player.CodecInfoController
import com.mxlite.app.player.PlayerEngine
import com.mxlite.app.player.TrackCodecInfo
import com.mxlite.app.subtitle.SubtitleController
import com.mxlite.app.subtitle.SubtitleCue
import com.mxlite.app.subtitle.SubtitlePrefsStore
import kotlinx.coroutines.delay
import java.util.Locale

// ============================================================================================
// CONSTANTS & CONFIG
// ============================================================================================

private const val ControlTimeoutMs = 3000L
private val PlayerBlack = Color(0xFF000000)
private val ControlsBg = Color.Black.copy(alpha = 0.6f)
private val IconActive = Color.White.copy(alpha = 0.9f)
private val IconInactive = Color.White.copy(alpha = 0.6f)

// ============================================================================================
// MAIN SCREEN
// ============================================================================================

@Composable
fun PlayerScreen(
    uri: Uri,
    engine: PlayerEngine,
    onBack: () -> Unit,
    _internal: Boolean = true
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()

    // 🔒 ROOT CAUSE #1: Persistent Surface identity
    var managedSurface by remember { mutableStateOf<android.view.Surface?>(null) }
    var managedTexture by remember { mutableStateOf<android.graphics.SurfaceTexture?>(null) }

    // State
    var controlsVisible by remember { mutableStateOf(true) }
    var isLocked by remember { mutableStateOf(false) }
    var lastInteractionTime by remember { mutableStateOf(System.currentTimeMillis()) }
    
    // Playback State
    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    
    // Seek / Gesture State
    var isDragging by remember { mutableStateOf(false) }
    var uiSeekPosition by remember { mutableStateOf(0f) }
    var seekPreviewTime by remember { mutableStateOf<Long?>(null) }
    
    // Subtitle / Track Data
    var subtitleLine by remember { mutableStateOf<SubtitleCue?>(null) }
    var subtitleController by remember { mutableStateOf<SubtitleController?>(null) }
    val subPrefsStore = remember { SubtitlePrefsStore(context) }
    val videoId = remember(uri) { SubtitlePrefsStore.videoIdFromUri(uri) }
    
    var subtitlesEnabled by remember { mutableStateOf(true) }
    var subtitleOffsetMs by remember { mutableStateOf(0L) }
    var subtitleFontSizeSp by remember { mutableStateOf(18f) }
    var subtitleColor by remember { mutableStateOf(Color.White) }
    var codecInfoList by remember { mutableStateOf<List<Pair<TrackCodecInfo, CodecCapability>>>(emptyList()) }

    // Back Handler
    BackHandler {
        if (isLocked) return@BackHandler 
        onBack()
    }

    // 🔒 SCREEN ORIENTATION & FULLSCREEN
    DisposableEffect(Unit) {
        activity?.lockLandscape()
        onDispose {
            activity?.unlockOrientation()
            engine.release()
            managedSurface?.release()
            managedTexture?.release()
        }
    }

    // ⏱ POLLING LOOP (Clock owns time)
    LaunchedEffect(Unit) {
        while (true) {
            isPlaying = engine.isPlaying
            if (!isDragging) {
                positionMs = engine.currentPositionMs
                durationMs = engine.durationMs
                uiSeekPosition = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
            }
            val effectiveTimeMs = (engine.currentPositionMs + subtitleOffsetMs).coerceAtLeast(0L)
            subtitleLine = subtitleController?.current(effectiveTimeMs)
            if (controlsVisible && !isLocked && 
                System.currentTimeMillis() - lastInteractionTime > ControlTimeoutMs) {
                controlsVisible = false
            }
            delay(200)
        }
    }

    // Initialization
    LaunchedEffect(uri) {
        try {
            val prefs = subPrefsStore.load(videoId)
            subtitlesEnabled = prefs.enabled
            subtitleOffsetMs = prefs.offsetMs
            subtitleFontSizeSp = prefs.fontSizeSp
            subtitleColor = prefs.textColor
            subtitleController = SubtitleController(context)
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                codecInfoList = CodecInfoController.getFileCodecInfo(pfd.fileDescriptor)
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    // ============================================================================================
    // UI LAYOUT
    // ============================================================================================

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PlayerBlack)
            .focusable()
    ) {
        // 1. VIDEO SURFACE
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                android.view.TextureView(ctx).apply {
                    setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                    surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
                            if (w <= 0 || h <= 0) return
                            st.setDefaultBufferSize(w, h)
                            if (managedTexture != st) {
                                managedSurface?.release()
                                managedSurface = android.view.Surface(st)
                                managedTexture = st
                            }
                            engine.attachSurface(managedSurface!!)
                            engine.play(uri)
                        }
                        override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
                            st.setDefaultBufferSize(w, h)
                        }
                        override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean {
                            engine.detachSurface()
                            return false
                        }
                        override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) = Unit
                    }
                }
            }
        )
        
        // 2. GESTURE & TAP OVERLAY
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { 
                            lastInteractionTime = System.currentTimeMillis()
                            controlsVisible = !controlsVisible 
                        },
                        onDoubleTap = { offset ->
                            if (!isLocked) {
                                lastInteractionTime = System.currentTimeMillis()
                                val half = size.width / 2
                                val delta = if (offset.x < half) -10_000L else 10_000L
                                engine.seekTo((engine.currentPositionMs + delta).coerceIn(0, engine.durationMs))
                            }
                        }
                    )
                }
                .pointerInput(isLocked) {
                     if (!isLocked) {
                        detectHorizontalDragGestures(
                            onDragStart = { 
                                isDragging = true
                                engine.onSeekStart()
                                lastInteractionTime = System.currentTimeMillis()
                            },
                            onDragEnd = {
                                if (durationMs > 0L) {
                                    val targetMs = (uiSeekPosition * durationMs).toLong()
                                    engine.onSeekCommit(targetMs)
                                }
                                isDragging = false
                                seekPreviewTime = null
                            },
                            onHorizontalDrag = { _, dragAmount ->
                                lastInteractionTime = System.currentTimeMillis()
                                if (durationMs > 0) {
                                    val delta = dragAmount / size.width
                                    uiSeekPosition = (uiSeekPosition + delta).coerceIn(0f, 1f)
                                    val targetMs = (uiSeekPosition * durationMs).toLong()
                                    seekPreviewTime = targetMs
                                    engine.onSeekPreview(targetMs)
                                }
                            }
                        )
                     }
                }
        )

        // 3. SUBTITLES
        if (subtitlesEnabled) {
            subtitleLine?.let { cue ->
                Box(
                    modifier = Modifier.align(Alignment.BottomCenter)
                        .padding(bottom = if (controlsVisible) 110.dp else 32.dp)
                        .padding(horizontal = 32.dp)
                ) {
                    OutlinedSubtitleText(
                        text = cue.text,
                        fontSizeSp = subtitleFontSizeSp,
                        textColor = subtitleColor,
                        outlineColor = Color.Black,
                        outlineWidthDp = 2f
                    )
                }
            }
        }
        
        // 4. SEEK PREVIEW TIME
        if (isDragging && seekPreviewTime != null) {
            Box(
                modifier = Modifier.align(Alignment.Center)
                    .background(Color.Black.copy(alpha=0.7f), RoundedCornerShape(8.dp))
                    .padding(16.dp)
            ) {
                Text(text = formatTime(seekPreviewTime!!), color = Color.White, style = MaterialTheme.typography.headlineMedium)
            }
        }

        // 5. DEBUG / AUDIO OVERLAY 
        AudioDebugOverlay(
            engine = engine,
            hasSurface = managedSurface != null && managedSurface!!.isValid,
            modifier = Modifier.align(Alignment.TopStart).padding(16.dp)
        )

        // 6. CONTROL BARS
        AnimatedVisibility(
            visible = controlsVisible || isLocked,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (isLocked) {
                    IconButton(
                        onClick = { isLocked = false },
                        modifier = Modifier.align(Alignment.CenterStart).padding(24.dp).background(ControlsBg, CircleShape)
                    ) {
                        Icon(Icons.Rounded.Lock, "Unlock", tint = MaterialTheme.colorScheme.primary)
                    }
                } else {
                    // TOP BAR
                    Row(
                        modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().background(ControlsBg).padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = IconActive) }
                        Text(
                            text = DocumentFile.fromSingleUri(context, uri)?.name ?: "Video",
                            style = MaterialTheme.typography.titleMedium, color = IconActive,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                        )
                        IconButton(onClick = { isLocked = true }) { Icon(Icons.Rounded.LockOpen, "Lock", tint = IconInactive) }
                    }

                    // CENTER
                    Row(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalArrangement = Arrangement.spacedBy(48.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { engine.seekTo((engine.currentPositionMs - 10000).coerceAtLeast(0)) }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Rounded.Replay10, "-10s", tint = IconActive, modifier = Modifier.fillMaxSize())
                        }
                        Box(
                            modifier = Modifier.size(72.dp).background(IconActive, CircleShape).clickable {
                                lastInteractionTime = System.currentTimeMillis()
                                if (isPlaying) engine.pause() else engine.resume()
                            },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = null, tint = Color.Black, modifier = Modifier.size(48.dp))
                        }
                        IconButton(onClick = { engine.seekTo((engine.currentPositionMs + 10000).coerceAtMost(durationMs)) }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Rounded.Forward10, "+10s", tint = IconActive, modifier = Modifier.fillMaxSize())
                        }
                    }

                    // BOTTOM BAR
                    Column(
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(ControlsBg).padding(16.dp)
                    ) {
                        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(formatTime(positionMs), color = IconActive, style = MaterialTheme.typography.labelMedium)
                            Text(formatTime(durationMs), color = IconActive, style = MaterialTheme.typography.labelMedium)
                        }
                        Slider(
                            value = uiSeekPosition,
                            onValueChange = { pos ->
                                if (!isDragging) { isDragging = true; engine.onSeekStart() }
                                lastInteractionTime = System.currentTimeMillis()
                                uiSeekPosition = pos
                                engine.onSeekPreview((pos * durationMs).toLong())
                            },
                            onValueChangeFinished = {
                                engine.onSeekCommit((uiSeekPosition * durationMs).toLong())
                                isDragging = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return java.lang.String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
}

@Composable
fun OutlinedSubtitleText(text: String, fontSizeSp: Float, textColor: Color, outlineColor: Color, outlineWidthDp: Float) {
    Box {
        Text(text = text, color = outlineColor, textAlign = TextAlign.Center, fontSize = fontSizeSp.sp, style = TextStyle(drawStyle = Stroke(width = outlineWidthDp)))
        Text(text = text, color = textColor, textAlign = TextAlign.Center, fontSize = fontSizeSp.sp)
    }
}