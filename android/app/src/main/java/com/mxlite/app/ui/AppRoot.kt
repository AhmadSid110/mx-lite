package com.mxlite.app.ui

import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.mxlite.app.player.PlayerController
import com.mxlite.app.ui.player.PlayerScreen
import com.mxlite.app.ui.screens.HomeScreen
import com.mxlite.app.ui.screens.TabbedFolderScreen
import com.mxlite.app.model.FolderInfo
import com.mxlite.app.model.VideoFile
import com.mxlite.app.browser.VideoStoreRepository
import com.mxlite.app.storage.SafFileCopier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
        val items = VideoStoreRepository.load(context)

        // Map VideoItem -> VideoFile
        val videos = items.map { item ->
            VideoFile(
                id = item.contentUri.toString(),
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
        var videoToPlay by remember { mutableStateOf<com.mxlite.app.model.VideoFile?>(null) }

        TabbedFolderScreen(
            folder = selectedFolder!!,
            onVideoClick = { video ->
                videoToPlay = video
            },
            onBack = { selectedFolder = null }
        )

        // ✅ FIX: Use LaunchedEffect to run suspend work when a video is requested
        LaunchedEffect(videoToPlay) {
            val v = videoToPlay
            if (v != null) {
                val cachedFile = withContext(Dispatchers.IO) {
                    SafFileCopier.copyToCache(context, android.net.Uri.parse(v.id))
                }
                playingFile = cachedFile
                videoToPlay = null
            }
        }
    } else {
        HomeScreen(
            folders = folders,
            onFolderClick = { folder -> selectedFolder = folder }
        )
    }
}