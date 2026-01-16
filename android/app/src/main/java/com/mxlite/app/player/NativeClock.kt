package com.mxlite.app.player

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Native clock implementation.
 * Reads master clock from C++ audio engine.
 */
class NativeClock : PlaybackClock {
    override val positionMs: Long
    get() = NativePlayer.virtualClockUs() / 1000
}

/**
 * Standalone monotonic clock used for video timing / UI when audio is absent or unhealthy.
 * This is intentionally independent of JNI and uses System.nanoTime() for monotonic timing.
 */
class StandaloneMediaClock {

    private var startTimeNs: Long = 0L
    private var basePositionUs: Long = 0L
    private val running = AtomicBoolean(false)

    fun start(startPositionUs: Long = 0L) {
        basePositionUs = startPositionUs
        startTimeNs = System.nanoTime()
        running.set(true)
    }

    fun stop() {
        running.set(false)
    }

    fun reset() {
        basePositionUs = 0L
        startTimeNs = System.nanoTime()
    }

    fun positionUs(): Long {
        if (!running.get()) return basePositionUs
        val elapsedNs = System.nanoTime() - startTimeNs
        return basePositionUs + (elapsedNs / 1_000)
    }
}
