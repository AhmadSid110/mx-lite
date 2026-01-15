package com.mxlite.app.player

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.view.Surface
import java.io.File

class MediaCodecEngine(
    private val clock: NativeClock
) : PlayerEngine {

    private var extractor: MediaExtractor? = null
    private var codec: MediaCodec? = null
    private var surface: Surface? = null

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
    }

    fun setRenderEnabled(enabled: Boolean) {
        renderEnabled = enabled
    }

    private fun handleVideoFrame(
        outIndex: Int,
        info: MediaCodec.BufferInfo
    ) {
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

        val audioUs = NativePlayer.getClockUs()
        if (audioUs <= 0) {
            codec!!.releaseOutputBuffer(outIndex, false)
            return
        }

        val diffUs = ptsUs - audioUs

        when {
            diffUs > 15_000 -> {
                Thread.sleep(diffUs / 1000)
                codec!!.releaseOutputBuffer(outIndex, true)
                lastRenderedPtsUs = ptsUs
            }
            diffUs < -50_000 -> {
                codec!!.releaseOutputBuffer(outIndex, false)
            }
            else -> {
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

    override fun play(file: File) {
        release()

        extractor = MediaExtractor().apply {
            setDataSource(file.absolutePath)
        }

        val trackIndex = (0 until extractor!!.trackCount).firstOrNull { i ->
            extractor!!
                .getTrackFormat(i)
                .getString(MediaFormat.KEY_MIME)
                ?.startsWith("video/") == true
        } ?: return

        extractor!!.selectTrack(trackIndex)
        val format = extractor!!.getTrackFormat(trackIndex)

        durationMs =
            if (format.containsKey(MediaFormat.KEY_DURATION))
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
    }

    override fun pause() {
        // handled by PlayerController via renderEnabled
    }

    override fun resume() {
        // handled by PlayerController via renderEnabled
    }

    override fun seekTo(positionMs: Long) {
        // handled by PlayerController (recreate video)
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

        codec = null
        extractor = null
        durationMs = 0
    }
}