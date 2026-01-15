package com.mxlite.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mxlite.app.model.FolderInfo
import com.mxlite.app.ui.components.ModernFolderItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    folders: List<FolderInfo>, 
    onFolderClick: (FolderInfo) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        "Library", 
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.headlineMedium
                    ) 
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)
        ) {
            items(
                items = folders,
                key = { it.name } // ✅ Stable Key for performance
            ) { folder ->
                ModernFolderItem(
                    folderName = folder.name,
                    videoCount = folder.videoCount,
                    folderSize = folder.totalSizeFormatted,
                    onClick = { onFolderClick(folder) }
                )
            }
        }
    }
}