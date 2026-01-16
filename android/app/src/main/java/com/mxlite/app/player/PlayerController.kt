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

    // Track the currently playing content URI (if any)
    override var currentUri: Uri? = null
        private set

    override val durationMs: Long
        get() = video.durationMs

    override val currentPositionMs: Long
        get() {
            return if (hasAudio) {
                // Native audio track exists: audio is master
                NativePlayer.getClockUs() / 1000
            } else {
                // Standalone clock takes over
                standaloneClock.positionUs() / 1000
            }
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

        currentUri = uri
        playing = true

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
            if (NativePlayer.isAudioClockHealthy()) {
                val audioUs = NativePlayer.getClockUs()
                standaloneClock.start(audioUs)
            }
        }
    }

    override fun seekTo(positionMs: Long) {
        if (currentUri == null) return

        val wasPlaying = playing
        seeking = true
        playing = false

        // 🔑 HARD STOP video rendering during seek
        video.setRenderEnabled(false)

        if (hasAudio) {
            NativePlayer.nativePause()
            NativePlayer.nativeSeek(positionMs * 1000)
        }

        // 🔁 Recreate video cleanly (do NOT restart audio here)
        video.recreateVideo()

        seeking = false

        if (wasPlaying) {
            video.setRenderEnabled(true)
            playing = true

            if (hasAudio) {
                NativePlayer.nativeResume()
                if (NativePlayer.isAudioClockHealthy()) {
                    val audioUs = NativePlayer.getClockUs()
                    standaloneClock.start(audioUs)
                }
            }
        }
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
        val uri = currentUri ?: return

        // Avoid restarting if video engine is already running
        if (video.isPlaying) return

        // Restart only the video (audio keeps playing)
        video.recreateVideo()
    }
}