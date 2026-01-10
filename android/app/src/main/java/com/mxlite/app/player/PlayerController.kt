package com.mxlite.app.player

import android.view.Surface
import java.io.File

class PlayerController : PlayerEngine {

    private val nativeClock = NativeClock()
    private val video = MediaCodecEngine(clock = nativeClock)

    // ONLY for detecting audio track
    private val legacyAudio = AudioCodecEngine()

    private var hasAudio = false

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

        // 1️⃣ Detect audio track
        hasAudio = legacyAudio.hasAudioTrack(file)

        // 2️⃣ Start native audio FIRST (if present)
        if (hasAudio) {
            NativePlayer.play(context, file.absolutePath)
        }

        // 3️⃣ Start video immediately
        // 🔑 Video will sync itself to audio clock when audio becomes active
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