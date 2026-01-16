package com.mxlite.app.player

import android.content.Context
import android.net.Uri
import android.view.Surface
import android.os.ParcelFileDescriptor
import java.io.File

class PlayerController(
    private val context: Context
) : PlayerEngine {

    private val nativeClock = NativeClock()
    private val standaloneClock = StandaloneMediaClock()

    private val masterClock = object : PlaybackClock {
        override val positionMs: Long
            get() {
                return if (hasAudio) {
                    // Native audio track exists: audio is master
                    NativePlayer.getClockUs() / 1000
                } else {
                    // No audio track: standalone clock is master
                    standaloneClock.positionUs() / 1000
                }
            }
    }

    private val video = MediaCodecEngine(context, masterClock)

    private var hasAudio = false
    private var playing = false
    private var seeking = false

    // Latch the UI position when paused so the UI freezes cleanly
    private var pausedPositionMs: Long = 0
    // Tracks whether we've observed a valid audio clock after resume/seek
    private var audioClockReady = false

    // Track the currently playing content URI (if any)
    override var currentUri: Uri? = null
        private set

    override val durationMs: Long
        get() = video.durationMs

    override val currentPositionMs: Long
        get() {
        // 🔒 Absolute freeze when not playing
        if (!playing) {
            return pausedPositionMs
        }

        // 🔒 Never trust audio until it advances
        if (hasAudio) {
            val us = NativePlayer.getClockUs()
            if (us <= pausedPositionMs * 1000L) {
                return pausedPositionMs
            }
            return us / 1000
        }

        return pausedPositionMs
        }

    override val isPlaying: Boolean
        get() = playing

    override fun attachSurface(surface: Surface) {
        video.attachSurface(surface)
    }

    // ✅ NEW: Play directly from a content URI (opens PFD for audio detection / dup, and delegates video ownership to MediaCodecEngine)
    override fun play(uri: Uri) {
        android.widget.Toast
            .makeText(
                context,
                "PlayerController.play(uri) ENTERED",
                android.widget.Toast.LENGTH_LONG
            )
            .show()

        // TEMPORARILY disable ALL guards
        // if (currentUri == uri && playing) return

        // Ensure any existing resources are released before starting new playback
        release()

        // 🔒 INITIAL BASELINE: ensure UI is latched at 0 and audio is not marked playing
        pausedPositionMs = 0L
        playing = false

        currentUri = uri

        try {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw IllegalStateException("openFileDescriptor returned null")

            android.widget.Toast
                .makeText(
                    context,
                    "PFD opened fd=${pfd.fd}",
                    android.widget.Toast.LENGTH_LONG
                )
                .show()

            // Ensure UI clock advances even if audio hasn't started or will fail
            standaloneClock.start(0L)

            NativePlayer.playFd(pfd.fd, 0L, -1)

            // Query native engine to see whether an audio track actually exists
            hasAudio = NativePlayer.dbgHasAudioTrack()

            // If audio is already healthy immediately, sync the standalone clock so handoff is seamless
            if (NativePlayer.isAudioClockHealthy()) {
                val audioUs = NativePlayer.getClockUs()
                standaloneClock.start(audioUs)
            }

            android.widget.Toast
                .makeText(
                    context,
                    "NativePlayer.playFd called",
                    android.widget.Toast.LENGTH_LONG
                )
                .show()

            video.play(uri)

        } catch (e: Throwable) {
            android.widget.Toast
                .makeText(
                    context,
                    "ENGINE ERROR: ${e.javaClass.simpleName}: ${e.message}",
                    android.widget.Toast.LENGTH_LONG
                )
                .show()
        }
    }

    override fun pause() {
        // Latch current position so UI freezes immediately
        pausedPositionMs = currentPositionMs

        // 🔒 ALWAYS pause native audio (idempotent)
        NativePlayer.nativePause()

        // Pause video rendering & decode
        video.pause()

        playing = false
    }

    override fun resume() {
        video.prepareResume()
        NativePlayer.nativeResume()
        playing = true
    }

    override fun seekTo(positionMs: Long) {
        pausedPositionMs = positionMs
        playing = false

        NativePlayer.nativePause()
        NativePlayer.nativeSeek(positionMs * 1000L)

        video.seekToAudioClock()
    }

    override fun release() {
        video.setRenderEnabled(false)

        if (hasAudio) NativePlayer.release()
        video.release()

        // Clear current URI when fully releasing
        currentUri = null

        hasAudio = false
        playing = false
        seeking = false
    }

    // Detach surface (called from UI when the Surface disappears)
    override fun detachSurface() {
        // stop video rendering without stopping audio
        video.detachSurface()
    }

    // Recreate video safely using the last known URI
    override fun recreateVideo() {
        if (currentUri == null) return

        // Avoid restarting if video engine is already running
        if (video.isPlaying) return

        // Restart only the video (audio keeps playing)
        video.recreateVideo()
    }
}