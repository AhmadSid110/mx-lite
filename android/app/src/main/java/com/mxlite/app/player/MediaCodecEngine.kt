package com.mxlite.app.player

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.Surface
import java.io.FileDescriptor
import kotlin.math.min

class MediaCodecEngine(
    private val context: Context,
    private val clock: PlaybackClock
) : PlayerEngine {

    override val currentUri: Uri?
        get() = null

    private var extractor: MediaExtractor? = null
    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var currentPfd: ParcelFileDescriptor? = null

    @Volatile private var surfaceReady = false
    @Volatile private var videoRunning = false
    
    // State Flags
    @Volatile private var renderEnabled = false
    @Volatile private var decodeEnabled = false
    @Volatile private var inputEOS = false

    private var decodeThread: Thread? = null
    
    // 🔒 LOCK: Extractor Serialization
    // Prevents onSeekPreview (Main Thread) from racing with startDecodeLoop (Background)
    private val extractorLock = Any()
    
    // 🔒 LOCK: Decode Gate State
    // acts as the key for "First Frame After Seek" logic
    @Volatile private var lastRenderedPtsUs: Long = Long.MIN_VALUE

    override var durationMs: Long = 0
        private set

    override val currentPositionMs: Long
        get() = NativePlayer.virtualClockUs() / 1000

    // 🔒 STATE LOGIC FIX: Playback = Enabled + Active Thread
    override val isPlaying: Boolean
        get() = decodeEnabled && renderEnabled && videoRunning

    override fun attachSurface(surface: Surface) {
        this.surface = surface
        surfaceReady = true
    }

    fun hasSurface(): Boolean = surfaceReady && surface?.isValid == true

    // =========================================================================
    // 🟢 CORE SYNC LOGIC
    // =========================================================================
    private fun handleVideoFrame(outIndex: Int, info: MediaCodec.BufferInfo) {
        val index = outIndex
        
        // 1️⃣ RULE: FIRST FRAME AFTER SEEK -> ALWAYS RENDER
        // Allows the preview frame to show even if "Paused"
        if (lastRenderedPtsUs == Long.MIN_VALUE) {
            try {
                codec?.releaseOutputBuffer(index, true)
                lastRenderedPtsUs = info.presentationTimeUs
            } catch (e: Exception) { e.printStackTrace() }
            return
        }

        // 2️⃣ RULE: PAUSE → DROP (DEADLOCK PREVENTION)
        // If render is disabled, drop immediately. NEVER hold the buffer.
        if (!renderEnabled) {
            try { codec?.releaseOutputBuffer(index, false) } catch (_: Exception) {}
            return
        }

        // 3️⃣ RULE: SYNC LOOP (TIMING)
        // Check clock once at top of loop
        var clockUs = NativePlayer.virtualClockUs()
        var diffUs = info.presentationTimeUs - clockUs

        while (diffUs > 0 && videoRunning) {
            // Check state integrity (User might have paused during sleep)
            if (!renderEnabled) {
                try { codec?.releaseOutputBuffer(index, false) } catch (_: Exception) {}
                return
            }

            // Sleep calculation:
            // - If >20ms away, sleep conservative amount (diff - 10ms) to avoid JNI thrashing
            // - If <20ms away, sleep small (2ms) for precision
            // Cap max sleep to 50ms for responsiveness
            val sleepMs = when {
                diffUs > 20_000 -> min((diffUs / 1000) - 10, 50)
                else -> 2
            }
            
            try { Thread.sleep(sleepMs) } catch (e: InterruptedException) { return }
            
            // Re-read clock only after wake-up
            clockUs = NativePlayer.virtualClockUs()
            diffUs = info.presentationTimeUs - clockUs
        }

        // 4️⃣ RULE: DROP LATE FRAMES (>50ms behind)
        if (diffUs < -50_000) {
            try { codec?.releaseOutputBuffer(index, false) } catch (_: Exception) {}
            return
        }

        // 5️⃣ RULE: RENDER
        try {
            codec?.releaseOutputBuffer(index, true)
            lastRenderedPtsUs = info.presentationTimeUs
        } catch (e: Exception) { e.printStackTrace() }
    }

    // =========================================================================
    // 🟢 DECODE LOOP
    // =========================================================================
    private fun startDecodeLoop() {
        if (decodeThread?.isAlive == true) return

        videoRunning = true
        
        decodeThread = Thread {
            while (videoRunning && !Thread.currentThread().isInterrupted) {
                
                // 🔒 INVARIANT: PAUSE GATE
                // If paused AND the seek frame is already done, sleep.
                if (!decodeEnabled && lastRenderedPtsUs != Long.MIN_VALUE) {
                    try { Thread.sleep(10) } catch (e: InterruptedException) { break }
                    continue
                }

                try {
                    // INPUT PATH
                    // Protect extractor read from concurrent seeking/flushing
                    synchronized(extractorLock) {
                        val inIndex = codec?.dequeueInputBuffer(10_000) ?: -1
                        if (!inputEOS && inIndex >= 0) {
                            val buffer = codec?.getInputBuffer(inIndex)
                            if (buffer != null) {
                                val size = extractor?.readSampleData(buffer, 0) ?: -1
                                if (size > 0) {
                                    val pts = extractor!!.sampleTime
                                    codec?.queueInputBuffer(inIndex, 0, size, pts, 0)
                                    extractor?.advance()
                                } else {
                                    codec?.queueInputBuffer(
                                        inIndex, 0, 0, 0,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                    )
                                    inputEOS = true
                                }
                            }
                        }
                    }

                    // OUTPUT PATH
                    val info = MediaCodec.BufferInfo()
                    val outIndex = codec?.dequeueOutputBuffer(info, 10_000) ?: -1

                    if (outIndex >= 0) {
                        handleVideoFrame(outIndex, info)
                    } 

                } catch (e: Exception) {
                    Log.e("MediaCodecEngine", "Decode loop error", e)
                }
            }
        }.apply { start() }
    }

    // =========================================================================
    // 🟢 PUBLIC API
    // =========================================================================

    fun play(fd: FileDescriptor) {
        if (!hasSurface()) return
        releaseResources()

        try {
            extractor = MediaExtractor().apply { setDataSource(fd) }

            var trackIndex = -1
            for (i in 0 until extractor!!.trackCount) {
                val format = extractor!!.getTrackFormat(i)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                    trackIndex = i
                    break
                }
            }

            if (trackIndex < 0) return

            extractor!!.selectTrack(trackIndex)
            val format = extractor!!.getTrackFormat(trackIndex)

            val startUs = NativePlayer.virtualClockUs()
            // Sync extractor initially under lock
            synchronized(extractorLock) {
                extractor!!.seekTo(startUs.coerceAtLeast(0), MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            }

            durationMs = if (format.containsKey(MediaFormat.KEY_DURATION))
                format.getLong(MediaFormat.KEY_DURATION) / 1000
            else 0

            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!).apply {
                configure(format, surface, null, 0)
                start()
            }

            inputEOS = false
            lastRenderedPtsUs = Long.MIN_VALUE // Trigger First Frame logic
            
            // Start Paused. Let Controller call resume.
            decodeEnabled = false
            renderEnabled = false

            startDecodeLoop()

        } catch (e: Exception) {
            e.printStackTrace()
            releaseResources()
        }
    }

    override fun play(uri: Uri) {
        release()
        try {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return
            currentPfd = pfd
            play(pfd.fileDescriptor)
        } catch (e: Exception) {
            e.printStackTrace()
            release()
        }
    }

    override fun pause() {
        renderEnabled = false
        decodeEnabled = false
    }

    override fun resume() {
        decodeEnabled = true
        renderEnabled = true
    }

    override fun seekTo(positionMs: Long) {
        val positionUs = positionMs * 1000L

        // Hard seek: Kill thread
        videoRunning = false
        try { decodeThread?.join() } catch (_: Exception) {}
        decodeThread = null

        try { codec?.flush() } catch (_: Exception) {}
        
        synchronized(extractorLock) {
            extractor?.seekTo(positionUs.coerceAtLeast(0), MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        }

        lastRenderedPtsUs = Long.MIN_VALUE
        startDecodeLoop()
    }

    // 🔒 FIX: RACE-PROOF SEEK PREVIEW
    // Temporarily gates decode and uses lock to ensure safe atomic Preview
    override fun onSeekPreview(positionMs: Long) {
        if (!videoRunning) return

        // 1. Logic freeze
        val wasDecoding = decodeEnabled
        decodeEnabled = false

        val positionUs = positionMs * 1000L
        
        synchronized(extractorLock) {
            try {
                // 2. Hardware reset
                codec?.flush()
                extractor?.seekTo(positionUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                
                // 3. Re-arm trigger (MIN_VALUE overrides the !decodeEnabled gate for ONE frame)
                lastRenderedPtsUs = Long.MIN_VALUE
            } catch (_: Exception) {}
        }
        
        // 4. Restore state
        // If we were paused, this keeps decodeEnabled=false, but MIN_VALUE allows 1 frame.
        // If we were playing, this resumes play logic (though typically we pause before preview).
        decodeEnabled = wasDecoding
    }

    override fun detachSurface() {
        renderEnabled = false
        surface = null
        videoRunning = false
        try { decodeThread?.join() } catch (_: Exception) {}
        decodeThread = null
    }

    override fun recreateVideo() {
        if (videoRunning) return
        try { codec?.stop(); codec?.release() } catch (_: Exception) {}
        codec = null
        try { extractor?.release() } catch (_: Exception) {}
        extractor = null

        currentPfd?.fileDescriptor?.let { fd ->
            if (hasSurface()) play(fd)
        }
    }

    override fun release() {
        releaseResources()
        try { currentPfd?.close() } catch (_: Exception) {}
        currentPfd = null
    }

    private fun releaseResources() {
        videoRunning = false
        renderEnabled = false
        
        try { decodeThread?.join() } catch (_: Exception) {}
        decodeThread = null

        try { codec?.stop(); codec?.release() } catch (_: Exception) {}
        codec = null
        
        try { extractor?.release() } catch (_: Exception) {}
        extractor = null
    }

    // Unused / Deprecated
    override fun onSeekStart() { }
    override fun onSeekCommit(positionMs: Long) { }
    @Deprecated("Use seekTo(positionMs)")
    fun seekToAudioClock() { }
}