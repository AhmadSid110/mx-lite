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
import com.mxlite.app.persistTreePermission
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

    var currentDir by remember { mutableStateOf(File("/storage/emulated/0")) }
    var safFolders by remember { mutableStateOf<List<Uri>>(emptyList()) }

    // Load persisted SAF folders once
    LaunchedEffect(Unit) {
        safFolders = store.getFolders()
    }

    val files = remember(currentDir) {
        currentDir.listFiles()?.sortedBy { !it.isDirectory } ?: emptyList()
    }

    // 🔐 SAF folder picker
    val folderPicker =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocumentTree()
        ) { uri ->
            if (uri != null) {
                persistTreePermission(context, uri)

                scope.launch {
                    store.addFolder(uri)
                    safFolders = store.getFolders() // refresh
                }
            }
        }

    Column {
        // 🔝 Top bar with SAF button
        TopAppBar(
            title = { Text("File Browser") },
            actions = {
                TextButton(onClick = { folderPicker.launch(null) }) {
                    Text("Pick Folder")
                }
            }
        )

        // 🔐 SAF folders section
        if (safFolders.isNotEmpty()) {
            Text(
                text = "Pinned folders",
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
                                // SAF browsing comes in SAF-3
                            }
                            .padding(12.dp)
                    )
                }
            }

            Divider()
        }

        // 📂 Normal filesystem browser
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
