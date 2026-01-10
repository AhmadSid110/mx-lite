package com.mxlite.app.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mxlite.app.player.NativeAudioDebug

/**
 * Debug overlay showing native audio engine state.
 * Always visible, no interaction required.
 * Replaces logcat for on-device debugging.
 */
@Composable
fun AudioDebugOverlay(
    debugState: NativeAudioDebug.DebugState,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(
                Color.Black.copy(alpha = 0.75f),
                RoundedCornerShape(8.dp)
            )
            .padding(12.dp)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "🔍 NATIVE AUDIO DEBUG",
                style = MaterialTheme.typography.labelSmall,
                color = Color.Yellow,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp
            )
            
            DebugRow("Engine created", debugState.engineCreated)
            DebugRow("AAudio opened", debugState.aaudioOpened)
            DebugRow("AAudio started", debugState.aaudioStarted)
            DebugRow("Callback running", debugState.callbackCalled)
            DebugRow("Decoder producing", debugState.decoderProduced)
            
            Text(
                text = "Buffer fill: ${debugState.bufferFill} frames",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp
            )
            
            Text(
                text = "Clock: ${debugState.clockPositionMs} ms",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp
            )
        }
    }
}

@Composable
private fun DebugRow(
    label: String,
    value: Boolean
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = if (value) "✅" else "❌",
            fontSize = 10.sp
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (value) Color.Green else Color.Red,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp
        )
    }
}
