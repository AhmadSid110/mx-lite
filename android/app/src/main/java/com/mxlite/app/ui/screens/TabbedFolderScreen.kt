package com.mxlite.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    // 🛡️ GUARD: Empty folder check
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

    // 1. STATE SAVING: rememberSaveable ensures tab selection survives screen rotation
    var selectedTabIndex by rememberSaveable { mutableIntStateOf(0) }

    // 2. SCROLL MEMORY: Create a separate scroll state for EACH tab (remembered once to avoid reallocation)
    val scrollStates = remember {
        listOf(
            androidx.compose.foundation.lazy.LazyListState(),
            androidx.compose.foundation.lazy.LazyListState(),
            androidx.compose.foundation.lazy.LazyListState()
        )
    }

    // 3. PERFORMANCE: Pre-compute filtered lists ONCE
    val recentVideos = remember(folder.videos) {
        folder.videos.sortedByDescending { it.dateAdded }
    }
    val largeVideos = remember(folder.videos) {
        folder.videos.filter { it.size > 100 * 1024 * 1024 }
    }

    val displayedVideos = when (selectedTabIndex) {
        0 -> folder.videos
        1 -> recentVideos
        2 -> largeVideos
        else -> folder.videos
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
            // 4. APPLY SCROLL STATE
            state = scrollStates[selectedTabIndex]
        ) {
            items(
                items = displayedVideos,
                key = { it.id }
            ) { video ->
                // ✅ CORRECTED: Matches Phase 3 signature (No filePath yet)
                CompactVideoRow(
                    videoTitle = video.name,
                    duration = video.durationFormatted,
                    fileSize = video.sizeFormatted,
                    thumbnail = video.uri,
                    onClick = { onVideoClick(video) }
                )
            }
        }
    }
}
