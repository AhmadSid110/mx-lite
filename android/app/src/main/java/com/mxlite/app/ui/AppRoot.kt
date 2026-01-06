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

    // 🔒 SINGLE ENGINE INSTANCE (MediaCodec Audio + Video)
    val engine: PlayerEngine = remember {
        PlayerController()
    }

    // ───────── Playback State ─────────
    var playingFile by remember { mutableStateOf<File?>(null) }
    var playingSafUri by remember { mutableStateOf<Uri?>(null) }

    when {
        // 🎥 PLAYER SCREEN
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

        // 📁 FILE BROWSER
        else -> {
            FileBrowserScreen(
                onFileSelected = { file ->
                    playingFile = file
                }
                // SAF file playback will hook here in SAF-5
            )
        }
    }
}
