package com.mxlite.app.player

/**
 * Single source of truth for playback time.
 * Audio engine will implement this.
 */
interface PlaybackClock {
    val positionMs: Long
}
