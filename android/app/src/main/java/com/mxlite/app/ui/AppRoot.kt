package com.mxlite.app.ui

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.mxlite.app.player.PlayerController
import com.mxlite.app.ui.player.PlayerScreen
import com.mxlite.app.ui.screens.HomeScreen
import com.mxlite.app.ui.screens.TabbedFolderScreen
import com.mxlite.app.model.FolderInfo
import com.mxlite.app.browser.VideoStoreRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@androidx.compose.material3.ExperimentalMaterial3Api
@Composable
fun AppRoot() {
    val context = LocalContext.current

    // PlayerController correctly receives Context
    val engine = remember { PlayerController(context) }

    // Navigation State
    var playingUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var selectedFolder by remember { mutableStateOf<FolderInfo?>(null) }
    var folders by remember { mutableStateOf<List<FolderInfo>>(emptyList()) }

    // Load folders
    LaunchedEffect(Unit) {
        val items = withContext(Dispatchers.IO) {
            VideoStoreRepository.load(context)
        }

        val videos = items.map { item ->
            com.mxlite.app.model.VideoFile(
                id = item.id.toString(),
                path = item.folder,
                name = item.name,
                size = item.size,
                duration = item.duration,
                dateAdded = item.dateAdded
            )
        }

        folders = videos
            .groupBy { it.path }
            .map { (folderPath, list) ->
                val displayName = folderPath.split('/').lastOrNull() ?: "Videos"
                FolderInfo(
                    name = displayName,
                    videoCount = list.size,
                    totalSize = list.sumOf { it.size },
                    videos = list
                )
            }
            .sortedBy { it.name }
    }

    // Navigation Logic
    if (playingUri != null) {
        PlayerScreen(
            uri = playingUri!!,
            engine = engine,
            onBack = {
                // We just clear state here. 
                // Engine cleanup happens inside PlayerScreen's DisposableEffect
                playingUri = null
            }
        )
    
    } else if (selectedFolder != null) {
        TabbedFolderScreen(
            folder = selectedFolder!!,
            onVideoClick = { video ->
                // ✅ CORRECT: Just set the state.
                // Do NOT call engine.play() here.
                // PlayerScreen will handle it when ready.
                playingUri = video.thumbnailUri 
            },
            onBack = { selectedFolder = null }
        )
    } else {
        HomeScreen(
            folders = folders,
            onFolderClick = { folder -> selectedFolder = folder }
        )
    }
}
