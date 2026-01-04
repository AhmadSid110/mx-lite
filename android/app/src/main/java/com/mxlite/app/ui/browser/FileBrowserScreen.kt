package com.mxlite.app.ui.browser

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
import com.mxlite.app.MainActivity
import com.mxlite.app.storage.StorageStore
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun FileBrowserScreen(
    onFileSelected: (File) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ===== SAF FOLDER PICKER (SAF+) =====
    val folderPicker =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocumentTree()
        ) { uri ->
            if (uri != null) {
                // Persist permission (Activity responsibility)
                (context as MainActivity).persistTreePermission(uri)

                // Save folder (StorageStore responsibility)
                scope.launch {
                    StorageStore(context).addFolder(uri)
                }
            }
        }

    // ===== LEGACY FILE BROWSER (UNCHANGED) =====
    var currentDir by remember { mutableStateOf(File("/storage/emulated/0")) }
    val files = remember(currentDir) {
        currentDir.listFiles()?.sortedBy { !it.isDirectory } ?: emptyList()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("File Browser") },
                actions = {
                    // SAF entry point (THIS IS CORRECT PLACE)
                    TextButton(onClick = { folderPicker.launch(null) }) {
                        Text("Add Folder")
                    }
                }
            )
        }
    ) { padding ->

        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {

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
}
