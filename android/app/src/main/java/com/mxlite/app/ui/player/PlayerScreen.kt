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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
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

// 📐 CANONICAL ASPECT RATIO INTERFACE
sealed interface AspectRatio {
    val label: String
    data object Fit : AspectRatio { override val label = "Fit" }
    data object Fill : AspectRatio { override val label = "Fill" }
    data object Crop : AspectRatio { override val label = "Crop" }
    data object Original : AspectRatio { override val label = "Original" }
    data object Stretch : AspectRatio { override val label = "Stretch" }
    data class Custom(val w: Float, val h: Float) : AspectRatio {
        override val label = if (h == 1f) "$w" else "${w.toInt()}:${h.toInt()}"
        companion object {
            val SixteenNine = Custom(16f, 9f)
            val FourThree = Custom(4f, 3f)
            val TwentyOneNine = Custom(21f, 9f)
        }
    }
}

fun parseAspectRatio(input: String): AspectRatio {
    val raw = input.trim().lowercase()
    return when(raw) {
        "fit" -> AspectRatio.Fit
        "fill" -> AspectRatio.Fill
        "crop" -> AspectRatio.Crop
        "original", "orig" -> AspectRatio.Original
        "stretch" -> AspectRatio.Stretch
        "auto" -> AspectRatio.Fit
        else -> {
            // 1. Colon (16:9)
            if (raw.contains(":")) {
                val parts = raw.split(":")
                if (parts.size == 2) {
                    val w = parts[0].toFloatOrNull()
                    val h = parts[1].toFloatOrNull()
                    if (w != null && h != null && h != 0f) return AspectRatio.Custom(w, h)
                }
            }
            // 2. Resolution (1920x1080)
            if (raw.contains("x")) {
                val parts = raw.split("x")
                if (parts.size == 2) {
                    val w = parts[0].toFloatOrNull()
                    val h = parts[1].toFloatOrNull()
                    if (w != null && h != null && h != 0f) return AspectRatio.Custom(w, h)
                }
            }
            // 3. Fraction (21/9)
            if (raw.contains("/")) {
                val parts = raw.split("/")
                if (parts.size == 2) {
                    val w = parts[0].toFloatOrNull()
                    val h = parts[1].toFloatOrNull()
                    if (w != null && h != null && h != 0f) return AspectRatio.Custom(w, h)
                }
            }
            // 4. Decimal (2.35)
            val f = raw.toFloatOrNull()
            if (f != null) return AspectRatio.Custom(f, 1f)
            
            AspectRatio.Fit // Default fallback
        }
    }
}

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
    val focusManager = LocalFocusManager.current

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
    
    // 1️⃣ ASPECT RATIO STATE (UI STATE)
    val aspectRatioSaver = remember {
        androidx.compose.runtime.saveable.Saver<AspectRatio, String>(
            save = { 
                when(it) {
                    AspectRatio.Fit -> "fit"
                    AspectRatio.Fill -> "fill"
                    AspectRatio.Crop -> "crop"
                    AspectRatio.Original -> "original"
                    AspectRatio.Stretch -> "stretch"
                    is AspectRatio.Custom -> "custom:${it.w}:${it.h}"
                }
            },
            restore = { parseAspectRatio(it) }
        )
    }
    var aspectRatio by rememberSaveable(stateSaver = aspectRatioSaver) { mutableStateOf<AspectRatio>(AspectRatio.Fit) }
    var customAspectInput by remember { mutableStateOf("") }

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

    // 2️⃣ APPLY TRANSFORM IMMEDIATELY (STRICT ARCHITECTURE)
    fun applyAspectRatio() {
        val tv = textureView ?: return
        val viewW = tv.width
        val viewH = tv.height
        
        // 4️⃣ MANDATORY CHECKS
        if (viewW <= 0 || viewH <= 0) return
        
        // Fallback safety for unknown video size
        val safeW = if (videoWidth > 0) videoWidth.toFloat() else viewW.toFloat()
        val safeH = if (videoHeight > 0) videoHeight.toFloat() else viewH.toFloat()

        val matrix = Matrix()
        val viewRatio = viewW.toFloat() / viewH
        val sourceRatio = safeW / safeH
        
        var scaleX = 1f
        var scaleY = 1f
        
        // 6️⃣ LOGIC: GPU Operations only. No isPlaying checks.
        when (val ar = aspectRatio) {
            AspectRatio.Fit -> {
                // Letterbox / Pillarbox
                if (viewRatio > sourceRatio) { // View wider than video
                    scaleX = (safeW * viewH) / (safeH * viewW)
                    scaleY = 1f
                } else { // View taller than video
                    scaleX = 1f
                    scaleY = (safeH * viewW) / (safeW * viewH)
                }
            }
            AspectRatio.Fill, AspectRatio.Crop -> {
                // Zoom to Fill
                if (viewRatio > sourceRatio) {
                     scaleX = 1f
                     scaleY = (viewW / sourceRatio) / viewH
                } else {
                     scaleX = (viewH * sourceRatio) / viewW
                     scaleY = 1f
                }
            }
            AspectRatio.Stretch -> {
                // Match View Bounds exactly (Distort)
                scaleX = 1f
                scaleY = 1f
            }
            AspectRatio.Original -> {
                 // Map safeW/safeH to View pixels (1:1 if we consider safeW as pixels, but logic here is Aspect based)
                 // User intent: "Aspect Ratio Original" usually means "Fit while respecting Source AR".
                 // MX Player "100%" implies 1:1 pixels. "Original" implies SAR.
                 // We implement canonical "Fit with Source AR" (Same as Fit, but semantic difference for user)
                 if (viewRatio > sourceRatio) {
                    scaleX = (safeW * viewH) / (safeH * viewW)
                    scaleY = 1f
                } else {
                    scaleX = 1f
                    scaleY = (safeH * viewW) / (safeW * viewH)
                }
            }
            is AspectRatio.Custom -> {
                // Force specific ratio (Anamorphic / Correction)
                // Treat ar.w / ar.h as the TRUE content ratio
                val targetRatio = ar.w / ar.h
                if (viewRatio > targetRatio) {
                    // View wider than target -> Pillarbox
                    scaleX = (viewH * targetRatio) / viewW
                    scaleY = 1f
                } else {
                    scaleX = 1f
                    scaleY = (viewW / targetRatio) / viewH
                }
            }
        }
        
        // TextureView Default scales to fit video buffer to view. 
        // We override with Matrix.
        // Important: TextureView documentation says setTransform is relative to the view's bounds.
        // For 'Fit' logic derived above (scaling down to fit), we need to ensure we start from 'Fill' or 'Fit'?
        // Actually, the simplest math is: 
        // 1. Calculate factor to match width (scaleX) and height (scaleY)
        // 2. Center pivot.
        
        // Re-evaluating standard math:
        // By default TextureView stretches video to View Size? No, it respects Surface aspect?
        // AndroidView + TextureView usually stretches content to View bounds unless configured.
        // So scaleX=1, scaleY=1 makes it STRETCH.
        
        // Let's refine for "Stretch" Base:
        // Fit Logic above assumes we are correcting a Stretched image.
        // Check: if scaleX = sourceRatio / viewRatio.
        // If view=16:9 (1.77), video=4:3 (1.33). source/view = 0.75. 
        // width = 0.75 * viewWidth. Correct for pillarbox.
        
        // Override above calculations for correctness based on Stretch Base:
        if (aspectRatio is AspectRatio.Fit || aspectRatio is AspectRatio.Original) {
             if (viewRatio > sourceRatio) {
                 scaleX = sourceRatio / viewRatio
                 scaleY = 1f
             } else {
                 scaleX = 1f
                 scaleY = viewRatio / sourceRatio
             }
        } else if (aspectRatio is AspectRatio.Fill || aspectRatio is AspectRatio.Crop) {
             if (viewRatio > sourceRatio) {
                 scaleX = 1f
                 scaleY = viewRatio / sourceRatio
             } else {
                 scaleX = sourceRatio / viewRatio
                 scaleY = 1f
             }
        } else if (aspectRatio is AspectRatio.Custom) {
            val targetRatio = (aspectRatio as AspectRatio.Custom).w / (aspectRatio as AspectRatio.Custom).h
            // We want the Visible Image to have `targetRatio`.
            // We start with Stretched Image (Ratio = viewRatio).
            // We want Final Ratio = targetRatio.
            // Factor = targetRatio / viewRatio.
             if (viewRatio > targetRatio) {
                 scaleX = targetRatio / viewRatio
                 scaleY = 1f
             } else {
                 scaleX = 1f
                 scaleY = viewRatio / targetRatio
             }
        }
        
        matrix.setScale(scaleX, scaleY, viewW / 2f, viewH / 2f)
        tv.setTransform(matrix)
    }

    // 3️⃣ LAUNCHED EFFECT (TRIGGER)
    LaunchedEffect(aspectRatio, videoWidth, videoHeight, customAspectInput) { 
        applyAspectRatio() 
    }

    // ============================================================================================
    // VIEW HIERARCHY
    // ============================================================================================

    Box(Modifier.fillMaxSize().background(VideoBg)) {
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
                            applyAspectRatio()
                        }
                        override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
                            // Update logic handles strict checks, okay to call
                            applyAspectRatio()
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
                            focusManager.clearFocus() 
                        },
                        onDoubleTap = { /* Seek feedback */ }
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

        // VISUAL FEEDBACK
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
                        
                        // GLASS CONTROL DECK
                        Box(
                            Modifier.align(Alignment.CenterHorizontally)
                                .clip(RoundedCornerShape(32.dp))
                                .background(GlassGradient)
                                .border(1.dp, GlassBorder, RoundedCornerShape(32.dp))
                                .padding(horizontal = 24.dp, vertical = 12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                                
                                // Aspect Toggle (Cyclic) - Quick Switch
                                IconButton(onClick = { 
                                     aspectRatio = when(aspectRatio) {
                                         AspectRatio.Fit -> AspectRatio.Fill
                                         AspectRatio.Fill -> AspectRatio.Crop
                                         AspectRatio.Crop -> AspectRatio.Stretch
                                         else -> AspectRatio.Fit
                                     }
                                }) { Icon(Icons.Rounded.AspectRatio, "Aspect", tint = Color.White) }

                                SpringIconButton(onClick = { 
                                     engine.seekTo((engine.currentPositionMs - 10000).coerceAtLeast(0))
                                }, icon = Icons.Rounded.Replay10)

                                SpringIconButton(
                                    onClick = { 
                                        lastInteractionTime = System.currentTimeMillis()
                                        if (isPlaying) engine.pause() else engine.resume()
                                    }, 
                                    icon = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    size = 48.dp
                                )

                                SpringIconButton(onClick = { 
                                    engine.seekTo((engine.currentPositionMs + 10000).coerceAtMost(durationMs)) 
                                }, icon = Icons.Rounded.Forward10)

                                IconButton(onClick = { showDiagnostics = !showDiagnostics }) { 
                                    Icon(Icons.Rounded.Info, "Infos", tint = Color.White.copy(0.7f)) 
                                }
                            }
                        }
                    }
                }
            }
        }

        // [4] SETTINGS OVERLAY
        AnimatedVisibility(
            visible = showSettings,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
             Box(Modifier.fillMaxSize().background(Color.Black.copy(0.4f)).clickable{showSettings=false}, contentAlignment = Alignment.BottomCenter) {
                 Column(
                     Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart=24.dp, topEnd=24.dp))
                        .background(GlassGradient)
                        .border(1.dp, GlassBorder, RoundedCornerShape(topStart=24.dp, topEnd=24.dp))
                        .clickable(enabled=false){} // consume click
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
                     
                     SettingRow("Aspect Ratio Preset") {
                          val presets = listOf(AspectRatio.Fit, AspectRatio.Fill, AspectRatio.Crop, AspectRatio.Original, AspectRatio.Stretch)
                          presets.forEach { mode ->
                               FilterChip(
                                selected = aspectRatio::class == mode::class, onClick = { aspectRatio = mode },
                                label = { Text(mode.label) },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor=Color.White, containerColor=Color.Transparent, labelColor=Color.White, selectedLabelColor=Color.Black)
                             )
                          }
                     }
                     
                     // Custom Input Row
                     Text("Custom Ratio", color = Color.Gray, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top=8.dp))
                     Row(Modifier.fillMaxWidth().padding(top=8.dp), verticalAlignment = Alignment.CenterVertically) {
                         OutlinedTextField(
                            value = customAspectInput,
                            onValueChange = { customAspectInput = it },
                            placeholder = { Text("e.g. 16:9, 2.39, 1920x1080", color=Color.Gray) },
                            textStyle = TextStyle(color=Color.White),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = {
                                aspectRatio = parseAspectRatio(customAspectInput)
                                focusManager.clearFocus()
                            }),
                            modifier = Modifier.weight(1f).border(1.dp, Color.White.copy(0.3f), RoundedCornerShape(8.dp)),
                            colors = TextFieldDefaults.outlinedTextFieldColors(
                                focusedBorderColor = Color.Transparent, 
                                unfocusedBorderColor = Color.Transparent,
                                containerColor = Color.Transparent
                            )
                         )
                         Spacer(Modifier.width(16.dp))
                         GlassButton(
                            onClick = { 
                                aspectRatio = parseAspectRatio(customAspectInput)
                                focusManager.clearFocus() 
                            }, 
                            icon = Icons.Rounded.Check
                         )
                     }
                     // Quick Common
                     Row(Modifier.padding(top=8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                         listOf(AspectRatio.Custom.SixteenNine, AspectRatio.Custom.TwentyOneNine, AspectRatio.Custom.FourThree).forEach { preset ->
                             FilterChip(
                                selected = aspectRatio == preset, onClick = { aspectRatio = preset; customAspectInput = preset.label },
                                label = { Text(preset.label) },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor=Color.White, containerColor=Color.Transparent, labelColor=Color.White, selectedLabelColor=Color.Black)
                             )
                         }
                     }
                     Spacer(Modifier.height(200.dp)) // Keyboard space
                 }
             }
        }
        
        // Diagnostics
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
                    DiagnosticItem("Aspect State", aspectRatio.label)
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
    var isHovering by remember { mutableStateOf(false) } 
    val height by animateDpAsState(if (isHovering) 12.dp else 4.dp, label = "scrubberHeight")
    
    Box(
        modifier = Modifier.fillMaxWidth().height(32.dp).pointerInput(Unit) {
                detectTapGestures(
                    onPress = { isHovering=true; tryAwaitRelease(); isHovering=false; onCommit() }
                )
            }.pointerInput(Unit) {
                 detectHorizontalDragGestures(
                    onDragStart = { isHovering = true },
                    onDragEnd = { isHovering = false; onCommit() },
                    onHorizontalDrag = { change, _ -> onValueChange((change.position.x / size.width).coerceIn(0f, 1f)) }
                )
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(4.dp)).background(Color.White.copy(0.3f)))
        Box(Modifier.fillMaxWidth(value).height(height).clip(RoundedCornerShape(4.dp)).background(Color.Red))
    }
}

@Composable
fun SpringIconButton(onClick: () -> Unit, icon: androidx.compose.ui.graphics.vector.ImageVector, size: Dp = 24.dp) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) 0.8f else 1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow), label = "spring")
    IconButton(onClick = onClick, interactionSource = interactionSource) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(size).graphicsLayer { scaleX=scale; scaleY=scale })
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
        Row(Modifier.fillMaxWidth().padding(top=8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
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
        Text(text, color = outlineColor, textAlign = TextAlign.Center, fontSize = fontSizeSp.sp, style = TextStyle(drawStyle = androidx.compose.ui.graphics.drawscope.Stroke(width = outlineWidthDp)))
        Text(text, color = textColor, textAlign = TextAlign.Center, fontSize = fontSizeSp.sp)
    }
}