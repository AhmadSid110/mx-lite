package com.mxlite.app.player

object PlaybackClock {
    @Volatile
    var audioPositionMs: Long = 0
}
