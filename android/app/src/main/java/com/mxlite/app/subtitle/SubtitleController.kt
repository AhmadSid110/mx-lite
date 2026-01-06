package com.mxlite.app.subtitle

import android.content.Context
import android.net.Uri

/**
 * Manages subtitle tracks and current subtitle display.
 * Supports multiple tracks and track switching.
 */
class SubtitleController(
    private val context: Context,
    initialTrack: SubtitleTrack? = null
) {
    private var subtitles: List<SubtitleCue> = emptyList()
    private var currentIndex = 0
    private var _currentTrack: SubtitleTrack? = initialTrack
    
    val currentTrack: SubtitleTrack? get() = _currentTrack
    val availableTracks = mutableListOf<SubtitleTrack>()

    init {
        initialTrack?.let { track ->
            availableTracks.add(track)
            loadTrack(track)
        }
    }

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
            is SubtitleTrack.FileTrack -> SubtitleParser.parseFile(track.file)
            is SubtitleTrack.SafTrack -> SubtitleParser.parseUri(context, track.uri)
        }
        currentIndex = 0
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
