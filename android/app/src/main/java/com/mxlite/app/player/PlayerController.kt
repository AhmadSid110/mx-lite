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
    override var currentUri: Uri? = null
        private set

    override val durationMs: Long
        get() = NativePlayer.durationMs

    //  PURE NATIVE CLOCK (User Request: No caching, no fallbacks)
    override val currentPositionMs: Long
        get() {
             val micros = NativePlayer.virtualClockUs()
             return if (micros < 0) 0L else micros / 1000L
        }

    // 🧱 AUTHORITATIVE STATE MODEL
    private enum class PlaybackState {
        STOPPED,
        PLAYING,
        PAUSED,
        DRAGGING
    }

    private var playbackState: PlaybackState = PlaybackState.STOPPED
    private var wasPlayingBeforeDrag = false

    override val isPlaying: Boolean
        get() = playbackState == PlaybackState.PLAYING

    // 🟢 PUBLIC ENTRY POSTS (ONLY THESE)
    
    override fun onSeekStart() { // onSeekDragStart
        if (playbackState == PlaybackState.DRAGGING) return

        wasPlayingBeforeDrag = (playbackState == PlaybackState.PLAYING)
        playbackState = PlaybackState.DRAGGING

        // Pause clock ONLY if it was running
        if (wasPlayingBeforeDrag) {
            NativePlayer.nativePause()
        }

        // Hard stop video decode (no thread destroy)
        video.pause() // decodeEnabled = false
    }

    override fun onSeekPreview(positionMs: Long) { // onSeekDrag
        if (playbackState != PlaybackState.DRAGGING) return
        
        // UI updates ONLY
        // ❌ NO nativeSeek
        // ❌ NO resume
        // ❌ NO decode
        // ❌ NO extractor access
    }

    override fun onSeekCommit(positionMs: Long) { // onSeekDragEnd
        if (playbackState != PlaybackState.DRAGGING) return

        // 1. SEEK CLOCK (GROUND TRUTH)
        NativePlayer.nativeSeek(positionMs * 1000L)

        // 2. HARD VIDEO SEEK (THREAD-SAFE)
        video.seekTo(positionMs)

        // 3. RESUME ONLY IF IT WAS PLAYING BEFORE
        if (wasPlayingBeforeDrag) {
            video.prepareResume()
            NativePlayer.nativeResume()
            playbackState = PlaybackState.PLAYING
        } else {
            playbackState = PlaybackState.PAUSED
        }
    }

    override fun attachSurface(surface: Surface) {
        video.attachSurface(surface)
    }

    // ✅ NEW: Play directly from a content URI
    override fun play(uri: Uri) {
        release()
        
        playbackState = PlaybackState.STOPPED
        currentUri = uri

        try {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw IllegalStateException("PFD null")
            
            // Sets initialized = true (via wrapper)
            NativePlayer.playFd(pfd.fd, 0L, -1)
            
            hasAudio = NativePlayer.dbgHasAudioTrack()
            playbackState = PlaybackState.PLAYING
            
            video.play(uri)

        } catch (e: Throwable) {
           // ...
        }
    }

    override fun pause() {
        playbackState = PlaybackState.PAUSED
        // 🔒 Direct pass-through
        NativePlayer.nativePause()
        video.pause()
    }

    override fun resume() {
        video.prepareResume()
        video.resume() 
        NativePlayer.nativeResume()
        playbackState = PlaybackState.PLAYING
    }

    override fun seekTo(positionMs: Long) {
        // 🔒 DIRECT SEEK (No pause, no resume, no guards)
        // Note: This API should ideally be restricted, but interface requires it.
        // It bypasses the drag state machine, so we assume it respects current state?
        // User said: "❌ No other code may call seek() directly."
        // But if UI calls this (e.g. double tap), we must handle it.
        // For now, implementing as direct pass-through as per previous logic, 
        // but ensuring it doesn't break state if we are NOT dragging.
        
        if (playbackState == PlaybackState.DRAGGING) {
             // If dragging, ignore external seeks? Or commit? 
             // Ideally we shouldn't be here if dragging.
             return 
        }

        NativePlayer.nativeSeek(positionMs * 1000L)
        video.seekTo(positionMs)
    }

    override fun release() {
        video.setRenderEnabled(false)

        if (hasAudio) NativePlayer.release()
        video.release()

        // Clear current URI when fully releasing
        currentUri = null

        hasAudio = false
        playbackState = PlaybackState.STOPPED
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