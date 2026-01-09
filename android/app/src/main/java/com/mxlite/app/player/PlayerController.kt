package com.mxlite.app.player

import android.view.Surface
import java.io.File

/**
 * Central playback coordinator.
 *
 * - Audio = MASTER clock
 * - Video follows audio
 * - Silent videos are handled correctly
 * - No ExoPlayer / Media3
 */
class PlayerController : PlayerEngine {

    private val audio = AudioCodecEngine()
    private val video = MediaCodecEngine(clock = audio)

    private var hasAudio = false

    /* ───────────────────────────────────────────── */
    /* PlayerEngine implementation */
    /* ───────────────────────────────────────────── */

    override val durationMs: Long
        get() = video.durationMs

    override val currentPositionMs: Long
        get() =
            if (hasAudio) audio.positionMs
            else video.currentPositionMs

    override val isPlaying: Boolean
        get() = video.isPlaying

    override fun attachSurface(surface: Surface) {
        video.attachSurface(surface)
    }

    override fun play(file: File) {
        release()

        // 🔑 Detect audio track BEFORE starting decoders
        hasAudio = audio.hasAudioTrack(file)

        if (hasAudio) {
            audio.reset()
            audio.play(file)
        }

        video.play(file)
    }

    override fun pause() {
        audio.pause()
        video.pause()
    }

    override fun seekTo(positionMs: Long) {
        if (hasAudio) {
            audio.seekTo(positionMs)
        }
        video.seekTo(positionMs)
    }

    override fun release() {
        audio.release()
        video.release()
        hasAudio = false
    }
}
