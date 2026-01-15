package com.mxlite.app.player

import android.content.Context
import android.view.Surface
import java.io.File

class PlayerController(
    private val context: Context
) : PlayerEngine {

    private val nativeClock = NativeClock()
    private val video = MediaCodecEngine(nativeClock)
    private val legacyAudio = AudioCodecEngine()

    private var hasAudio = false
    private var playing = false
    private var currentFile: File? = null
    private var seeking = false

    override val durationMs: Long
        get() = video.durationMs

    override val currentPositionMs: Long
        get() = if (hasAudio)
            NativePlayer.getClockUs() / 1000
        else
            video.currentPositionMs

    override val isPlaying: Boolean
        get() = playing

    override fun attachSurface(surface: Surface) {
        video.attachSurface(surface)
    }

    override fun play(file: File) {
        release()
        currentFile = file

        hasAudio = legacyAudio.hasAudioTrack(file)

        // 🔑 ALWAYS allow render on fresh play
        video.setRenderEnabled(true)

        if (hasAudio) {
            NativePlayer.play(context, file.absolutePath)
        }

        // 🔑 IMPORTANT
        if (video.hasSurface()) {
            video.play(file)
            playing = true
        }
    }

    override fun pause() {
        if (!playing) return
        playing = false

        // 🔑 ALWAYS disable render when paused
        video.setRenderEnabled(false)

        if (hasAudio) {
            NativePlayer.nativePause()
        }
    }

    override fun resume() {
        if (playing) return
        playing = true

        // 🔑 Re-enable render BEFORE audio resumes
        video.setRenderEnabled(true)

        if (hasAudio) {
            NativePlayer.nativeResume()
        }
    }

    override fun seekTo(positionMs: Long) {
        if (currentFile == null) return

        val wasPlaying = playing
        seeking = true
        playing = false

        // 🔑 HARD STOP video rendering during seek
        video.setRenderEnabled(false)

        if (hasAudio) {
            NativePlayer.nativePause()
            NativePlayer.nativeSeek(positionMs * 1000)
        }

        // 🔁 Recreate video cleanly
        video.release()
        video.play(currentFile!!)

        seeking = false

        if (wasPlaying) {
            video.setRenderEnabled(true)
            playing = true

            if (hasAudio) {
                NativePlayer.nativeResume()
            }
        }
    }

    override fun release() {
        video.setRenderEnabled(false)

        if (hasAudio) NativePlayer.release()
        legacyAudio.release()
        video.release()

        hasAudio = false
        playing = false
        seeking = false
        currentFile = null
    }
}