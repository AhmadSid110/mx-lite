package com.mxlite.app.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import com.mxlite.app.player.NativeAudioDebug
import com.mxlite.app.player.PlayerEngine

@Composable
fun AudioDebugOverlay(
    engine: PlayerEngine,
    hasSurface: Boolean,
    modifier: Modifier = Modifier
) {
    var text by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        while (true) {
            text = NativeAudioDebug.snapshot(engine, hasSurface)
            delay(500)
        }
    }

    Text(
        text = text,
        color = Color.Red,
        modifier = modifier
            .background(Color.Black)
            .padding(8.dp)
    )
}

