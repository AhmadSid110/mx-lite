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
    
var showBrowser by remember { mutableStateOf(false) }

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