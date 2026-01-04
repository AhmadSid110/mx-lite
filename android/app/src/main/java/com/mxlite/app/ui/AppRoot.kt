package com.mxlite.app.ui

import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.mxlite.app.ui.browser.FileBrowserScreen

@Composable
fun AppRoot() {
    var tab by remember { mutableStateOf(0) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(tab == 0, { tab = 0 }, { Text("Home") })
                NavigationBarItem(tab == 1, { tab = 1 }, { Text("Files") })
                NavigationBarItem(tab == 2, { tab = 2 }, { Text("Player") })
                NavigationBarItem(tab == 3, { tab = 3 }, { Text("Settings") })
            }
        }
    ) {
        when (tab) {
            0 -> Text("Home")
            1 -> FileBrowserScreen { }
            2 -> Text("Player")
            3 -> Text("Settings")
        }
    }
}
