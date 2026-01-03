package com.mxlite.app

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

@Composable
fun PlayerScreen(vm: PlayerViewModel) {
    val context = LocalContext.current

    var resumeMs by remember { mutableStateOf<Long?>(null) }
    
    
    var showResumeDialog by remember { mutableStateOf(false) }
    var dontAskAgain by remember { mutableStateOf(false) }

    var dontAskAgain by remember { mutableStateOf(false) }


    // Load resume position on file/tab open
    LaunchedEffect(activeTab.path) {
        val pos = ResumeStore.load(context, activeTab.path)
        if (pos > 5_000) { // ignore tiny resumes
            resumeMs = pos
            
        if (ResumeStore.shouldAsk(context, activeTab.path)) {
            
        if (ResumeStore.shouldAsk(context, activeTab.path)) {
            showResumeDialog = true
        } else {
            playerController.seekTo(pos.toInt())
        }

        } else {
            playerController.seekTo(pos.toInt())
        }

        }
    }

    // Save playback position on exit or tab switch
    DisposableEffect(activeTab.path) {
        onDispose {
            ResumeStore.save(
                context,
                activeTab.path,
                audioRenderer.getClockMs()
            )
        }
    }

    val context = LocalContext.current

    // Restore position on open
    LaunchedEffect(activeTab.path) {
        val resumeMs = ResumeStore.load(context, activeTab.path)
        if (resumeMs > 0) {
            playerController.seekTo(resumeMs.toInt())
        }
    }

    // Save position on exit
    DisposableEffect(activeTab.path) {
        onDispose {
            ResumeStore.save(
                context,
                activeTab.path,
                audioRenderer.getClockMs()
            )
        }
    }

    val context = LocalContext.current
    var showCodecPackDialog by remember {
        mutableStateOf(false)
    }

    val assRenderer = remember {
        runCatching {
            AssSubtitleRenderer("/storage/emulated/0/movie.ass")
        }.getOrNull()
    }

    DisposableEffect(Unit) {
        onDispose {
            assRenderer?.release()
        }
    }

    val subtitleTrack = remember {
        runCatching {
            SubtitleTrack(
                SrtParser.parse(
                    File("/storage/emulated/0/movie.srt")
                )
            )
        }.getOrNull()
    }

    
var showBrowser by remember { mutableStateOf(false) }
        }

if (showBrowser) {
    FileBrowserScreen(
        context = vm.context,
        vm = vm,
        onClose = { showBrowser = false }
    )
    return
}

Column(

        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        
TopAppBar(
    title = { Text("MX Lite") },
    actions = {
        IconButton(onClick = { showBrowser = true }) {
            Text("Browse")
        }
    }
)
title = { Text("MX Lite") })

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            Box(
            modifier = Modifier
                .fillMaxSize()
                .then(GestureOverlay(vm))
        ) {
            VideoSurface { surface ->
                vm.onSurfaceReady(surface)
            }
        }

        PlayerControls(
            onPlay = {
                vm.play(Uri.parse("/storage/emulated/0/Movies/sample.mp4"))
            },
            onPause = { vm.pause() }
        )
    }
}

@Composable
fun CodecDialogHost(vm: com.mxlite.app.player.PlayerViewModel) {
    val show by vm.showCodecDialog.collectAsState()

    if (show) {
        CodecDialog(
            onInstall = { },
            onUseSW = {
                vm.forceSoftware(true)
            },
            onDismiss = { }
        )
    }
}
