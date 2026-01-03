
package com.mxlite.app.ui

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import com.mxlite.app.subtitle.ass.AssSubtitleRenderer

@Composable
fun AssSubtitleOverlay(
    modifier: Modifier = Modifier,
    renderer: AssSubtitleRenderer?,
    getTimeMs: () -> Long
) {
    val bmp by remember {
        derivedStateOf {
            renderer?.render(getTimeMs())
        }
    }

    bmp?.let {
        Image(
            bitmap = it.asImageBitmap(),
            contentDescription = "ASS Subtitles",
            modifier = modifier
        )
    }
}
