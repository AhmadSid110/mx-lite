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
