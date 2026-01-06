package com.mxlite.app.ui

import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.ui.platform.LocalContext
import com.mxlite.app.player.PlayerEngine
import com.mxlite.app.player.ExoPlayerEngine
import com.mxlite.app.ui.browser.FileBrowserScreen
import com.mxlite.app.ui.player.PlayerScreen
import java.io.File

@Composable
fun AppRoot() {
    val context = LocalContext.current

    val engine: PlayerEngine = remember {
        ExoPlayerEngine(context)
    }

    var playingFile by remember { mutableStateOf<File?>(null) }

    if (playingFile != null) {
        PlayerScreen(
            file = playingFile,
            safUri = null,
            engine = engine,
            onBack = { playingFile = null }
        )
    } else {
        FileBrowserScreen(
            onFileSelected = { file ->
                playingFile = file
            }
        )
    }
}
