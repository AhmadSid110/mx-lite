
package com.mxlite.app.ui

import androidx.compose.runtime.*
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.*
import com.mxlite.app.subtitle.SubtitleTrack

@Composable
fun SubtitleOverlay(
    modifier: Modifier = Modifier,
    subtitleTrack: SubtitleTrack?,
    getCurrentTimeMs: () -> Long
) {
    val subtitleText by remember {
        derivedStateOf {
            subtitleTrack
                ?.getSubtitleAt(getCurrentTimeMs())
                ?.text ?: ""
        }
    }

    if (subtitleText.isNotEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.BottomCenter
        ) {
            Text(
                text = subtitleText,
                color = Color.White,
                fontSize = 18.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(16.dp)
            )
        }
    }
}
