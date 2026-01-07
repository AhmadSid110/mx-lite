package com.mxlite.app.subtitle

import androidx.compose.ui.graphics.Color

/**
 * Styling information for subtitle text.
 * Supports basic ASS/SSA styling without complex rendering.
 */
data class SubtitleStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val fontSizeSp: Float? = null,
    val color: Color? = null
)

/**
 * A single subtitle cue with timing and optional styling.
 */
data class SubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val style: SubtitleStyle = SubtitleStyle()
)
