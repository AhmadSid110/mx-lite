package com.mxlite.app.subtitle

import android.net.Uri
import java.io.File

/**
 * Represents a subtitle track source.
 * Each track has a unique ID for persistence and selection.
 */
sealed class SubtitleTrack {
    abstract val id: String
    abstract val displayName: String

    /**
     * Subtitle track from a File source
     */
    data class FileTrack(
        val file: File
    ) : SubtitleTrack() {
        override val id: String = "file:${file.absolutePath}"
        override val displayName: String = file.name
    }

    /**
     * Subtitle track from SAF (Storage Access Framework) URI
     */
    data class SafTrack(
        val uri: Uri,
        val name: String
    ) : SubtitleTrack() {
        override val id: String = "saf:${uri}"
        override val displayName: String = name
    }
}
