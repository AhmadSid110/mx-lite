package com.mxlite.app.subtitle

class SubtitleController(
    private val subtitles: List<SubtitleCue>
) {
    private var currentIndex = 0

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
