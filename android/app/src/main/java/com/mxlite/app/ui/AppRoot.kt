package com.mxlite.app.ui

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.mxlite.app.player.PlayerController
import com.mxlite.app.ui.browser.FileBrowserScreen
import com.mxlite.app.ui.player.PlayerScreen
import com.mxlite.app.ui.screens.HomeScreen
import com.mxlite.app.model.FolderInfo
import com.mxlite.app.browser.VideoStoreRepository
import java.io.File

@Composable
fun AppRoot() {
    val context = LocalContext.current

    // PlayerController now correctly receives Context
    val engine = remember {
        PlayerController(context)
    }

    var playingFile by remember { mutableStateOf<File?>(null) }
    var selectedFolder by remember { mutableStateOf<FolderInfo?>(null) }
    var folders by remember { mutableStateOf<List<FolderInfo>>(emptyList()) }

    /* Load folders from MediaStore */
    LaunchedEffect(Unit) {
        val videos = VideoStoreRepository.load(context)
        folders = videos
            .groupBy { it.folder }
            .map { (folderPath, list) ->
                val displayName = folderPath.split('/').lastOrNull() ?: "Videos"
                FolderInfo(
                    name = displayName,
                    path = folderPath,
                    videoCount = list.size,
                    totalSizeFormatted = "—"
                )
            }
            .sortedBy { it.name }
    }

    if (playingFile != null) {
        PlayerScreen(
            file = playingFile!!,
            engine = engine,
            onBack = {
                engine.release()   // 🔑 important cleanup
                playingFile = null
            }
        )
    } else if (selectedFolder != null) {
        FileBrowserScreen(
            onFileSelected = { file -> playingFile = file },
            initialFolder = selectedFolder!!.path,
            onNavigateHome = { selectedFolder = null }
        )
    } else {
        HomeScreen(
            folders = folders,
            onFolderClick = { folder -> selectedFolder = folder }
        )
    }
}