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
    
    // Track the currently playing content URI (if any)
    private var isPlayingState = false

    override var currentUri: Uri? = null
        private set

    override val durationMs: Long
        get() = NativePlayer.durationMs

    // � PURE NATIVE CLOCK (User Request: No caching, no fallbacks)
    override val currentPositionMs: Long
        get() {
             val micros = NativePlayer.virtualClockUs()
             return if (micros < 0) 0L else micros / 1000L
        }

    override val isPlaying: Boolean
        get() = isPlayingState

    override fun onSeekStart() {
        // UI handles drag blocking, Engine just waits for commit
    }

    override fun onSeekPreview(positionMs: Long) {
        // No cached preview logic
    }

    override fun onSeekCommit(positionMs: Long) {
        seekTo(positionMs)
    }

    override fun attachSurface(surface: Surface) {
        video.attachSurface(surface)
    }

    // ✅ NEW: Play directly from a content URI
    override fun play(uri: Uri) {
        release()
        
        isPlayingState = false
        currentUri = uri

        try {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw IllegalStateException("PFD null")
            
            // Sets initialized = true (via wrapper)
            NativePlayer.playFd(pfd.fd, 0L, -1)
            
            hasAudio = NativePlayer.dbgHasAudioTrack()
            isPlayingState = true
            
            video.play(uri)

        } catch (e: Throwable) {
           // ...
        }
    }

    override fun pause() {
        isPlayingState = false
        // 🔒 Direct pass-through
        NativePlayer.nativePause()
        video.pause()
    }

    override fun resume() {
        video.prepareResume()
        video.resume() 
        NativePlayer.nativeResume()
        isPlayingState = true
    }

    override fun seekTo(positionMs: Long) {
        // 🔒 DIRECT SEEK (No pause, no resume, no guards)
        NativePlayer.nativeSeek(positionMs * 1000L)
        video.seekTo(positionMs)
        
        // Note: Clock maintains its running/paused state automatically.
    }

    override fun release() {
        video.setRenderEnabled(false)

        if (hasAudio) NativePlayer.release()
        video.release()

        // Clear current URI when fully releasing
        currentUri = null

        hasAudio = false
        isPlayingState = false
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