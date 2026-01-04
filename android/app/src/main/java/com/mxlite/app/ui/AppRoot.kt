package com.mxlite.app.ui

import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.ui.platform.LocalContext
import com.mxlite.app.player.ExoPlayerEngine
import com.mxlite.app.ui.player.PlayerScreen
import java.io.File

@Composable
fun AppRoot() {
    val context = LocalContext.current
    val engine = remember { ExoPlayerEngine(context) }

    var playingFile by remember { mutableStateOf<File?>(null) }

    if (playingFile != null) {
        PlayerScreen(
            file = playingFile!!,
            engine = engine,
            onBack = { playingFile = null }
        )
    } else {
        Text("Home (wire browser → player here)")
    }
}
