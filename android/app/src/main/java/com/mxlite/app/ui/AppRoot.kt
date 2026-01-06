package com.mxlite.app.ui

import android.net.Uri
import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.ui.platform.LocalContext
import com.mxlite.app.player.PlayerController
import com.mxlite.app.player.PlayerEngine
import com.mxlite.app.ui.browser.FileBrowserScreen
import com.mxlite.app.ui.player.PlayerScreen
import java.io.File

@Composable
fun AppRoot() {
    val context = LocalContext.current

    // 🔒 SINGLE ENGINE OWNER
    val engine: PlayerEngine = remember {
        PlayerController(context)
    }

    var playingFile by remember { mutableStateOf<File?>(null) }
    var playingSafUri by remember { mutableStateOf<Uri?>(null) }

    when {
        playingFile != null || playingSafUri != null -> {
            PlayerScreen(
                file = playingFile,
                safUri = playingSafUri,
                engine = engine,
                onBack = {
                    playingFile = null
                    playingSafUri = null
                }
            )
        }

        else -> {
            FileBrowserScreen(
                onFileSelected = {
                    playingFile = it
                },
                onSafFileSelected = {
                    playingSafUri = it
                }
            )
        }
    }
}
