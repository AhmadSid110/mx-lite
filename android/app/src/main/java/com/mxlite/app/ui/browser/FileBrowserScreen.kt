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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
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

private val VIDEO_EXTENSIONS = setOf(
    "mp4",
    "mkv",
    "avi",
    "webm",
    "mov",
    "flv",
    "wmv",
    "m4v"
)

private fun File.isVideoFile(): Boolean {
    if (!isFile) return false
    return extension.lowercase() in VIDEO_EXTENSIONS
}

private fun File.containsVideo(): Boolean {
    if (!isDirectory) return false

    val children = listFiles() ?: return false
    for (child in children) {
        if (child.isVideoFile()) return true
        if (child.isDirectory && child.containsVideo()) return true
    }
    return false
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileBrowserScreen(
    onFileSelected: (File) -> Unit
) {
    // ───────── State (MUST be before BackHandler logic uses it) ─────────
    val initialDir = remember { File("/storage/emulated/0") }
    var currentDir by remember { mutableStateOf(initialDir) }
    var currentSafDir by remember { mutableStateOf<DocumentFile?>(null) }

    // ───────── BACK HANDLER (CRITICAL FIX) ─────────
    BackHandler(enabled = currentDir.parentFile != null) {
        currentDir = currentDir.parentFile ?: currentDir
    }

    // ───────── Context / helpers ─────────
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { StorageStore(context) }

    // ───────── SAF folders root ─────────
    var safFolders by remember { mutableStateOf<List<Uri>>(emptyList()) }

    LaunchedEffect(Unit) {
        safFolders = store.getFolders()
    }

    // ───────── Folder Picker (SAF) ─────────
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

    Column(
        modifier = Modifier.fillMaxSize()
    ) {

        // ───────── Top Bar ─────────
        TopAppBar(
            title = {
                Text(
                    when {
                        currentSafDir != null -> "SAF Browser"
                        else -> "File Browser"
                    }
                )
            },
            navigationIcon = {
                if (currentSafDir != null || currentDir.parentFile != null) {
                    IconButton(
                        onClick = {
                            when {
                                currentSafDir != null -> currentSafDir = null
                                currentDir.parentFile != null ->
                                    currentDir = currentDir.parentFile!!
                            }
                        }
                    ) {
                        Text("←")
                    }
                }
            },
            actions = {
                TextButton(onClick = { folderPicker.launch(null) }) {
                    Text("Pick Folder")
                }
            }
        )

        // ───────── SAF ROOT LIST ─────────
        if (currentSafDir == null && safFolders.isNotEmpty()) {
            LazyColumn {
                items(safFolders) { uri ->
                    Text(
                        text = "📁 ${uri.lastPathSegment ?: uri}",
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                currentSafDir =
                                    DocumentFile.fromTreeUri(context, uri)
                            }
                            .padding(12.dp)
                    )
                }
            }
            Divider()
        }

        // ───────── SAF DIRECTORY VIEW ─────────
        if (currentSafDir != null) {
            val children = remember(currentSafDir) {
                currentSafDir!!
                    .listFiles()
                    .sortedWith(
                        compareBy<DocumentFile> { !it.isDirectory }
                            .thenBy { it.name?.lowercase() }
                    )
            }

            LazyColumn {
                items(children) { doc ->
                    Text(
                        text = if (doc.isDirectory)
                            "📁 ${doc.name}"
                        else
                            doc.name ?: "",
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (doc.isDirectory) {
                                    currentSafDir = doc
                                } else {
                                    val file =
                                        SafFileCopier.copyToCache(
                                            context,
                                            doc.uri
                                        )
                                    onFileSelected(file)
                                }
                            }
                            .padding(12.dp)
                    )
                }
            }
            return@Column
        }

        // ───────── NORMAL FILESYSTEM ─────────
        val visibleFolders = remember(currentDir) {
            currentDir.listFiles()
                ?.filter { it.isDirectory && it.containsVideo() }
                ?.sortedBy { it.name.lowercase() }
                ?: emptyList()
        }

        val folderContents = remember(currentDir) {
            currentDir.listFiles()
                ?.filter {
                    it.isVideoFile() || (it.isDirectory && it.containsVideo())
                }
                ?.sortedWith(
                    compareBy<File> { !it.isDirectory }
                        .thenBy { it.name.lowercase() }
                )
                ?: emptyList()
        }

        val isRootLevel = currentDir.absolutePath == initialDir.absolutePath
        val itemsToShow = if (isRootLevel) visibleFolders else folderContents

        Text(
            text = currentDir.absolutePath,
            modifier = Modifier.padding(8.dp),
            style = MaterialTheme.typography.labelMedium
        )

        LazyColumn {
            items(itemsToShow) { file ->
                if (file.isDirectory) {
                    // Folder UI
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .clickable { currentDir = file },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Folder,
                                contentDescription = null
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = file.name,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                } else {
                    // Video file UI
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .clickable { onFileSelected(file) },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = null
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = file.name,
                                maxLines = 1,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            }
        }
    }
}