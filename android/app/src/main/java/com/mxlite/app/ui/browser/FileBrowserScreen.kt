package com.mxlite.app.ui.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileBrowserScreen(
    onFileSelected: (File) -> Unit
) {
    var currentDir by remember { mutableStateOf(File("/storage/emulated/0")) }
    val files = remember(currentDir) {
        currentDir.listFiles()?.sortedBy { !it.isDirectory } ?: emptyList()
    }

    Column {
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
