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

    // Track the currently playing content URI (if any)
    override var currentUri: Uri? = null
        private set

    override val durationMs: Long
        get() = video.durationMs

    override val currentPositionMs: Long
        get() {
            return if (!playing) {
                // When paused, show the latched position.
                pausedPositionMs
            } else if (hasAudio) {
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

        // Latch current position so UI freezes immediately
        pausedPositionMs = currentPositionMs

        // 🔑 ALWAYS disable render when paused
        video.setRenderEnabled(false)

        if (hasAudio) {
            NativePlayer.nativePause()
        }

        playing = false
    }

    override fun resume() {
        if (playing) return

        // 🔑 Re-enable render BEFORE audio resumes
        video.setRenderEnabled(true)

        if (hasAudio) {
            // Prepare video before starting the native audio clock
            video.prepareResume()
            NativePlayer.nativeResume()
            if (NativePlayer.isAudioClockHealthy()) {
                val audioUs = NativePlayer.getClockUs()
                standaloneClock.start(audioUs)
            }
        }

        // Finally mark playing true — do not reset pausedPositionMs to avoid a UI jump
        playing = true
    }

    override fun seekTo(positionMs: Long) {
        if (currentUri == null) return
        if (seeking) return
        if (durationMs <= 0) return

        val wasPlaying = playing

        // One-shot seek guard
        seeking = true
        playing = false

        try {
            // 🔑 HARD STOP video rendering during seek
            video.setRenderEnabled(false)

            if (hasAudio) {
                NativePlayer.nativePause()
                NativePlayer.nativeSeek(positionMs * 1000)

                // Latch the paused position immediately so UI updates
                pausedPositionMs = positionMs

                // Align video extractor with the audio clock instead of recreating
                video.seekToAudioClock()
            } else {
                // No audio: safely recreate the video pipeline
                video.recreateVideo()
            }

            if (wasPlaying) {
                video.setRenderEnabled(true)
                playing = true

                if (hasAudio) {
                        // Prepare the video pipeline BEFORE resuming audio
                        video.prepareResume()
                        NativePlayer.nativeResume()
                        if (NativePlayer.isAudioClockHealthy()) {
                            val audioUs = NativePlayer.getClockUs()
                            standaloneClock.start(audioUs)
                        }
                    }
            }
        } finally {
            seeking = false
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