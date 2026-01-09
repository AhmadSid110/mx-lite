package com.mxlite.app.player

/**
 * JNI bridge to native audio engine.
 * Audio is the MASTER clock.
 * All timing comes from C++.
 */
object NativePlayer {
    init {
        System.loadLibrary("mxplayer")
    }

    /**
     * Start native audio playback.
     * Must be called BEFORE video starts.
     */
    external fun nativePlay(path: String)

    /**
     * Stop native audio playback.
     */
    external fun nativeStop()

    /**
     * Get current playback position in microseconds.
     * This is the MASTER CLOCK.
     */
    external fun nativeGetClockUs(): Long

    /**
     * Seek to position in microseconds.
     */
    external fun nativeSeek(positionUs: Long)

    /**
     * Release native resources.
     */
    external fun nativeRelease()
}
