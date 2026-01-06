package com.mxlite.app.subtitle

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/**
 * Manages subtitle tracks and current subtitle display.
 * Supports multiple tracks and track switching.
 * Owns all subtitle parsing logic directly.
 */
class SubtitleController(
    private val context: Context
) {
    private var subtitles: List<SubtitleCue> = emptyList()
    private var currentIndex = 0
    private var _currentTrack: SubtitleTrack? = null
    
    val currentTrack: SubtitleTrack? get() = _currentTrack
    val availableTracks = mutableListOf<SubtitleTrack>()

    /**
     * Add a subtitle track to available tracks
     */
    fun addTrack(track: SubtitleTrack) {
        if (availableTracks.none { it.id == track.id }) {
            availableTracks.add(track)
        }
    }

    /**
     * Select and load a track by ID
     */
    suspend fun selectTrack(trackId: String?) {
        val track = availableTracks.find { it.id == trackId }
        if (track != null) {
            _currentTrack = track
            loadTrack(track)
        } else {
            _currentTrack = null
            subtitles = emptyList()
            currentIndex = 0
        }
    }

    /**
     * Load subtitles from a track
     */
    private suspend fun loadTrack(track: SubtitleTrack) {
        subtitles = when (track) {
            is SubtitleTrack.FileTrack -> loadFromFile(track.file)
            is SubtitleTrack.SafTrack -> loadFromUri(track.uri)
        }
        currentIndex = 0
    }

    /**
     * Load subtitles from a File
     */
    private suspend fun loadFromFile(file: File): List<SubtitleCue> = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext emptyList()
        runCatching {
            file.inputStream().use { stream ->
                parseFromStream(stream, file.name)
            }
        }.getOrElse { emptyList() }
    }

    /**
     * Load subtitles from a Uri (SAF)
     */
    private suspend fun loadFromUri(uri: Uri): List<SubtitleCue> = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                parseFromStream(stream, uri.lastPathSegment ?: "")
            } ?: emptyList()
        }.getOrElse { emptyList() }
    }

    /**
     * Parse subtitles from an InputStream
     */
    private fun parseFromStream(stream: InputStream, fileName: String): List<SubtitleCue> {
        val name = fileName.lowercase()
        return when {
            name.endsWith(".srt") -> SrtParser.parse(stream)
            name.endsWith(".ass") || name.endsWith(".ssa") -> AssParser.parse(stream)
            else -> {
                // Try to detect format by content
                val lines = stream.bufferedReader().readLines()
                detectFormatAndParse(lines)
            }
        }
    }

    /**
     * Detect format from content and parse
     */
    private fun detectFormatAndParse(lines: List<String>): List<SubtitleCue> {
        val hasAssMarkers = lines.any { line ->
            val trimmed = line.trim()
            trimmed.startsWith("[Script Info]", ignoreCase = true) ||
            trimmed.startsWith("[Events]", ignoreCase = true) ||
            trimmed.startsWith("Format:", ignoreCase = true) ||
            trimmed.startsWith("Dialogue:", ignoreCase = true)
        }

        return if (hasAssMarkers) {
            AssParser.parse(lines.joinToString("\n").byteInputStream())
        } else {
            SrtParser.parse(lines.joinToString("\n").byteInputStream())
        }
    }

    /**
     * Get the current subtitle for the given position
     */
    fun current(positionMs: Long): SubtitleCue? {
        if (subtitles.isEmpty()) return null
        while (currentIndex > 0 && positionMs < subtitles[currentIndex].startMs) {
            currentIndex--
        }
        while (currentIndex < subtitles.size - 1 && positionMs > subtitles[currentIndex].endMs) {
            currentIndex++
        }
        val current = subtitles.getOrNull(currentIndex)
        return if (current != null && positionMs in current.startMs..current.endMs) current else null
    }
}
