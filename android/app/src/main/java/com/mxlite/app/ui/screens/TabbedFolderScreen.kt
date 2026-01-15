package com.mxlite.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mxlite.app.model.FolderInfo
import com.mxlite.app.model.VideoFile
import com.mxlite.app.ui.components.CompactVideoRow
import com.mxlite.app.ui.theme.CinemaAccent
import com.mxlite.app.ui.theme.CinemaBackground
import com.mxlite.app.ui.theme.TextPrimary
import com.mxlite.app.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabbedFolderScreen(
    folder: FolderInfo,
    onVideoClick: (VideoFile) -> Unit,
    onBack: () -> Unit
) {
    // 🛡️ STEP 3: Guard against empty folders immediately
    if (folder.videos.isEmpty()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(folder.name, color = TextPrimary) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = TextPrimary)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = CinemaBackground)
                )
            },
            containerColor = CinemaBackground
        ) { padding ->
            Box(modifier = Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No videos found", color = TextSecondary)
            }
        }
        return
    }

    val tabs = listOf("All Videos", "Recent", "Large (>100MB)")
    var selectedTabIndex by remember { mutableIntStateOf(0) }

    val displayedVideos = remember(selectedTabIndex, folder.videos) {
        when (selectedTabIndex) {
            0 -> folder.videos
            1 -> folder.videos.sortedByDescending { it.dateAdded }
            2 -> folder.videos.filter { it.size > 100 * 1024 * 1024 }
            else -> folder.videos
        }
    }

    Scaffold(
        topBar = {
            Column(modifier = Modifier.fillMaxWidth()) {
                TopAppBar(
                    title = {
                        Text(
                            text = folder.name,
                            color = TextPrimary,
                            style = MaterialTheme.typography.titleLarge
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = "Back",
                                tint = TextPrimary
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = CinemaBackground)
                )

                ScrollableTabRow(
                    selectedTabIndex = selectedTabIndex,
                    containerColor = CinemaBackground,
                    contentColor = CinemaAccent,
                    edgePadding = 16.dp,
                    indicator = { tabPositions ->
                        // 🛡️ STEP 2: Harden Tab Indicator bounds check
                        if (selectedTabIndex < tabPositions.size) {
                            TabRowDefaults.SecondaryIndicator(
                                modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTabIndex]),
                                color = CinemaAccent
                            )
                        }
                    },
                    divider = {}
                ) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTabIndex == index,
                            onClick = { selectedTabIndex = index },
                            text = {
                                Text(
                                    text = title,
                                    color = if (selectedTabIndex == index) CinemaAccent else TextPrimary.copy(alpha = 0.7f)
                                )
                            }
                        )
                    }
                }
            }
        },
        containerColor = CinemaBackground
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(
                items = displayedVideos,
                // 🛡️ STEP 1: Use safe ID as key (prevents duplicate path crash)
                key = { it.id } 
            ) { video ->
                CompactVideoRow(
                    videoTitle = video.name,
                    duration = video.durationFormatted,
                    fileSize = video.sizeFormatted,
                    thumbnail = null, 
                    onClick = { onVideoClick(video) }
                )
            }
        }
    }
}