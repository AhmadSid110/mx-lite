package com.mxlite.app.model

import android.net.Uri
import android.provider.MediaStore
import java.util.Locale

data class VideoFile(
    val id: String,
    val path: String,
    val name: String,
    val size: Long,
    val duration: Long,
    val dateAdded: Long
) {
    // ✅ Computed content URI (preferred over file paths)
    val thumbnailUri: Uri
        get() = Uri.withAppendedPath(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            id
        )

    // Convenience alias expected by caller code
    val uri: Uri get() = thumbnailUri

    val sizeFormatted: String
        get() {
            val mb = size / (1024.0 * 1024.0)
            return if (mb > 1000) {
                "%.2f GB".format(Locale.US, mb / 1024)
            } else {
                "%.2f MB".format(Locale.US, mb)
            }
        }

    val durationFormatted: String
        get() {
            val totalSeconds = duration / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return "%02d:%02d".format(Locale.US, minutes, seconds)
        }
}  