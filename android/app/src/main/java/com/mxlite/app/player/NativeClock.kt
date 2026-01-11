package com.mxlite.app.player

/**
 * Native clock implementation.
 * Reads master clock from C++ audio engine.
 */
class NativeClock : PlaybackClock {
    override val positionMs: Long
    get() = NativePlayer.getClockUs() / 1000
}
