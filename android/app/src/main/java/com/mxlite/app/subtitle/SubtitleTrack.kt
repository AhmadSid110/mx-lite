
package com.mxlite.app.subtitle

class SubtitleTrack(
    private val subtitles: List<Subtitle>
) {
    fun getSubtitleAt(timeMs: Long): Subtitle? {
        return subtitles.firstOrNull {
            timeMs in it.startMs..it.endMs
        }
    }
}
