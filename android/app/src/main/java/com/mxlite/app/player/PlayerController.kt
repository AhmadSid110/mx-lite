package com.mxlite.app.player

import android.view.Surface
import java.io.File

/**
 * Central playback coordinator.
 *
 * RULES (NON-NEGOTIABLE):
 * - Native C++ audio is the ONLY master clock
 * - Video MUST NOT start until audio clock ADVANCES
 * - Java NEVER controls timing
 */
class PlayerController : PlayerEngine {

    private val nativeClock = NativeClock()
    private val video = MediaCodecEngine(clock = nativeClock)

    // Used ONLY to detect presence of audio track
    private val legacyAudio = AudioCodecEngine()

    private var hasAudio = false

    /* ───────────────────────────────────────────── */
    /* PlayerEngine implementation */
    /* ───────────────────────────────────────────── */

    override val durationMs: Long
        get() = video.durationMs

    override val currentPositionMs: Long
        get() =
            if (hasAudio) nativeClock.positionMs
            else video.currentPositionMs

    override val isPlaying: Boolean
        get() = video.isPlaying

    override fun attachSurface(surface: Surface) {
        video.attachSurface(surface)
    }

    override fun play(file: File) {
        release()

        // 1️⃣ Detect audio track BEFORE starting anything
        hasAudio = legacyAudio.hasAudioTrack(file)

        if (hasAudio) {
            // 2️⃣ Start NATIVE AUDIO FIRST
            NativePlayer.nativePlay(file.absolutePath)

            // 3️⃣ WAIT UNTIL AUDIO CLOCK ACTUALLY ADVANCES
            var lastClock = 0L
            var retries = 0

            while (retries < 200) { // ~2 seconds max
                val now = nativeClock.positionMs
                if (now > lastClock) {
                    break // 🔑 AUDIO IS RENDERING
                }
                lastClock = now
                Thread.sleep(10)
                retries++
            }
        }

        // 4️⃣ Start VIDEO ONLY AFTER AUDIO CLOCK IS LIVE
        video.play(file)
    }

    override fun pause() {
        if (hasAudio) {
            NativePlayer.nativeStop()
        }
        video.pause()
    }

    override fun seekTo(positionMs: Long) {
        if (hasAudio) {
            NativePlayer.nativeSeek(positionMs * 1000)
        }
        video.seekTo(positionMs)
    }

    override fun release() {
        if (hasAudio) {
            NativePlayer.nativeRelease()
        }
        legacyAudio.release()
        video.release()
        hasAudio = false
    }
}