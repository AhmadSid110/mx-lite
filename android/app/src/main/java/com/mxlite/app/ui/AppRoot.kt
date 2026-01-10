package com.mxlite.app.ui

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.mxlite.app.player.PlayerController
import com.mxlite.app.ui.browser.FileBrowserScreen
import com.mxlite.app.ui.player.PlayerScreen
import java.io.File

@Composable
fun AppRoot() {
    val context = LocalContext.current

    // PlayerController now correctly receives Context
    val engine = remember {
        PlayerController(context)
    }

    var playingFile by remember { mutableStateOf<File?>(null) }

    if (playingFile != null) {
        PlayerScreen(
            file = playingFile!!,
            engine = engine,
            onBack = {
                engine.release()   // 🔑 important cleanup
                playingFile = null
            }
        )
    } else {
        FileBrowserScreen(
            onFileSelected = { file ->
                playingFile = file
            }
        )
    }
}