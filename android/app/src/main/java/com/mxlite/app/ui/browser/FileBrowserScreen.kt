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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.mxlite.app.storage.SafFileCopier
import com.mxlite.app.storage.StorageStore
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileBrowserScreen(
    onFileSelected: (File) -> Unit
) {
    // ───────── State (MUST be before BackHandler logic uses it) ─────────
    var currentDir by remember { mutableStateOf(File("/storage/emulated/0")) }
    var currentSafDir by remember { mutableStateOf<DocumentFile?>(null) }

    // ───────── BACK HANDLER (CRITICAL FIX) ─────────
    BackHandler(
        enabled = currentSafDir != null || currentDir.parentFile != null
    ) {
        when {
            currentSafDir != null -> {
                currentSafDir = null
            }
            currentDir.parentFile != null -> {
                currentDir = currentDir.parentFile!!
            }
        }
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
        val files = remember(currentDir) {
            currentDir.listFiles()
                ?.sortedBy { !it.isDirectory }
                ?: emptyList()
        }

        Text(
            text = currentDir.absolutePath,
            modifier = Modifier.padding(8.dp),
            style = MaterialTheme.typography.labelMedium
        )

        LazyColumn {
            items(files) { file ->
                Text(
                    text = if (file.isDirectory)
                        "📁 ${file.name}"
                    else
                        file.name,
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