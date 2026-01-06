package com.mxlite.app.subtitle

class SubtitleController(
    private val subtitles: List<SubtitleLine>
) {
    fun current(positionMs: Long): SubtitleLine? =
        subtitles.firstOrNull {
            positionMs in it.startMs..it.endMs
        }
}
