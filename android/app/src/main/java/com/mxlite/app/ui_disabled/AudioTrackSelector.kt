
package com.mxlite.app.ui

import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.mxlite.app.player.AudioTrackInfo

@Composable
fun AudioTrackSelector(
    tracks: List<AudioTrackInfo>,
    onSelect: (AudioTrackInfo) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Button(onClick = { expanded = true }) {
        Text("Audio Track")
    }

    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        tracks.forEach { track ->
            DropdownMenuItem(
                text = {
                    Text(
                        (track.language ?: "Track ${track.index}") +
                        " • ${track.codec} • ${track.channels}ch"
                    )
                },
                onClick = {
                    expanded = false
                    onSelect(track)
                }
            )
        }
    }
}
