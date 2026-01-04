
package com.mxlite.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.mxlite.app.player.PlayerViewModel

@Composable
fun FileBrowserScreen(
    context: Context,
    vm: PlayerViewModel,
    onClose: () -> Unit
) {
    var folderUri by remember { mutableStateOf<Uri?>(null) }
    var files by remember { mutableStateOf<List<DocumentFile>>(emptyList()) }

    val folderPicker =
        rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocumentTree()
        ) { uri ->
            uri ?: return@rememberLauncherForActivityResult
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            folderUri = uri
            files = DocumentFile.fromTreeUri(context, uri)
                ?.listFiles()
                ?.filter { it.isFile && it.name?.endsWith(".mp4") == true }
                ?: emptyList()
        }

    Column(modifier = Modifier.fillMaxSize()) {

        TopAppBar(
            title = { Text("Browse") },
            navigationIcon = {
                IconButton(onClick = onClose) {
                    Text("Back")
                }
            }
        )

        Button(
            modifier = Modifier.padding(8.dp),
            onClick = { folderPicker.launch(null) }
        ) {
            Text("Pick Folder")
        }

        LazyColumn {
            items(files) { file ->
                ListItem(
                    headlineText = { Text(file.name ?: "") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            vm.play(file.uri)
                            onClose()
                        }
                )
                Divider()
            }
        }
    }
}
