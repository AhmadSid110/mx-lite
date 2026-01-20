package com.mxlite.player.decoder.sw

import android.view.Surface
import com.mxlite.player.decoder.VideoDecoder
import java.io.FileDescriptor

/**
 * Software video decoder (FFmpeg-backed in Phase 2.2+)
 *
 * IMPORTANT:
 * - No audio
 * - No clock
 * - No UI
 * - No Surface ownership
 * - ZERO dependencies in constructor
 */
class SwVideoDecoder : VideoDecoder {

    private enum class State {
        IDLE,        // Created but not prepared
        PREPARED,   // FD accepted, resources allocated
        PLAYING,    // Decode loop running
        PAUSED,     // Decode loop alive but gated
        SEEKING,    // Transient seek state
        STOPPED,    // Decoding stopped, reusable
        RELEASED    // Terminal
    }

    @Volatile private var state = State.IDLE
    @Volatile private var running = false
    @Volatile private var paused = false

    private var decodeThread: Thread? = null

    private var native: NativeSwDecoder? = null

    override fun prepare(
        fd: FileDescriptor,
        surface: Surface
    ) {
        check(state == State.IDLE || state == State.STOPPED)

        native = NativeSwDecoder()
        
        // Use reflection or a helper to get the int FD if needed, 
        // but for Phase 2.3 stub we just need to prove call flow.
        // We can't easily detach FD in pure Kotlin without ParcelFileDescriptor or similar.
        // For now, we pass -1 or use a dummy for the stub.
        native!!.prepare(-1)

        state = State.PREPARED
        running = true
        paused = true

        decodeThread = Thread {
            decodeLoop()
        }.apply { 
            name = "SwVideoDecoder-Decode"
            start() 
        }
    }

    override fun play() {
        if (state == State.PREPARED || state == State.PAUSED) {
            native?.play()
            paused = false
            state = State.PLAYING
        }
    }

    override fun pause() {
        if (state == State.PLAYING) {
            native?.pause()
            paused = true
            state = State.PAUSED
        }
    }

    override fun seekTo(positionMs: Long) {
        if (state == State.PLAYING || state == State.PAUSED) {
            val previousPaused = paused
            state = State.SEEKING
            
            native?.seek(positionMs)
            
            state = if (previousPaused) State.PAUSED else State.PLAYING
        }
    }

    override fun stop() {
        if (state == State.RELEASED) return

        native?.stop()
        running = false
        decodeThread?.interrupt() // Ensure it wakes up from sleep
        try {
            decodeThread?.join(500)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        decodeThread = null

        state = State.STOPPED
    }

    override fun release() {
        stop()
        native?.release()
        native = null
        state = State.RELEASED
    }

    override fun attachSurface(surface: Surface) {
        // Phase 2.2: NO-OP
    }

    override fun detachSurface() {
        // Phase 2.2: NO-OP
    }

    override fun recreateVideo() {
        // Phase 2.2: NO-OP
    }

    override val durationMs: Long
        get() = 0L

    override val isPlaying: Boolean
        get() = state == State.PLAYING

    override val videoWidth: Int
        get() = 0

    override val videoHeight: Int
        get() = 0

    override val decoderName: String
        get() = "Software (Stateful)"

    override val outputFps: Float
        get() = 0f

    override val droppedFrames: Int
        get() = 0

    private fun decodeLoop() {
        while (running) {
            if (paused) {
                try {
                    Thread.sleep(10)
                } catch (e: InterruptedException) {
                    break
                }
                continue
            }

            // 🔒 Phase 2.2: No decoding yet
            // This is where FFmpeg will feed frames later

            try {
                Thread.sleep(10)
            } catch (e: InterruptedException) {
                break
            }
        }
    }
}
