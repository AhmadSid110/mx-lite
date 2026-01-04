package com.mxlite.app.ui

import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.mxlite.app.ui.browser.FileBrowserScreen
import com.mxlite.app.ui.player.PlayerScreen
import java.io.File

@Composable
fun AppRoot() {
    var selectedFile by remember { mutableStateOf<File?>(null) }

    if (selectedFile == null) {
        FileBrowserScreen(
            onFileSelected = { selectedFile = it }
        )
    } else {
        PlayerScreen(
            file = selectedFile!!,
            onBack = { selectedFile = null }
        )
    }
}
