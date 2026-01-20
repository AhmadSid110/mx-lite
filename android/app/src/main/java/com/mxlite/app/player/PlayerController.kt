package com.mxlite.app.player

import android.content.Context
import android.net.Uri
import android.view.Surface
import android.os.ParcelFileDescriptor
import android.util.Log
import com.mxlite.player.decoder.VideoDecoder

class PlayerController(
    private val context: Context
) : PlayerEngine {

    // 🔒 CLOCK: Direct pass-through to C++ VirtualClock
    private val masterClock = object : PlaybackClock {
        override val positionMs: Long
            get() = NativePlayer.virtualClockUs() / 1000
    }

    private var videoDecoder: VideoDecoder? = null
    private var currentSurface: Surface? = null
    
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

    override val videoWidth: Int
        get() = videoDecoder?.videoWidth ?: 0

    override val videoHeight: Int
        get() = videoDecoder?.videoHeight ?: 0

    override val decoderName: String
        get() = videoDecoder?.decoderName ?: "None"
        
    override val outputFps: Float
        get() = videoDecoder?.outputFps ?: 0f
        
    override val droppedFrames: Int
        get() = videoDecoder?.droppedFrames ?: 0

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
            
            // 4. Start Video Engine
            val surface = currentSurface
            if (surface != null) {
                val decoder = HwVideoDecoder(context, masterClock)
                videoDecoder = decoder
                decoder.prepare(pfd.fileDescriptor, surface)
                
                // 🔒 CRITICAL FIX: INITIAL HANDSHAKE
                // START CLOCK FIRST
                NativePlayer.nativeResume()
                // THEN open video gates
                decoder.play()
            } else {
                Log.w("PlayerController", "play() called without surface")
                NativePlayer.nativeResume()
            }
            
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
        videoDecoder?.pause()
    }

    override fun resume() {
        if (playbackState == PlaybackState.STOPPED) return
        
        // 🔒 Resume logic
        videoDecoder?.play()
        NativePlayer.nativeResume()
        playbackState = PlaybackState.PLAYING
    }

    override fun stop() {
        if (playbackState == PlaybackState.STOPPED) return

        playbackState = PlaybackState.STOPPED

        NativePlayer.release()   // Audio + clock
        videoDecoder?.stop()             // Video only

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
        videoDecoder?.pause()
    }

    override fun onSeekPreview(positionMs: Long) { // Drag Move
        if (playbackState != PlaybackState.DRAGGING) return
        videoDecoder?.seekTo(positionMs) // Mapping preview to seekTo for now
    }

    override fun onSeekCommit(positionMs: Long) { // Drag End
        if (playbackState != PlaybackState.DRAGGING) return

        NativePlayer.nativeSeek(positionMs * 1000L)
        videoDecoder?.seekTo(positionMs)

        if (wasPlayingBeforeDrag) {
            videoDecoder?.play()
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
        videoDecoder?.seekTo(positionMs)
        
        // 🔒 FIX #3: Re-open gates if we are playing
        if (playbackState == PlaybackState.PLAYING) {
            videoDecoder?.play()
        }
    }

    // =========================================================================
    // 🟢 LIFECYCLE
    // =========================================================================

    override fun attachSurface(surface: Surface) {
        currentSurface = surface
        videoDecoder?.attachSurface(surface)
        if (playbackState != PlaybackState.STOPPED) {
             videoDecoder?.recreateVideo()
             
             // 🔒 FIX #1: RESTORE GATES
             // If we were playing, we MUST re-enable the render gate
             // after the surface-triggered recreateVideo().
             if (playbackState == PlaybackState.PLAYING) {
                 videoDecoder?.play()
             }
        }
    }

    override fun detachSurface() {
        currentSurface = null
        videoDecoder?.detachSurface()
    }

    override fun recreateVideo() {
        if (currentUri == null) return
        videoDecoder?.recreateVideo()
    }

    override fun release() {
        playbackState = PlaybackState.STOPPED
        
        NativePlayer.release()
        videoDecoder?.release()
        videoDecoder = null
        
        try { audioPfd?.close() } catch (_: Exception) {}
        audioPfd = null
        
        currentUri = null
        hasAudio = false
    }
}