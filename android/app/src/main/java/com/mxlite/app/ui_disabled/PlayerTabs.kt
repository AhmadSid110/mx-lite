
package com.mxlite.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mxlite.app.player.PlayerTab

@Composable
fun PlayerTabs(
    tabs: List<PlayerTab>,
    activeTabId: Long,
    onSelect: (PlayerTab) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(4.dp)
    ) {
        tabs.forEach { tab ->
            Text(
                text = tab.path.substringAfterLast('/'),
                modifier = Modifier
                    .padding(8.dp)
                    .clickable { onSelect(tab) },
                style = if (tab.id == activeTabId)
                    MaterialTheme.typography.titleMedium
                else
                    MaterialTheme.typography.bodyMedium
            )
        }
    }
}
