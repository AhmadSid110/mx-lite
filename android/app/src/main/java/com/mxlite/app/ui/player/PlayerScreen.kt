package com.mxlite.app.ui.player

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Matrix
import android.media.AudioManager
import android.net.Uri
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.documentfile.provider.DocumentFile
import com.mxlite.app.player.PlayerEngine
import com.mxlite.app.player.NativePlayer
import com.mxlite.app.subtitle.SubtitleController
import com.mxlite.app.subtitle.SubtitleCue
import com.mxlite.app.subtitle.SubtitlePrefsStore
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs

// ============================================================================================
// 🎨 DESIGN TOKENS (GLASS & PHYSICS)
// ============================================================================================

private const val ControlTimeoutMs = 3000L
private val VideoBg = Color.Black

private val GlassGradient = Brush.verticalGradient(
    listOf(Color(0xFF2B2B2B).copy(alpha = 0.85f), Color(0xFF1A1A1A).copy(alpha = 0.95f))
)
private val GlassBorder = Color.White.copy(alpha = 0.15f)
private val ScrimTop = Brush.verticalGradient(listOf(Color.Black.copy(alpha=0.6f), Color.Transparent))
private val ScrimBottom = Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha=0.7f)))

enum class OrientationMode { AUTO, PORTRAIT, LANDSCAPE }
enum class AspectMode { FIT, FILL, CROP, ORIGINAL, CUSTOM }

// ============================================================================================
// MAIN SCREEN
// ============================================================================================

@ExperimentalMaterial3Api
@Composable
fun PlayerScreen(
    uri: Uri,
    engine: PlayerEngine,
    onBack: () -> Unit,
    _internal: Boolean = true
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val audioManager = remember { context.getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager }

    // Surface & Texture
    var managedSurface by remember { mutableStateOf<android.view.Surface?>(null) }
    var managedTexture by remember { mutableStateOf<android.graphics.SurfaceTexture?>(null) }
    var textureView by remember { mutableStateOf<android.view.TextureView?>(null) }

    // State
    var controlsVisible by remember { mutableStateOf(true) }
    var isLocked by remember { mutableStateOf(false) }
    var lastInteractionTime by remember { mutableStateOf(System.currentTimeMillis()) }
    var showSettings by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }

    // Playback Data
    var isPlaying by remember { mutableStateOf(engine.isPlaying) }
    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }
    var videoWidth by remember { mutableStateOf(0) }
    var videoHeight by remember { mutableStateOf(0) }
    
    // Diagnostics
    var decoderName by remember { mutableStateOf("Unknown") }
    var outputFps by remember { mutableStateOf(0f) }
    var droppedFrames by remember { mutableStateOf(0) }
    var audioClockUs by remember { mutableStateOf(0L) }

    // User Overrides
    var orientationMode by remember { mutableStateOf(OrientationMode.AUTO) }
    var aspectMode by remember { mutableStateOf(AspectMode.FIT) }
    var customAspectX by remember { mutableStateOf(21f) }
    var customAspectY by remember { mutableStateOf(9f) }

    // Gestures
    var isDragging by remember { mutableStateOf(false) }
    var uiSeekPosition by remember { mutableStateOf(0f) }
    var seekPreviewTime by remember { mutableStateOf<Long?>(null) }
    var gestureAction by remember { mutableStateOf<String?>(null) }
    var gestureValue by remember { mutableStateOf(0f) }

    // Subtitles
    var subtitleLine by remember { mutableStateOf<SubtitleCue?>(null) }
    var subtitleController by remember { mutableStateOf<SubtitleController?>(null) }
    var subtitlesEnabled by remember { mutableStateOf(true) }
    var subtitleFontSizeSp by remember { mutableStateOf(18f) }
    
    // Init
    LaunchedEffect(uri) {
        try { subtitleController = SubtitleController(context) } catch (_: Exception) {}
    }

    BackHandler {
        if (showSettings) { showSettings = false; return@BackHandler }
        if (showDiagnostics) { showDiagnostics = false; return@BackHandler }
        if (isLocked) return@BackHandler
        onBack()
    }

    // 🔒 AUTO ORIENTATION
    LaunchedEffect(videoWidth, videoHeight, orientationMode) {
        if (videoWidth > 0 && videoHeight > 0) {
            when (orientationMode) {
                OrientationMode.AUTO -> {
                    val ratio = videoWidth.toFloat() / videoHeight
                    if (ratio >= 1.2f) activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    else activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                }
                OrientationMode.LANDSCAPE -> activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                OrientationMode.PORTRAIT -> activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            engine.stop()
            managedSurface?.release()
            managedTexture?.release()
        }
    }

    // ⏱ POLLING & AUTO-HIDE
    LaunchedEffect(Unit) {
        while (true) {
            isPlaying = engine.isPlaying
            if (!isDragging) {
                positionMs = engine.currentPositionMs
                durationMs = engine.durationMs
                uiSeekPosition = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
            }
            videoWidth = engine.videoWidth
            videoHeight = engine.videoHeight
            
            if (showDiagnostics) {
                decoderName = engine.decoderName
                outputFps = engine.outputFps
                droppedFrames = engine.droppedFrames
                audioClockUs = NativePlayer.virtualClockUs()
            }
            subtitleLine = subtitleController?.current(engine.currentPositionMs)

            if (controlsVisible && !isLocked && !showSettings && !showDiagnostics && isPlaying && 
                System.currentTimeMillis() - lastInteractionTime > ControlTimeoutMs) {
                controlsVisible = false
            }
            delay(200)
        }
    }

    // UPDATE TEXTURE
    fun updateTexture() {
        val tv = textureView ?: return
        val viewW = tv.width
        val viewH = tv.height
        if (videoWidth <= 0 || videoHeight <= 0 || viewW <= 0 || viewH <= 0) return

        val matrix = Matrix()
        val viewRatio = viewW.toFloat() / viewH
        val videoRatio = if (aspectMode == AspectMode.CUSTOM) customAspectX / customAspectY else videoWidth.toFloat() / videoHeight

        var scaleX = 1f
        var scaleY = 1f

        when (aspectMode) {
            AspectMode.FIT -> { }
            AspectMode.FILL, AspectMode.CROP -> {
                if (viewRatio > videoRatio) scaleY = (viewW / videoRatio) / viewH
                else scaleX = (viewH * videoRatio) / viewW
            }
            AspectMode.ORIGINAL -> {
                scaleX = videoWidth.toFloat() / viewW
                scaleY = videoHeight.toFloat() / viewH
            }
            AspectMode.CUSTOM -> {
                if (viewRatio > videoRatio) scaleX = (viewH * videoRatio) / viewW
                else scaleY = (viewW / videoRatio) / viewH
            }
        }
        matrix.setScale(scaleX, scaleY, viewW / 2f, viewH / 2f)
        tv.setTransform(matrix)
    }

    LaunchedEffect(aspectMode, customAspectX, customAspectY, videoWidth, videoHeight) { updateTexture() }


    // ============================================================================================
    // VIEW HIERARCHY (BOX -> VIDEO -> TOUCH -> CONTROLS)
    // ============================================================================================

    Box(
        modifier = Modifier.fillMaxSize().background(VideoBg)
    ) {
        // [1] VIDEO LAYER
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                android.view.TextureView(ctx).apply {
                    textureView = this
                    setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                    surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
                            st.setDefaultBufferSize(w, h)
                            if (managedTexture != st) {
                                managedSurface?.release()
                                managedSurface = android.view.Surface(st)
                                managedTexture = st
                            }
                            engine.attachSurface(managedSurface!!)
                            engine.play(uri)
                            updateTexture()
                        }
                        override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
                            st.setDefaultBufferSize(w, h)
                            updateTexture()
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

        // [2] GESTURE LAYER
        Box(
            modifier = Modifier.fillMaxSize()
                .pointerInput(isLocked) {
                    if (isLocked) return@pointerInput
                    detectTapGestures(
                        onTap = { 
                            lastInteractionTime = System.currentTimeMillis()
                            controlsVisible = !controlsVisible
                            if (!controlsVisible) { showSettings = false; showDiagnostics = false }
                        },
                        onDoubleTap = { /* Seek feedback or Fullscreen logic here */ }
                    )
                }
                .pointerInput(isLocked) {
                     if (isLocked) return@pointerInput
                     detectHorizontalDragGestures(
                        onDragStart = { 
                            isDragging = true; engine.onSeekStart(); lastInteractionTime = System.currentTimeMillis()
                        },
                        onDragEnd = {
                            if (durationMs > 0L) engine.onSeekCommit((uiSeekPosition * durationMs).toLong())
                            isDragging = false; seekPreviewTime = null
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
                .pointerInput(isLocked) {
                    if (isLocked) return@pointerInput
                    detectVerticalDragGestures(
                        onDragStart = { offset ->
                            lastInteractionTime = System.currentTimeMillis()
                            val width = size.width
                            if (offset.x < width / 2) {
                                gestureAction = "Brightness"
                                gestureValue = activity?.window?.attributes?.screenBrightness ?: 0.5f
                                if (gestureValue < 0) gestureValue = 0.5f
                            } else {
                                gestureAction = "Volume" 
                                val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                                gestureValue = current.toFloat() / max.toFloat()
                            }
                        },
                        onDragEnd = { gestureAction = null },
                        onVerticalDrag = { _, dragAmount ->
                            lastInteractionTime = System.currentTimeMillis()
                            val delta = -dragAmount / (size.height * 0.8f) 
                            gestureValue = (gestureValue + delta).coerceIn(0f, 1f)
                            if (gestureAction == "Brightness") {
                                val lp = activity?.window?.attributes
                                lp?.screenBrightness = gestureValue
                                activity?.window?.attributes = lp
                            } else {
                                val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                val target = (gestureValue * max).toInt()
                                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                            }
                        }
                    )
                }
        )

        // VISUAL FEEDBACK (Gestures)
        if (gestureAction != null) {
            Box(Modifier.align(Alignment.Center).background(Color.Black.copy(0.7f), RoundedCornerShape(16.dp)).padding(24.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(gestureAction!!, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    LinearProgressIndicator(progress = { gestureValue }, modifier = Modifier.padding(top=16.dp).width(120.dp).clip(RoundedCornerShape(4.dp)), color = Color.White)
                    Text("${(gestureValue * 100).toInt()}%", color = Color.White.copy(0.9f), modifier = Modifier.padding(top=8.dp))
                }
            }
        }
        
        if (isDragging && seekPreviewTime != null) {
            Box(Modifier.align(Alignment.Center).background(Color.Black.copy(0.7f), RoundedCornerShape(12.dp)).padding(16.dp)) {
                Text(formatTime(seekPreviewTime!!), color = Color.White, style = MaterialTheme.typography.headlineMedium)
            }
        }
        
        if (subtitlesEnabled) {
            subtitleLine?.let { cue ->
                Box(Modifier.align(Alignment.BottomCenter).padding(bottom = if(controlsVisible) 140.dp else 48.dp).padding(horizontal=32.dp)) {
                    OutlinedSubtitleText(cue.text, subtitleFontSizeSp, Color.White, Color.Black, 2f)
                }
            }
        }

        // [3] CONTROLS LAYER
        AnimatedVisibility(
            visible = controlsVisible || isLocked,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(Modifier.fillMaxSize()) {
                if (isLocked) {
                    GlassButton(
                        onClick = { isLocked = false }, 
                        icon = Icons.Rounded.Lock, 
                        modifier = Modifier.align(Alignment.CenterStart).padding(24.dp)
                    )
                } else {
                    // TOP BAR
                    Box(Modifier.fillMaxWidth().height(100.dp).align(Alignment.TopCenter).background(ScrimTop))
                    Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
                        Text(
                            text = DocumentFile.fromSingleUri(context, uri)?.name ?: "Video",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { isLocked = true }) { Icon(Icons.Rounded.LockOpen, "Lock", tint = Color.White) }
                        IconButton(onClick = { showSettings = !showSettings }) { Icon(Icons.Rounded.Settings, "Settings", tint = Color.White) }
                    }

                    // BOTTOM ZONE
                    Box(Modifier.fillMaxWidth().height(220.dp).align(Alignment.BottomCenter).background(ScrimBottom))
                    
                    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp)) {
                        
                        // TIME & SCRUBBER
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(formatTime(positionMs), color = Color.White.copy(0.8f))
                            Text(formatTime(durationMs), color = Color.White.copy(0.8f))
                        }
                        PhantomScrubber(
                            value = uiSeekPosition,
                            onValueChange = { pos ->
                                if (!isDragging) { isDragging = true; engine.onSeekStart() }
                                lastInteractionTime = System.currentTimeMillis()
                                uiSeekPosition = pos
                                engine.onSeekPreview((pos * durationMs).toLong())
                            },
                            onCommit = { engine.onSeekCommit((uiSeekPosition * durationMs).toLong()); isDragging = false }
                        )
                        
                        Spacer(Modifier.height(24.dp))
                        
                        // GLASS CONTROL DECK (FLOATING PILL)
                        Box(
                            Modifier.align(Alignment.CenterHorizontally)
                                .clip(RoundedCornerShape(32.dp))
                                .background(GlassGradient)
                                .border(1.dp, GlassBorder, RoundedCornerShape(32.dp))
                                .padding(horizontal = 24.dp, vertical = 12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                                
                                // Aspect Ratio
                                IconButton(onClick = { 
                                    val modes = AspectMode.values()
                                    aspectMode = modes[(aspectMode.ordinal + 1) % modes.size]
                                }) { Icon(Icons.Rounded.AspectRatio, "Aspect", tint = Color.White) }

                                // Prev
                                SpringIconButton(onClick = { 
                                     engine.seekTo((engine.currentPositionMs - 10000).coerceAtLeast(0))
                                }, icon = Icons.Rounded.Replay10)

                                // Play/Pause (Large)
                                SpringIconButton(
                                    onClick = { 
                                        lastInteractionTime = System.currentTimeMillis()
                                        if (isPlaying) engine.pause() else engine.resume()
                                    }, 
                                    icon = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    size = 48.dp
                                )

                                // Next
                                SpringIconButton(onClick = { 
                                    engine.seekTo((engine.currentPositionMs + 10000).coerceAtMost(durationMs)) 
                                }, icon = Icons.Rounded.Forward10)

                                // Diagnostics
                                IconButton(onClick = { showDiagnostics = !showDiagnostics }) { 
                                    Icon(Icons.Rounded.Info, "Infos", tint = Color.White.copy(0.7f)) 
                                }
                            }
                        }
                    }
                }
            }
        }

        // [4] OVERLAYS (DIAGNOSTICS & SETTINGS)
        
        // Settings (Vertical Slide Up)
        AnimatedVisibility(
            visible = showSettings,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter) // Bottom Sheet Style
        ) {
             Box(Modifier.fillMaxSize().background(Color.Black.copy(0.4f)).clickable{showSettings=false}, contentAlignment = Alignment.BottomCenter) {
                 Column(
                     Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart=24.dp, topEnd=24.dp))
                        .background(GlassGradient)
                        .border(1.dp, GlassBorder, RoundedCornerShape(topStart=24.dp, topEnd=24.dp))
                        .clickable(enabled=false){}
                        .padding(32.dp)
                 ) {
                     Text("Settings", style = MaterialTheme.typography.headlineMedium, color = Color.White)
                     Spacer(Modifier.height(24.dp))
                     
                     SettingRow("Orientation") {
                         OrientationMode.values().forEach { mode ->
                             FilterChip(
                                selected = orientationMode == mode, onClick = { orientationMode = mode },
                                label = { Text(mode.name) },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor=Color.White, containerColor=Color.Transparent, labelColor=Color.White, selectedLabelColor=Color.Black)
                             )
                         }
                     }
                     SettingRow("Aspect Ratio") {
                         AspectMode.values().take(4).forEach { mode ->
                              FilterChip(
                                selected = aspectMode == mode, onClick = { aspectMode = mode },
                                label = { Text(mode.name) },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor=Color.White, containerColor=Color.Transparent, labelColor=Color.White, selectedLabelColor=Color.Black)
                             )
                         }
                     }
                     // Custom Aspect Input
                     Row(verticalAlignment=Alignment.CenterVertically) {
                         Text("Custom:", color=Color.Gray, modifier=Modifier.padding(end=8.dp))
                         // Simple preset toggler for now as TextFields are tricky in this overlay
                         FilterChip(selected = aspectMode == AspectMode.CUSTOM && customAspectX == 21f, onClick={ aspectMode=AspectMode.CUSTOM; customAspectX=21f; customAspectY=9f}, label={Text("21:9")}, colors=FilterChipDefaults.filterChipColors(selectedContainerColor=Color.White, containerColor=Color.Transparent, labelColor=Color.White, selectedLabelColor=Color.Black))
                         Spacer(Modifier.width(8.dp))
                         FilterChip(selected = aspectMode == AspectMode.CUSTOM && customAspectX == 4f, onClick={ aspectMode=AspectMode.CUSTOM; customAspectX=4f; customAspectY=3f}, label={Text("4:3")}, colors=FilterChipDefaults.filterChipColors(selectedContainerColor=Color.White, containerColor=Color.Transparent, labelColor=Color.White, selectedLabelColor=Color.Black))
                     }
                 }
             }
        }
        
        // Diagnostics (Right Slide)
        AnimatedVisibility(
            visible = showDiagnostics,
            enter = slideInHorizontally { it } + fadeIn(),
            exit = slideOutHorizontally { it } + fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            Box(Modifier.width(300.dp).fillMaxHeight().background(GlassGradient).padding(24.dp).clickable(enabled=false){}) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Diagnostics", style=MaterialTheme.typography.titleLarge, color=Color.White)
                    HorizontalDivider(color=Color.White.copy(0.2f))
                    DiagnosticItem("Resolution", "${videoWidth}x${videoHeight} (${getResolutionLabel(videoHeight)})")
                    DiagnosticItem("Decoder", decoderName)
                    DiagnosticItem("Type", if(decoderName.startsWith("c2.android")) "Software" else "Hardware")
                    DiagnosticItem("FPS", String.format("%.1f", outputFps))
                    DiagnosticItem("Drops", droppedFrames.toString())
                    DiagnosticItem("Audio Clock", "${audioClockUs/1000} ms")
                    DiagnosticItem("Audio Codec", "Native / AAudio") // Placeholder
                }
            }
        }
    }
}

// ============================================================================================
// HELPER COMPONENTS
// ============================================================================================

@Composable
fun PhantomScrubber(value: Float, onValueChange: (Float) -> Unit, onCommit: () -> Unit) {
    var isHovering by remember { mutableStateOf(false) } // Touch down
    val height by animateDpAsState(if (isHovering) 12.dp else 4.dp, label = "scrubberHeight")
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp) // Large touch target
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = { 
                        isHovering = true
                        tryAwaitRelease()
                        isHovering = false 
                        onCommit()
                    }
                )
            }
            .pointerInput(Unit) {
                 detectHorizontalDragGestures(
                    onDragStart = { isHovering = true },
                    onDragEnd = { isHovering = false; onCommit() },
                    onHorizontalDrag = { change, _ ->
                        val newPos = (change.position.x / size.width).coerceIn(0f, 1f)
                        onValueChange(newPos)
                    }
                )
            },
        contentAlignment = Alignment.CenterStart
    ) {
        // Track
        Box(Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(4.dp)).background(Color.White.copy(0.3f)))
        // Fill
        Box(Modifier.fillMaxWidth(value).height(height).clip(RoundedCornerShape(4.dp)).background(Color.Red)) // Netflix Red
    }
}

@Composable
fun SpringIconButton(
    onClick: () -> Unit, 
    icon: androidx.compose.ui.graphics.vector.ImageVector, 
    size: Dp = 24.dp
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) 0.8f else 1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow), label = "spring")
    
    IconButton(onClick = onClick, interactionSource = interactionSource) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(size).graphicsLayer {
            scaleX = scale
            scaleY = scale
        })
    }
}

@Composable
fun GlassButton(onClick: () -> Unit, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier) {
    Box(modifier.background(Color.White.copy(0.1f), CircleShape).clickable(onClick=onClick).padding(12.dp)) {
        Icon(icon, null, tint = Color.White)
    }
}

@Composable
fun SettingRow(label: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(vertical=8.dp)) {
        Text(label, color = Color.Gray, style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth().padding(top=8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            content()
        }
    }
}

@Composable
fun DiagnosticItem(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color.LightGray)
        Text(value, color = Color.White, fontWeight = FontWeight.Bold)
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return java.lang.String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
}

private fun getResolutionLabel(height: Int): String {
    return when {
        height >= 2160 -> "4K"
        height >= 1440 -> "1440p"
        height >= 1080 -> "1080p"
        height >= 720 -> "720p"
        height >= 480 -> "480p"
        else -> "SD"
    }
}

@Composable
fun OutlinedSubtitleText(text: String, fontSizeSp: Float, textColor: Color, outlineColor: Color, outlineWidthDp: Float) {
    Box {
        Text(text = text, color = outlineColor, textAlign = TextAlign.Center, fontSize = fontSizeSp.sp, style = TextStyle(drawStyle = androidx.compose.ui.graphics.drawscope.Stroke(width = outlineWidthDp)))
        Text(text = text, color = textColor, textAlign = TextAlign.Center, fontSize = fontSizeSp.sp)
    }
}