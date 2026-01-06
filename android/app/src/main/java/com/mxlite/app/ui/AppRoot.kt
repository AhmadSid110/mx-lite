package com.mxlite.app.ui

import androidx.compose.runtime.*
import com.mxlite.app.player.PlayerController
import com.mxlite.app.ui.browser.FileBrowserScreen
import com.mxlite.app.ui.player.PlayerScreen
import java.io.File

@Composable
fun AppRoot() {

    val engine = remember { PlayerController() }
    var playingFile by remember { mutableStateOf<File?>(null) }

    if (playingFile != null) {
        PlayerScreen(
            file = playingFile!!,
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
