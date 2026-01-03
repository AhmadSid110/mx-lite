package com.mxlite.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun PlayerControls(onPlay: () -> Unit, onPause: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        Button(onClick = onPlay) { Text("Play") }
        Spacer(modifier = Modifier.width(16.dp))
        Button(onClick = onPause) { Text("Pause") }
    }
}