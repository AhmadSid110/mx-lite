package com.mxlite.app.ui.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.io.File

@Composable
fun FileBrowserScreen(
    onFileSelected: (File) -> Unit
) {
    var currentDir by remember {
        mutableStateOf(File("/storage/emulated/0"))
    }

    val files = remember(currentDir) {
        currentDir.listFiles()
            ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            ?: emptyArray()
    }

    Column {
        Text(
            text = currentDir.absolutePath,
            modifier = Modifier.padding(8.dp)
        )

        LazyColumn {
            currentDir.parentFile?.let { parent ->
                item {
                    Text(
                        "..",
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { currentDir = parent }
                            .padding(12.dp)
                    )
                }
            }

            items(files.toList()) { file ->
                Text(
                    text = file.name,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (file.isDirectory) currentDir = file
                            else onFileSelected(file)
                        }
                        .padding(12.dp)
                )
            }
        }
    }
}