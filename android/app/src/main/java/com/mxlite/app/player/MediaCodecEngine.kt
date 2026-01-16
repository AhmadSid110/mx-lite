package com.mxlite.app.player

import android.content.Context
import android.net.Uri
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import android.os.ParcelFileDescriptor

class MediaCodecEngine(
    private val context: Context,
    private val clock: PlaybackClock
) : PlayerEngine {

    // 🔒 Video engine does NOT own URI state
    override val currentUri: android.net.Uri?
        get() = null

    private var extractor: MediaExtractor? = null
    private var codec: MediaCodec? = null
    private var surface: Surface? = null

    @Volatile private var surfaceReady = false
    @Volatile private var videoRunning = false
    @Volatile private var renderEnabled = true
    @Volatile private var inputEOS = false

    private var decodeThread: Thread? = null
    private var lastRenderedPtsUs: Long = Long.MIN_VALUE

    override var durationMs: Long = 0
        private set

    override val currentPositionMs: Long
        get() = clock.positionMs

    override val isPlaying: Boolean
        get() = videoRunning

    override fun attachSurface(surface: Surface) {
        this.surface = surface
        surfaceReady = true
    }

    fun setRenderEnabled(enabled: Boolean) {
        renderEnabled = enabled
    }

    fun hasSurface(): Boolean = surfaceReady && surface?.isValid == true

    private fun handleVideoFrame(
        outIndex: Int,
        info: MediaCodec.BufferInfo
    ) {
        Log.d("VIDEO", "render=$renderEnabled pts=${info.presentationTimeUs}")
        
        // 🔑 HARD GATE: when audio paused or seeking, never render or wait
        if (!renderEnabled) {
            codec!!.releaseOutputBuffer(outIndex, false)
            lastRenderedPtsUs = info.presentationTimeUs
            return
        }

        val ptsUs = info.presentationTimeUs

        // Drop backward / duplicate frames
        if (lastRenderedPtsUs != Long.MIN_VALUE && ptsUs <= lastRenderedPtsUs) {
            codec!!.releaseOutputBuffer(outIndex, false)
            return
        }

        // Use the master clock (provided by PlayerController) so video never blocks on audio
        val masterUs = clock.positionMs * 1000
        if (masterUs <= 0) {
            // OPTIONAL: You can choose to render the first frame blindly here 
            // if you want to avoid a black screen on start, but strictly 
            // following the clock is safer for sync.
            codec!!.releaseOutputBuffer(outIndex, false)
            return
        }

        val diffUs = ptsUs - masterUs

        // Defensive: drop frames that are extremely late (e.g., after fast seeks)
        if (diffUs < -500_000) {
            // Drop badly late frames to avoid long catch-up stalls and visual glitches
            codec!!.releaseOutputBuffer(outIndex, false)
            return
        }

        when {
            diffUs > 15_000 -> {
                // Video is early. Wait for audio.
                // Clamp sleep to avoid massive stalls (max 500ms safety)
                val sleepMs = (diffUs / 1000).coerceAtMost(500)
                if (sleepMs > 0) Thread.sleep(sleepMs)
                
                codec!!.releaseOutputBuffer(outIndex, true)
                lastRenderedPtsUs = ptsUs
            }
            diffUs < -50_000 -> {
                // Video is late. Drop to catch up.
                codec!!.releaseOutputBuffer(outIndex, false)
            }
            else -> {
                // On time. Render.
                codec!!.releaseOutputBuffer(outIndex, true)
                lastRenderedPtsUs = ptsUs
            }
        }
    }

    private fun startDecodeLoop() {
        if (decodeThread?.isAlive == true) return

        videoRunning = true

        decodeThread = Thread {
            while (videoRunning && !Thread.currentThread().isInterrupted) {

                // INPUT
                val inIndex = codec?.dequeueInputBuffer(0) ?: break
                if (!inputEOS && inIndex >= 0) {
                    val buffer = codec!!.getInputBuffer(inIndex)!!
                    val size = extractor!!.readSampleData(buffer, 0)

                    if (size > 0) {
                        val pts = extractor!!.sampleTime
                        codec!!.queueInputBuffer(inIndex, 0, size, pts, 0)
                        extractor!!.advance()
                    } else {
                        codec!!.queueInputBuffer(
                            inIndex,
                            0,
                            0,
                            0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        inputEOS = true
                    }
                }

                // OUTPUT
                val info = MediaCodec.BufferInfo()
                val outIndex = codec!!.dequeueOutputBuffer(info, 2_000)

                if (outIndex >= 0) {
                    handleVideoFrame(outIndex, info)
                }
            }
        }

        decodeThread!!.start()
    }

    private var currentPfd: ParcelFileDescriptor? = null

    // ✅ NEW: accept FileDescriptor directly
    // NOTE: This method does NOT take ownership of the passed FileDescriptor.
    // The caller must ensure the FD remains valid for the duration of playback,
    // or (preferably) open a dedicated ParcelFileDescriptor via play(uri) so the
    // engine can manage its lifecycle.
    fun play(fd: java.io.FileDescriptor) {
        android.util.Log.e("MX-VIDEO", "MediaCodecEngine.play(fd): hasSurface=${hasSurface()}")
        if (!hasSurface()) {
            // Do NOT start codec without a surface
            android.util.Log.e("MX-VIDEO", "No surface - aborting video start")
            return
        }

        release()

        try {
            val pExtractor = MediaExtractor()
            try {
                pExtractor.setDataSource(fd)
                android.util.Log.e("MX-VIDEO", "MediaExtractor setDataSource OK")
            } catch (e: Exception) {
                android.util.Log.e("MX-VIDEO", "Extractor FAILED", e)
                return
            }
            extractor = pExtractor

            var trackIndex: Int? = null
            for (i in 0 until extractor!!.trackCount) {
                val format = extractor!!.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)
                android.util.Log.e("MX-VIDEO", "Track $i mime=$mime")
                if (mime?.startsWith("video/") == true) {
                    trackIndex = i
                    break
                }
            }

            if (trackIndex == null) {
                android.util.Log.e("MX-VIDEO", "NO VIDEO TRACK FOUND")
                return
            }

            extractor!!.selectTrack(trackIndex)
            val format = extractor!!.getTrackFormat(trackIndex)
            android.util.Log.e("MX-VIDEO", "Creating codec for ${format.getString(MediaFormat.KEY_MIME)}")

            // Sync Extractor logic (Keep this!)
            val masterUs = clock.positionMs * 1000
            if (masterUs > 0) {
                extractor!!.seekTo(masterUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            } else {
                extractor!!.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            }

            durationMs = if (format.containsKey(MediaFormat.KEY_DURATION))
                format.getLong(MediaFormat.KEY_DURATION) / 1000
            else 0

            codec = MediaCodec.createDecoderByType(
                format.getString(MediaFormat.KEY_MIME)!!
            ).apply {
                configure(format, surface, null, 0)
                start()
            }

            inputEOS = false
            lastRenderedPtsUs = Long.MIN_VALUE
            renderEnabled = true

            startDecodeLoop()

        } catch (e: Exception) {
            e.printStackTrace()
            release()
        }
    }

    // ✅ NEW: accept a Uri; open PFD via context and delegate to fd-based play
    override fun play(uri: Uri) {
        release()

        try {
            // Close any existing PFD we own before opening a new one
            try { currentPfd?.close() } catch (_: Exception) {}
            currentPfd = null

            val pfd: ParcelFileDescriptor =
                context.contentResolver.openFileDescriptor(uri, "r") ?: return

            // Delegate to the real implementation
            play(pfd.fileDescriptor)

            // Store so we can close on release()
            currentPfd = pfd

        } catch (e: Exception) {
            e.printStackTrace()
            release()
        }
    }

    override fun pause() {
        // handled by PlayerController via renderEnabled
    }

    override fun resume() {
        // handled by PlayerController via renderEnabled & prepareResume
    }

    override fun seekTo(positionMs: Long) {
        // handled by PlayerController using seekToAudioClock() for precise alignment
    }

    // Seek video extractor to the current native audio clock without recreating pipeline
    fun seekToAudioClock() {
        // Stop decode loop
        videoRunning = false
        try {
            decodeThread?.join()
        } catch (_: InterruptedException) {
        }
        decodeThread = null

        // Flush decoder but do NOT recreate codec/extractor
        try { codec?.flush() } catch (_: Exception) {}

        val audioUs = NativePlayer.getClockUs()
        if (audioUs > 0) {
            extractor?.seekTo(
                audioUs,
                MediaExtractor.SEEK_TO_CLOSEST_SYNC
            )
        } else {
            extractor?.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        }

        lastRenderedPtsUs = Long.MIN_VALUE

        // Restart decode loop
        videoRunning = true
        startDecodeLoop()
    }

    // Prepare internal renderer state before resuming audio clock
    fun prepareResume() {
        lastRenderedPtsUs = Long.MIN_VALUE
        renderEnabled = true
    }

    // Detach surface without stopping audio or destroying the engine's PFD
    override fun detachSurface() {
        // Stop rendering and clear the surface reference
        renderEnabled = false
        surface = null

        // Stop the decode loop but keep extractor and PFD so we can recreate quickly
        videoRunning = false
        try {
            decodeThread?.join()
        } catch (_: InterruptedException) {
        }
        decodeThread = null
    }

    // Recreate the video pipeline only - do NOT restart audio
    override fun recreateVideo() {
        if (videoRunning) return

        // Release codec and extractor resources only
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null
        try { extractor?.release() } catch (_: Exception) {}
        extractor = null

        // If we own a PFD (opened via play(uri)), reuse it; otherwise nothing to do
        val pfdLocal = currentPfd
        if (pfdLocal != null && hasSurface()) {
            // This will re-open extractor/codec and start the decode loop
            play(pfdLocal.fileDescriptor)
        }
    }

    override fun release() {
        videoRunning = false

        try {
            decodeThread?.join()
        } catch (_: InterruptedException) {
        }

        decodeThread = null

        codec?.stop()
        codec?.release()
        extractor?.release()

        // Close any open ParcelFileDescriptor we created
        try {
            currentPfd?.close()
        } catch (_: Exception) {
        }
        currentPfd = null

        codec = null
        extractor = null
        durationMs = 0
    }
}
