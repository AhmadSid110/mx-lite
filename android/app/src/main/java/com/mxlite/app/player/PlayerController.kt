package com.mxlite.app.player

import android.view.Surface
import java.io.File

/**
 * Central playback coordinator.
 *
 * - Native audio = MASTER clock (C++)
 * - Video follows native clock
 * - Silent videos are handled correctly
 * - No ExoPlayer / Media3
 */
class PlayerController : PlayerEngine {

    private val nativeClock = NativeClock()
    private val video = MediaCodecEngine(clock = nativeClock)
    private val legacyAudio = AudioCodecEngine()  // For detecting audio tracks only

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

        // 🔑 Detect audio track BEFORE starting decoders
        hasAudio = legacyAudio.hasAudioTrack(file)

        if (hasAudio) {
            // ✅ Start NATIVE audio FIRST
            NativePlayer.nativePlay(file.absolutePath)
            
            // ⏳ Wait until native clock starts (non-zero)
            var retries = 0
            while (nativeClock.positionMs == 0L && retries < 100) {
                Thread.sleep(10)
                retries++
            }
        }

        // ✅ Start video AFTER native audio is running
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
