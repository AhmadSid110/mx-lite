package com.mxlite.app.ui.browser

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
import com.mxlite.app.storage.SafFileCopier
import com.mxlite.app.storage.StorageStore
import kotlinx.coroutines.launch
import java.io.File

/* ───────────────────────────────────────────── */
/* Video detection helpers */
/* ───────────────────────────────────────────── */

private val VIDEO_EXTENSIONS = setOf(
    "mp4", "mkv", "avi", "webm", "mov", "flv", "wmv", "m4v"
)

private fun File.isVideo(): Boolean =
    isFile && extension.lowercase() in VIDEO_EXTENSIONS

private fun File.containsVideoRecursively(): Boolean {
    if (!isDirectory) return false
    listFiles()?.forEach {
        if (it.isVideo()) return true
        if (it.isDirectory && it.containsVideoRecursively()) return true
    }
    return false
}

/* ───────────────────────────────────────────── */
/* UI */
/* ───────────────────────────────────────────── */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileBrowserScreen(
    onFileSelected: (File) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { StorageStore(context) }

    val rootDir = remember { File("/storage/emulated/0") }
    var currentDir by remember { mutableStateOf(rootDir) }

    var safFolders by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var currentSafDir by remember { mutableStateOf<DocumentFile?>(null) }

    /* ───────── Back handling ───────── */
    BackHandler(enabled = currentDir != rootDir || currentSafDir != null) {
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
                if (currentDir != rootDir || currentSafDir != null) {
                    IconButton(onClick = {
                        when {
                            currentSafDir != null -> currentSafDir = null
                            currentDir != rootDir -> currentDir = currentDir.parentFile ?: rootDir
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

        /* ───────── SAF root folders ───────── */
        if (currentSafDir == null && safFolders.isNotEmpty()) {
            LazyColumn {
                items(safFolders) { uri ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                            .clickable {
                                currentSafDir = DocumentFile.fromTreeUri(context, uri)
                            },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("📁", style = MaterialTheme.typography.headlineSmall)
                            Spacer(Modifier.width(12.dp))
                            Text(uri.lastPathSegment ?: "Folder")
                        }
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
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .clickable {
                                if (doc.isDirectory) {
                                    currentSafDir = doc
                                } else {
                                    val file = SafFileCopier.copyToCache(context, doc.uri)
                                    onFileSelected(file)
                                }
                            },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (doc.isDirectory) "📁" else "▶",
                                style = MaterialTheme.typography.headlineSmall
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(doc.name ?: "")
                        }
                    }
                }
            }
            return@Column
        }

        /* ───────── Local filesystem browsing ───────── */
        val entries = remember(currentDir) {
            currentDir.listFiles()
                ?.filter {
                    it.isVideo() || (it.isDirectory && it.containsVideoRecursively())
                }
                ?.sortedWith(compareBy<File> { !it.isDirectory })
                ?: emptyList()
        }

        LazyColumn {
            items(entries) { file ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .clickable {
                            if (file.isDirectory) {
                                currentDir = file
                            } else {
                                onFileSelected(file)
                            }
                        },
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (file.isDirectory) "📁" else "▶",
                            style = MaterialTheme.typography.headlineSmall
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            file.name,
                            maxLines = 1,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        }
    }
}}
