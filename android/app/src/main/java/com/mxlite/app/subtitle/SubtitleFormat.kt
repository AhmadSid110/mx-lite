package com.mxlite.app.subtitle

sealed class SubtitleFormat {

    abstract val cues: List<SubtitleCue>

    data class Srt(
        override val cues: List<SubtitleCue>
    ) : SubtitleFormat()

    data class Ass(
        override val cues: List<SubtitleCue>
    ) : SubtitleFormat()
}
