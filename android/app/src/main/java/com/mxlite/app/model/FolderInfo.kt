package com.mxlite.app.model

import java.util.Locale

/**
 * Folder model for home and folder screens.
 */
data class FolderInfo(
    val name: String,
    val videoCount: Int,
    val totalSize: Long,
    val videos: List<VideoFile>
) {
    val totalSizeFormatted: String
        get() {
            val mb = totalSize / (1024.0 * 1024.0)
            return if (mb > 1000) {
                "%.2f GB".format(Locale.US, mb / 1024)
            } else {
                "%.2f MB".format(Locale.US, mb)
            }
        }
}