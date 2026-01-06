package com.mxlite.app.ui.browser

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.mxlite.app.storage.SafBrowser
import com.mxlite.app.storage.StorageStore
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileBrowserScreen(
    onFileSelected: (File) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { StorageStore(context) }
    val safBrowser = remember { SafBrowser(context) }

    // ───────── NORMAL FS STATE ─────────
    var currentDir by remember { mutableStateOf(File("/storage/emulated/0")) }

    // ───────── SAF STATE ─────────
    var safFolders by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var currentSafDir by remember { mutableStateOf<DocumentFile?>(null) }

    LaunchedEffect(Unit) {
        safFolders = store.getFolders()
    }

    val folderPicker =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocumentTree()
        ) { uri ->
            if (uri != null) {
                persistTreePermission(context, uri)
                scope.launch {
                    store.addFolder(uri)
                    safFolders = store.getFolders()
                }
            }
        }

    Column {

        // ✅ FIXED TopAppBar (NO NULL LAMBDAS)
        TopAppBar(
            title = {
                Text(if (currentSafDir != null) "SAF Browser" else "File Browser")
            },
            navigationIcon = {
                if (currentSafDir != null) {
                    IconButton(onClick = { currentSafDir = null }) {
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
            Text(
                "Pinned folders",
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.labelLarge
            )

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
                safBrowser.listChildren(currentSafDir!!)
            }

            LazyColumn {
                items(children) { doc ->
                    Text(
                        text = if (doc.isDirectory) "📁 ${doc.name}" else doc.name.orEmpty(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (doc.isDirectory) {
                                    currentSafDir = doc
                                } else {
                                    // SAF file playback → SAF-5
                                }
                            }
                            .padding(12.dp)
                    )
                }
            }
            return@Column
        }

        // ───────── NORMAL FILESYSTEM VIEW ─────────
        val files = remember(currentDir) {
            currentDir.listFiles()?.sortedBy { !it.isDirectory } ?: emptyList()
        }

        Text(
            text = currentDir.absolutePath,
            modifier = Modifier.padding(8.dp),
            style = MaterialTheme.typography.labelMedium
        )

        LazyColumn {
            items(files) { file ->
                Text(
                    text = if (file.isDirectory) "📁 ${file.name}" else file.name,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (file.isDirectory) {
                                currentDir = file
                            } else {
                                onFileSelected(file)
                            }
                        }
                        .padding(12.dp)
                )
            }
        }
    }
}
