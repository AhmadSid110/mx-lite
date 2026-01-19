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
    private var videoTrackIndex = -1

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

    override var videoWidth: Int = 0
        private set
    override var videoHeight: Int = 0
        private set

    override fun attachSurface(surface: Surface) {
        this.surface = surface
        surfaceReady = true
    }

    fun hasSurface(): Boolean = surfaceReady && surface?.isValid == true

    // =========================================================================
    // 🟢 CORE SYNC LOGIC
    // =========================================================================
    private fun handleVideoFrame(outIndex: Int, info: MediaCodec.BufferInfo) {
        val localCodec = codec ?: return // Should not happen if called from decodeLoop
        
        // 🔒 PHASE 2: SURFACE VALIDITY AT RENDER TIME
        if (surface == null || !surface!!.isValid) {
            try {
                localCodec.releaseOutputBuffer(outIndex, false)
            } catch (_: Exception) {}
            return
        }

        // 1️⃣ RULE: FIRST FRAME AFTER SEEK -> ALWAYS RENDER
        // Allows the preview frame to show even if "Paused"
        if (lastRenderedPtsUs == Long.MIN_VALUE) {
            try {
                localCodec.releaseOutputBuffer(outIndex, true)
                lastRenderedPtsUs = info.presentationTimeUs
            } catch (e: Exception) { e.printStackTrace() }
            return
        }

        // 2️⃣ RULE: PAUSE → DROP (DEADLOCK PREVENTION)
        // If render is disabled, drop immediately. NEVER hold the buffer.
        if (!renderEnabled) {
            try { localCodec.releaseOutputBuffer(outIndex, false) } catch (_: Exception) {}
            return
        }

        // 3️⃣ RULE: SYNC LOOP (TIMING)
        // Check clock once at top of loop
        var clockUs = NativePlayer.virtualClockUs()
        var diffUs = info.presentationTimeUs - clockUs

        while (diffUs > 0 && videoRunning) {
            // Check state integrity (User might have paused during sleep)
            if (!renderEnabled) {
                try { localCodec.releaseOutputBuffer(outIndex, false) } catch (_: Exception) {}
                return
            }

            val sleepMs = when {
                diffUs > 20_000 -> min((diffUs / 1000) - 10, 50)
                else -> 2
            }
            
            try { Thread.sleep(sleepMs) } catch (e: InterruptedException) {
                try { localCodec.releaseOutputBuffer(outIndex, false) } catch (_: Exception) {}
                return
            }
            
            // Re-read clock only after wake-up
            clockUs = NativePlayer.virtualClockUs()
            diffUs = info.presentationTimeUs - clockUs
        }

        // 4️⃣ RULE: DROP LATE FRAMES (>50ms behind)
        if (diffUs < -50_000) {
            try { localCodec.releaseOutputBuffer(outIndex, false) } catch (_: Exception) {}
            return
        }

        // 5️⃣ RULE: RENDER
        try {
            localCodec.releaseOutputBuffer(outIndex, true)
            lastRenderedPtsUs = info.presentationTimeUs
        } catch (_: UnsupportedOperationException) {
            videoRunning = false
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
                        val localCodec = codec ?: return@synchronized
                        val inIndex = try {
                            localCodec.dequeueInputBuffer(10_000)
                        } catch (_: UnsupportedOperationException) {
                            videoRunning = false
                            return@synchronized
                        }
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
                    val localCodec = codec ?: return@Thread
                    val outIndex = try {
                        localCodec.dequeueOutputBuffer(info, 10_000)
                    } catch (_: UnsupportedOperationException) {
                        videoRunning = false
                        return@Thread
                    }

                    when (outIndex) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            // 🏁 RULE 7: Required by some GPUs for rendering
                            val newFormat = codec?.outputFormat
                            Log.d("MediaCodecEngine", "RULE 7: Format changed: $newFormat")
                        }
                        MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            // No-op
                        }
                        else -> if (outIndex >= 0) {
                            // 🏁 RULE 6: Log once to confirm buffer production
                            if (lastRenderedPtsUs == Long.MIN_VALUE) {
                                Log.d("MediaCodecEngine", "RULE 6: First buffer produced at index $outIndex")
                            }
                            handleVideoFrame(outIndex, info)
                        }
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
        if (!hasSurface()) {
            Log.e("MediaCodecEngine", "play() ABORT: Surface not ready/valid")
            return
        }
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
            videoTrackIndex = trackIndex

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

            // Rule 3: MediaCodec.configure() MUST see a valid Surface
            check(surface != null) { "Surface lost before configure()" }
            check(surface!!.isValid) { "Surface invalid before configure()" }

            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!).apply {
                configure(format, surface, null, 0)
                start()
                
                // Rule C3: setVideoScalingMode is mandatory
                setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT)
                
                // 🕵️ RULE 8: Verify Color Format is Surface-compatible
                val colorFormat = outputFormat.getInteger(MediaFormat.KEY_COLOR_FORMAT)
                Log.d("MediaCodecEngine", "RULE 8: Decoder started with ColorFormat=$colorFormat")
                
                // Expose Dimensions
                videoWidth = format.getInteger(MediaFormat.KEY_WIDTH)
                videoHeight = format.getInteger(MediaFormat.KEY_HEIGHT)
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
        // Rule S3: MUST NOT call release() which clears surface
        stop()
        try {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return
            currentPfd = pfd
            play(pfd.fileDescriptor)
        } catch (e: Exception) {
            e.printStackTrace()
            stop()
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

        // Stop thread
        videoRunning = false
        try { decodeThread?.join() } catch (_: Exception) {}
        decodeThread = null

        // FULL RESET (required for Surface codecs)
        val localCodec = codec
        codec = null
        try { localCodec?.stop() } catch (_: Exception) {}
        try { localCodec?.release() } catch (_: Exception) {}

        synchronized(extractorLock) {
            extractor?.seekTo(positionUs.coerceAtLeast(0), MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        }

        // Recreate codec
        if (extractor != null && videoTrackIndex >= 0) {
            try {
                // Rule 3: MediaCodec.configure() MUST see a valid Surface
                check(surface != null) { "Surface lost before configure() in seekTo" }
                check(surface!!.isValid) { "Surface invalid before configure() in seekTo" }

                val format = extractor!!.getTrackFormat(videoTrackIndex)
                codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!).apply {
                    configure(format, surface, null, 0)
                    start()

                    // Rule C3: setVideoScalingMode is mandatory
                    setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT)
                    
                    videoWidth = format.getInteger(MediaFormat.KEY_WIDTH)
                    videoHeight = format.getInteger(MediaFormat.KEY_HEIGHT)
                }
            } catch (e: Exception) {
                Log.e("MediaCodecEngine", "Failed to recreate codec in seekTo", e)
            }
        }

        lastRenderedPtsUs = Long.MIN_VALUE
        startDecodeLoop()
    }

    // 🔒 FIX: RACE-PROOF SEEK PREVIEW
    // Temporarily gates decode and uses lock to ensure safe atomic Preview
    override fun onSeekPreview(positionMs: Long) {
        // UI-only update. NO video decode.
    }

    override fun detachSurface() {
        renderEnabled = false
        surface = null
        videoRunning = false
        try { decodeThread?.join() } catch (_: Exception) {}
        decodeThread = null
    }

    override fun recreateVideo() {
        // 🔒 FIX A-2: Force stop if running to allow fresh recreation on new Surface
        if (videoRunning) {
            videoRunning = false
            try { decodeThread?.join() } catch (_: Exception) {}
            decodeThread = null
        }

        val localCodec = codec
        codec = null
        try { localCodec?.stop() } catch (_: Exception) {}
        try { localCodec?.release() } catch (_: Exception) {}
        
        try { extractor?.release() } catch (_: Exception) {}
        extractor = null

        currentPfd?.fileDescriptor?.let { fd ->
            if (hasSurface()) play(fd)
        }
    }

    override fun stop() {
        // 1. Stop decode loop
        videoRunning = false
        decodeEnabled = false
        renderEnabled = false

        try {
            decodeThread?.join()
        } catch (_: Exception) {
        }
        decodeThread = null

        // 2. Release codec safely
        val localCodec = codec
        codec = null
        try { localCodec?.stop() } catch (_: Exception) {}
        try { localCodec?.release() } catch (_: Exception) {}

        // 3. Release extractor
        try {
            extractor?.release()
        } catch (_: Exception) {
        }
        extractor = null

        // 4. Reset state
        inputEOS = false
        lastRenderedPtsUs = Long.MIN_VALUE
        durationMs = 0
        videoTrackIndex = -1

        // NOTE:
        // - surface is NOT cleared here
        // - currentPfd is NOT closed here
        // - play() will reinitialize everything
    }

    override fun release() {
        stop()

        try {
            currentPfd?.close()
        } catch (_: Exception) {
        }
        currentPfd = null

        surface = null
        surfaceReady = false
    }

    private fun releaseResources() {
        videoRunning = false
        renderEnabled = false
        
        try { decodeThread?.join() } catch (_: Exception) {}
        decodeThread = null

        val localCodec = codec
        codec = null
        try { localCodec?.stop() } catch (_: Exception) {}
        try { localCodec?.release() } catch (_: Exception) {}
        
        try { extractor?.release() } catch (_: Exception) {}
        extractor = null
        videoTrackIndex = -1
    }

    // Unused / Deprecated
    override fun onSeekStart() { }
    override fun onSeekCommit(positionMs: Long) { }
    @Deprecated("Use seekTo(positionMs)")
    fun seekToAudioClock() { }
}