package com.mxlite.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.mxlite.app.player.PlayerController
import com.mxlite.app.ui.browser.FileBrowserScreen
import com.mxlite.app.ui.player.PlayerScreen
import java.io.File

@Composable
fun AppRoot() {
    val context = LocalContext.current

    // 🔑 Request ALL FILES ACCESS (Android 11+)
    LaunchedEffect(Unit) {
        if (!Environment.isExternalStorageManager()) {
            val intent = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
            context.startActivity(intent)
        }
    }

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