package com.mxlite.app.ui.browser

import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.mxlite.app.browser.DEFAULT_FOLDER_NAME
import com.mxlite.app.browser.VideoItem
import com.mxlite.app.browser.VideoStoreRepository
import com.mxlite.app.storage.SafFileCopier
import com.mxlite.app.storage.StorageStore
import com.mxlite.app.ui.components.ModernFolderItem
import java.io.File

/* ───────────────────────────────────────────── */
/* Helpers */
/* ───────────────────────────────────────────── */

private fun extractFolderDisplayName(folder: String?): String =
    folder?.split('/')?.lastOrNull() ?: DEFAULT_FOLDER_NAME

/* ───────────────────────────────────────────── */
/* UI */
/* ───────────────────────────────────────────── */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileBrowserScreen(
    onFileSelected: (File) -> Unit,
    initialFolder: String? = null,
    onNavigateHome: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val store = remember { StorageStore(context) }
    val scope = rememberCoroutineScope()

    /* ✅ MediaStore videos */
    var videos by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
    var currentFolder by remember { mutableStateOf<String?>(initialFolder) }

    /* ✅ SAF folders */
    var safFolders by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var currentSafDir by remember { mutableStateOf<DocumentFile?>(null) }

    /* ───────── Back handling ───────── */
    BackHandler(enabled = currentSafDir != null || currentFolder != null) {
        when {
            currentSafDir != null -> currentSafDir = null
            currentFolder != null -> currentFolder = null
        }
    }

    /* ───────── Load videos once ───────── */
    LaunchedEffect(Unit) {
        videos = VideoStoreRepository.load(context)
        safFolders = store.getFolders()
    }

    /* ───────── SAF picker ───────── */
    val folderPicker =
        rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocumentTree()
        ) { uri ->
            if (uri != null) {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                scope.launch {
                    store.addFolder(uri)
                    safFolders = store.getFolders()
                }
            }
        }

    Column(modifier = Modifier.fillMaxSize()) {

        /* ───────── Top bar ───────── */
        TopAppBar(
            title = {
                Text(
                    when {
                        currentSafDir != null -> "Folders"
                        currentFolder != null -> extractFolderDisplayName(currentFolder)
                        else -> DEFAULT_FOLDER_NAME
                    }
                )
            },
            navigationIcon = {
                if (currentSafDir != null || currentFolder != null) {
                    IconButton(onClick = {
                        when {
                            currentSafDir != null -> currentSafDir = null
                            currentFolder != null -> currentFolder = null
                        }
                    }) {
                        Text("←")
                    }
                }
            },
            actions = {
                TextButton(onClick = { folderPicker.launch(null) }) {
                    Text("Add Folder")
                }
            }
        )

        /* ───────── SAF root ───────── */
        if (currentSafDir == null && safFolders.isNotEmpty()) {
            LazyColumn {
                items(safFolders) { uri ->
                    ModernFolderItem(
                        folderName = uri.lastPathSegment ?: "Folder",
                        videoCount = 0,
                        folderSize = "—"
                    ) {
                        currentSafDir = DocumentFile.fromTreeUri(context, uri)
                    }
                }
            }
            Divider()
        }

        /* ───────── SAF browsing ───────── */
        if (currentSafDir != null) {
            val children = remember(currentSafDir) {
                currentSafDir!!.listFiles()
                    .sortedWith(compareBy<DocumentFile> { !it.isDirectory })
            }

            LazyColumn {
                items(children) { doc ->
                    ModernFolderItem(
                        folderName = doc.name ?: "",
                        videoCount = if (doc.isDirectory) doc.listFiles().count { !it.isDirectory } else 1,
                        folderSize = "—"
                    ) {
                        if (doc.isDirectory) {
                            currentSafDir = doc
                        } else {
                            scope.launch {
                                val cachedFile = withContext(Dispatchers.IO) {
                                    SafFileCopier.copyToCache(context, doc.uri)
                                }
                                onFileSelected(cachedFile)
                            }
                        }
                    }
                }
            }
            return@Column
        }

        /* ───────── Local videos (MediaStore) ───────── */
        
        if (currentFolder != null) {
            /* Show videos in selected folder */
            val folderVideos = videos.filter { it.folder == currentFolder }
            
            LazyColumn {
                items(folderVideos) { video ->
                    ModernFolderItem(
                        folderName = video.name,
                        videoCount = 1,
                        folderSize = "—"
                    ) {
                        scope.launch {
                            val cachedFile = withContext(Dispatchers.IO) {
                                SafFileCopier.copyToCache(context, video.contentUri)
                            }
                            onFileSelected(cachedFile)
                        }
                    }
                }
            }
        } else {
            /* Show folders that contain videos */
            val folders = videos
                .map { it.folder }
                .distinct()
                .sorted()
            
            LazyColumn {
                items(folders) { folder ->
                    val displayName = extractFolderDisplayName(folder)
                    ModernFolderItem(
                        folderName = displayName,
                        videoCount = videos.count { it.folder == folder },
                        folderSize = "—"
                    ) {
                        currentFolder = folder
                    }
                }
            }
        }
    }
}

