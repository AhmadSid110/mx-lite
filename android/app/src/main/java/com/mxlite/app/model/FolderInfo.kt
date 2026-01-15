package com.mxlite.app.model

/**
 * Simple model used by the Home screen.
 */
data class FolderInfo(
    val name: String,
    val path: String,
    val videoCount: Int,
    val totalSizeFormatted: String = "—"
)