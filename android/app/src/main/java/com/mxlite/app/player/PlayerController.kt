package com.mxlite.app.player

import android.content.Context
import android.net.Uri
import android.view.Surface
import android.os.ParcelFileDescriptor
import android.util.Log

class PlayerController(
    private val context: Context
) : PlayerEngine {

    // 🔒 CLOCK: Direct pass-through to C++ VirtualClock
    private val masterClock = object : PlaybackClock {
        override val positionMs: Long
            get() = NativePlayer.virtualClockUs() / 1000
    }

    private val video = MediaCodecEngine(context, masterClock)
    
    // Audio FD management
    private var audioPfd: ParcelFileDescriptor? = null
    private var hasAudio = false

    override var currentUri: Uri? = null
        private set

    override val durationMs: Long
        get() = NativePlayer.durationMs

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

    // 🔒 isPlaying is strictly defined by our intent
    override val isPlaying: Boolean
        get() = playbackState == PlaybackState.PLAYING

    // =========================================================================
    // 🟢 PUBLIC ENTRY POINTS
    // =========================================================================

    override fun play(uri: Uri) {
        // 1. Clean slate - Rule S3: MUST NOT call release()
        stop()
        
        playbackState = PlaybackState.STOPPED
        currentUri = uri

        try {
            // 2. Initialize Native Audio / Clock
            NativePlayer.nativeInit()

            // 3. Open Audio FD
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw IllegalStateException("PFD null")
            audioPfd = pfd
            
            // Pass FD to C++
            NativePlayer.playFd(pfd.fd, 0L, -1)
            hasAudio = NativePlayer.dbgHasAudioTrack()
            
            // 4. Start Video Engine (Starts PAUSED)
            video.play(uri)

            // 🔒 CRITICAL FIX: INITIAL HANDSHAKE
            // START CLOCK FIRST
            NativePlayer.nativeResume()
            // THEN open video gates
            video.resume()
            
            playbackState = PlaybackState.PLAYING

        } catch (e: Throwable) {
            e.printStackTrace()
            stop()
        }
    }

    override fun pause() {
        if (playbackState == PlaybackState.STOPPED) return
        playbackState = PlaybackState.PAUSED
        
        // 🔒 Direct pass-through
        NativePlayer.nativePause()
        video.pause()
    }

    override fun resume() {
        if (playbackState == PlaybackState.STOPPED) return
        
        // 🔒 Resume logic
        video.resume()
        NativePlayer.nativeResume()
        playbackState = PlaybackState.PLAYING
    }

    override fun stop() {
        if (playbackState == PlaybackState.STOPPED) return

        playbackState = PlaybackState.STOPPED

        NativePlayer.release()   // Audio + clock
        video.stop()             // Video only

        try { audioPfd?.close() } catch (_: Exception) {}
        audioPfd = null
    }

    // =========================================================================
    // 🟢 SEEK / DRAG STATE MACHINE
    // =========================================================================

    override fun onSeekStart() { // Drag Start
        if (playbackState == PlaybackState.DRAGGING) return

        wasPlayingBeforeDrag = (playbackState == PlaybackState.PLAYING)
        playbackState = PlaybackState.DRAGGING

        NativePlayer.nativePause()
        video.pause()
    }

    override fun onSeekPreview(positionMs: Long) { // Drag Move
        if (playbackState != PlaybackState.DRAGGING) return
        video.onSeekPreview(positionMs)
    }

    override fun onSeekCommit(positionMs: Long) { // Drag End
        if (playbackState != PlaybackState.DRAGGING) return

        NativePlayer.nativeSeek(positionMs * 1000L)
        video.seekTo(positionMs)

        if (wasPlayingBeforeDrag) {
            video.resume()
            NativePlayer.nativeResume()
            playbackState = PlaybackState.PLAYING
        } else {
            playbackState = PlaybackState.PAUSED
            // No resume. Video stays on seek frame.
        }
    }

    override fun seekTo(positionMs: Long) {
        // Direct seek (e.g. 10s skip)
        if (playbackState == PlaybackState.DRAGGING) return

        NativePlayer.nativeSeek(positionMs * 1000L)
        video.seekTo(positionMs)
        
        // 🔒 FIX #3: Re-open gates if we are playing
        if (playbackState == PlaybackState.PLAYING) {
            video.resume()
        }
    }

    // =========================================================================
    // 🟢 LIFECYCLE
    // =========================================================================

    override fun attachSurface(surface: Surface) {
        video.attachSurface(surface)
        if (playbackState != PlaybackState.STOPPED) {
             video.recreateVideo()
             
             // 🔒 FIX #1: RESTORE GATES
             // If we were playing, we MUST re-enable the render gate
             // after the surface-triggered recreateVideo().
             if (playbackState == PlaybackState.PLAYING) {
                 video.resume()
             }
        }
    }

    override fun detachSurface() {
        video.detachSurface()
    }

    override fun recreateVideo() {
        if (currentUri == null) return
        video.recreateVideo()
    }

    override fun release() {
        playbackState = PlaybackState.STOPPED
        
        NativePlayer.release()
        video.release()
        
        try { audioPfd?.close() } catch (_: Exception) {}
        audioPfd = null
        
        currentUri = null
        hasAudio = false
    }
}