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

        if (hasAudio) {
            NativePlayer.play(context, file.absolutePath)
        }

        video.play(file)
        playing = true
    }

    override fun pause() {
        if (!playing) return
        playing = false

        if (hasAudio) {
            video.setRenderEnabled(false) // 🔑 FIX
            NativePlayer.nativePause()
        }
    }

    override fun resume() {
        if (playing) return
        playing = true

        if (hasAudio) {
            video.setRenderEnabled(true) // 🔑 FIX
            NativePlayer.nativeResume()
        }
    }

    override fun seekTo(positionMs: Long) {
        val wasPlaying = playing

        if (hasAudio) {
            NativePlayer.nativePause()
            NativePlayer.nativeSeek(positionMs * 1000)
        }

        currentFile?.let {
            video.release()
            video.play(it)
        }

        if (wasPlaying && hasAudio) {
            video.setRenderEnabled(true)
            NativePlayer.nativeResume()
        }
    }

    override fun release() {
        if (hasAudio) NativePlayer.release()
        legacyAudio.release()
        video.release()

        hasAudio = false
        playing = false
        currentFile = null
    }
}