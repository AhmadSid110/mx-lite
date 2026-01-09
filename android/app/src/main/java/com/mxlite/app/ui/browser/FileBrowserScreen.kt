package com.mxlite.app.ui.browser

import kotlinx.coroutines.launch
import android.content.Intent
import android.net.Uri
import android.os.Environment
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
import com.mxlite.app.storage.SafFileCopier
import com.mxlite.app.storage.StorageStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/* ───────────────────────────────────────────── */
/* Video helpers */
/* ───────────────────────────────────────────── */

private val VIDEO_EXTENSIONS = setOf(
    "mp4", "mkv", "avi", "webm", "mov", "flv", "wmv", "m4v"
)

private fun File.isVideo(): Boolean =
    isFile && extension.lowercase() in VIDEO_EXTENSIONS

private fun File.containsVideoShallowSafe(): Boolean =
    runCatching {
        isDirectory && listFiles()?.any { it.isVideo() } == true
    }.getOrDefault(false)

/* ───────────────────────────────────────────── */
/* Cache */
/* ───────────────────────────────────────────── */

private val directoryCache = mutableMapOf<String, List<File>>()

/* ───────────────────────────────────────────── */
/* UI */
/* ───────────────────────────────────────────── */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileBrowserScreen(
    onFileSelected: (File) -> Unit
) {
    val context = LocalContext.current
    val store = remember { StorageStore(context) }
    val scope = rememberCoroutineScope()

    /* ✅ SAFE ROOT DIRECTORY */
    val rootDir = remember {
        Environment.getExternalStorageDirectory()
    }
    var currentDir by remember { mutableStateOf(rootDir) }

    var safFolders by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var currentSafDir by remember { mutableStateOf<DocumentFile?>(null) }

    /* ───────── Back handling ───────── */
    BackHandler(enabled = currentSafDir != null || currentDir != rootDir) {
        when {
            currentSafDir != null -> currentSafDir = null
            currentDir != rootDir -> currentDir = currentDir.parentFile ?: rootDir
        }
    }

    LaunchedEffect(Unit) {
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
                    directoryCache.clear()
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
                        currentDir != rootDir -> currentDir.name
                        else -> "Videos"
                    }
                )
            },
            navigationIcon = {
                if (currentSafDir != null || currentDir != rootDir) {
                    IconButton(onClick = {
                        when {
                            currentSafDir != null -> currentSafDir = null
                            currentDir != rootDir ->
                                currentDir = currentDir.parentFile ?: rootDir
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
                    FolderCard(uri.lastPathSegment ?: "Folder") {
                        currentSafDir =
                            DocumentFile.fromTreeUri(context, uri)
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
                    FolderCard(doc.name ?: "") {
                        if (doc.isDirectory) {
                            currentSafDir = doc
                        } else {
                            onFileSelected(
                                SafFileCopier.copyToCache(context, doc.uri)
                            )
                        }
                    }
                }
            }
            return@Column
        }

        /* ───────── Local filesystem (SAFE + CACHED) ───────── */

        var entries by remember { mutableStateOf<List<File>>(emptyList()) }
        var loading by remember { mutableStateOf(true) }

        LaunchedEffect(currentDir) {
            val path = currentDir.absolutePath

            directoryCache[path]?.let {
                entries = it
                loading = false
                return@LaunchedEffect
            }

            loading = true

            val result = withContext(Dispatchers.IO) {
                runCatching {
                    currentDir.listFiles()
                        ?.filter {
                            it.isVideo() ||
                                (it.isDirectory && it.containsVideoShallowSafe())
                        }
                        ?.sortedWith(compareBy<File> { !it.isDirectory })
                        ?: emptyList()
                }.getOrDefault(emptyList())
            }

            if (result.isNotEmpty()) {
                directoryCache[path] = result
            }

            entries = result
            loading = false
        }

        if (loading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            return@Column
        }

        LazyColumn {
            items(entries) { file ->
                FolderCard(file.name) {
                    if (file.isDirectory) {
                        currentDir = file
                    } else {
                        onFileSelected(file)
                    }
                }
            }
        }
    }
}

/* ───────────────────────────────────────────── */
/* Reusable card */
/* ───────────────────────────────────────────── */

@Composable
private fun FolderCard(
    title: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("📁", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.width(12.dp))
            Text(title, maxLines = 1)
        }
    }
}