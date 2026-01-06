package com.mxlite.app.subtitle

/**
 * Sealed class representing different subtitle formats.
 * Each format contains timing and text information.
 */
sealed class SubtitleFormat {
    
    /**
     * SRT (SubRip) subtitle format
     */
    data class Srt(
        val cues: List<SubtitleCue>
    ) : SubtitleFormat()
    
    /**
     * ASS/SSA (Advanced SubStation Alpha / SubStation Alpha) subtitle format
     * Converted to simple timing + text (advanced features ignored)
     */
    data class Ass(
        val cues: List<SubtitleCue>
    ) : SubtitleFormat()
    
    /**
     * Get cues from any format
     */
    fun getCues(): List<SubtitleCue> = when (this) {
        is Srt -> cues
        is Ass -> cues
    }
}
