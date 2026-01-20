package com.mxlite.player.decoder

import android.view.Surface
import java.io.FileDescriptor

interface VideoDecoder {

    /**
     * Allocate resources and configure for the given FD and Surface.
     * Must NOT start playback automatically.
     */
    fun prepare(
        fd: FileDescriptor, 
        surface: Surface
    )

    fun attachSurface(surface: Surface)
    fun detachSurface()
    fun recreateVideo()

    /** Start advancing frames */
    fun play()
    
    /** Pause advancement of frames */
    fun pause()
    
    fun seekTo(positionMs: Long)
    
    /** Stop decoding, keep surface & object reusable */
    fun stop()
    
    /** Final destruction, no reuse */
    fun release()

    val durationMs: Long
    val isPlaying: Boolean

    // Video Metadata
    val videoWidth: Int
    val videoHeight: Int
    val decoderName: String
    val outputFps: Float
    val droppedFrames: Int
}
