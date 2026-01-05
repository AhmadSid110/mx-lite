package com.mxlite.app.ui

import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.ui.platform.LocalContext
import com.mxlite.app.player.PlayerEngine
import com.mxlite.app.player.ExoPlayerEngine
import com.mxlite.app.ui.player.PlayerScreen
import com.mxlite.app.ui.browser.FileBrowserScreen
import java.io.File

@Composable
fun AppRoot() {
    val context = LocalContext.current

    // 🔒 UI depends ONLY on PlayerEngine
    val engine: PlayerEngine = remember {
        ExoPlayerEngine(context)
    }

    var playingFile by remember { mutableStateOf<File?>(null) }

    if (playingFile != null) {
        PlayerScreen(
    file = playingFile,
    safUri = playingSafUri,
    engine = engine,
    onBack = {
        playingFile = null
        playingSafUri = null
    }
) else {
        FileBrowserScreen(
            onFileSelected = { file ->
                playingFile = file
            }
        )
    }
}
