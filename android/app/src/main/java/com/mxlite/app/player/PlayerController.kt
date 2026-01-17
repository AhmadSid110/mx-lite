package com.mxlite.app.player

import android.content.Context
import android.net.Uri
import android.view.Surface
import android.os.ParcelFileDescriptor
import java.io.File

class PlayerController(
    private val context: Context
) : PlayerEngine {

    private val masterClock = object : PlaybackClock {
        override val positionMs: Long
            get() {
                return NativePlayer.virtualClockUs() / 1000
            }
    }

    private val video = MediaCodecEngine(context, masterClock)

    private var hasAudio = false
    private var seeking = false

    // Track the currently playing content URI (if any)
    private var playerDurationMs = 0L
    private var isPlayingState = false

    // Seek Latch State
    private var userSeeking = false
    private var seekPreviewMs: Long = 0

    override var currentUri: Uri? = null
        private set

    override val durationMs: Long
        get() = NativePlayer.durationMs

    override val currentPositionMs: Long
        get() {
            // 🔒 SEEK LATCH: If user is seeking, return preview time.
            // This prevents the UI from fighting the advancing clock.
            if (userSeeking) return seekPreviewMs
            
            // Otherwise return authoritative clock
            if (!NativePlayer.initialized) return 0L
            val micros = NativePlayer.virtualClockUs()
            return if (micros < 0) 0L else micros / 1000L
        }

    override val isPlaying: Boolean
        get() = isPlayingState

    override fun onSeekStart() {
        userSeeking = true
        seekPreviewMs = currentPositionMs
    }

    override fun onSeekPreview(positionMs: Long) {
        seekPreviewMs = positionMs
    }

    override fun onSeekCommit(positionMs: Long) {
        userSeeking = false
        seekTo(positionMs)
    }

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
        isPlayingState = false

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

            NativePlayer.playFd(pfd.fd, 0L, -1)

            // Query native engine to see whether an audio track actually exists
            hasAudio = NativePlayer.dbgHasAudioTrack()

            // 🔴 REQUIRED: reflect native start in UI state immediately
            isPlayingState = true
            

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
        // Reflect non-playing state immediately
        isPlayingState = false

        // 🔒 ALWAYS pause native audio (idempotent)
        NativePlayer.nativePause()
        
        // 🔒 SOFT PAUSE video (stop decoding)
        video.pause()
    }

    override fun resume() {
        video.prepareResume()
        video.resume() // Ensure decodeEnabled = true

        NativePlayer.nativeResume()

        // Always reflect resumed state in UI immediately
        isPlayingState = true
    }

    override fun seekTo(positionMs: Long) {
        // 🔒 SEEK SEQUENCE (Final Form)
        // 1. Soft Pause Audio (idempotent, no driver calls)
        NativePlayer.pause()

        // 2. Seek Native Clock (atomic)
        NativePlayer.nativeSeek(positionMs * 1000L)

        // 3. Seek Video (use EXPLICIT target, not live clock)
        video.seekTo(positionMs)

        // 4. Resume Audio (restores flow)
        NativePlayer.nativeResume()

        // Reflect playing state
        isPlayingState = true
    }

    override fun release() {
        video.setRenderEnabled(false)

        if (hasAudio) NativePlayer.release()
        video.release()

        // Clear current URI when fully releasing
        currentUri = null

        hasAudio = false
        isPlayingState = false
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