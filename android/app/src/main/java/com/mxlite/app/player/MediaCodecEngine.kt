package com.mxlite.app.player

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.view.Surface
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.min

class MediaCodecEngine(
    private val clock: PlaybackClock
) : PlayerEngine {

    private var extractor: MediaExtractor? = null
    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var running = false
    private var videoRunning = false
    private var decodeThread: Thread? = null
    private var lastRenderedPtsUs: Long = Long.MIN_VALUE
    private var suppressRenderUntilAudioCatchup = false
    private var videoStartNano: Long = 0L
    private var firstVideoPtsUs: Long = Long.MIN_VALUE
    private var hasAudio: Boolean = false

    override var durationMs: Long = 0
        private set

    override val currentPositionMs: Long
        get() = clock.positionMs

    override val isPlaying: Boolean
        get() = running

    override fun attachSurface(surface: Surface) {
        this.surface = surface
    }

    override fun play(file: File) {
        release()
        running = true

        // RESET VIDEO STATE
        lastRenderedPtsUs = Long.MIN_VALUE
        suppressRenderUntilAudioCatchup = true
        videoRunning = true

        extractor = MediaExtractor().apply {
            setDataSource(file.absolutePath)
        }

        // Detect whether the file contains an audio track
        hasAudio = run {
            var found = false
            for (i in 0 until extractor!!.trackCount) {
                val fmt = extractor!!.getTrackFormat(i)
                val m = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (m.startsWith("audio/")) {
                    found = true
                    break
                }
            }
            found
        }

        val trackIndex = selectVideoTrack(extractor!!)
        extractor!!.selectTrack(trackIndex)

        val format = extractor!!.getTrackFormat(trackIndex)

        durationMs =
            if (format.containsKey(MediaFormat.KEY_DURATION))
                format.getLong(MediaFormat.KEY_DURATION) / 1000
            else 0L

        val mime = format.getString(MediaFormat.KEY_MIME)!!

        codec = MediaCodec.createDecoderByType(mime).apply {
            configure(format, surface, null, 0)
            start()
        }

        // Initialize video clock
        videoStartNano = System.nanoTime()
        firstVideoPtsUs = Long.MIN_VALUE

        // START DECODE THREAD
        decodeThread = Thread { decodeLoop() }
        decodeThread?.start()
    }

    private fun decodeLoop() {
        while (videoRunning) {

            // INPUT (never blocked)
            val inIndex = codec?.dequeueInputBuffer(0) ?: break
            if (inIndex >= 0) {
                val inputBuffer = codec?.getInputBuffer(inIndex)
                val size = extractor?.readSampleData(inputBuffer!!, 0) ?: -1

                if (size > 0) {
                    val pts = extractor!!.sampleTime
                    codec!!.queueInputBuffer(inIndex, 0, size, pts, 0)
                    extractor!!.advance()
                } else {
                    codec!!.queueInputBuffer(
                        inIndex, 0, 0, 0,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    )
                }
            }

            // OUTPUT (sync gated)
            val info = MediaCodec.BufferInfo()
            val outIndex = codec!!.dequeueOutputBuffer(info, 10_000)

            if (outIndex >= 0) {
                    val framePtsUs = info.presentationTimeUs

                    val audioUs = if (hasAudio) {
                        NativePlayer.getClockUs()
                    } else {
                        // 🔥 VIDEO-ONLY FALLBACK CLOCK
                        if (firstVideoPtsUs == Long.MIN_VALUE) {
                            firstVideoPtsUs = framePtsUs
                            videoStartNano = System.nanoTime()
                        }

                        val elapsedUs = (System.nanoTime() - videoStartNano) / 1000
                        firstVideoPtsUs + elapsedUs
                    }

                    if (audioUs <= 0) {
                        codec!!.releaseOutputBuffer(outIndex, false)
                        continue
                    }

                    val diffUs = framePtsUs - audioUs

                if (suppressRenderUntilAudioCatchup) {
                    if (info.presentationTimeUs > audioUs) {
                        codec!!.releaseOutputBuffer(outIndex, false)
                        continue
                    }
                    suppressRenderUntilAudioCatchup = false
                }

                when {
                    diffUs > 15_000 -> {
                        Thread.sleep(diffUs / 1000)
                        codec!!.releaseOutputBuffer(outIndex, true)
                    }
                    diffUs < -50_000 -> {
                        codec!!.releaseOutputBuffer(outIndex, false)
                    }
                    else -> {
                        codec!!.releaseOutputBuffer(outIndex, true)
                    }
                }
            }
        }
    }

    private fun selectVideoTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) {
                return i
            }
        }
        return -1
    }

    override fun pause() {
        // Pause video only: stop decode thread and rendering
        videoRunning = false

        try {
            decodeThread?.join()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    override fun seekTo(positionMs: Long) {
        // STOP VIDEO THREAD BEFORE SEEK (video-only)
        videoRunning = false

        try {
            decodeThread?.join()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        // FLUSH VIDEO CODEC — invalidates old frames and clears output queue
        codec?.flush()

        // RESET VIDEO TIMING STATE — avoid reusing old PTS
        lastRenderedPtsUs = Long.MIN_VALUE

        // SEEK VIDEO EXTRACTOR (must match audio seek target)
        extractor?.seekTo(
            positionMs * 1000,
            MediaExtractor.SEEK_TO_CLOSEST_SYNC
        )

        // re-enable rendering only after flush; suppress until audio catches up
        suppressRenderUntilAudioCatchup = true
        videoRunning = true

        // Restart decode thread for video
        decodeThread = thread(name = "video-decode") { decodeLoop() }
    }

    override fun release() {
        running = false
        videoRunning = false
        codec?.stop()
        codec?.release()
        extractor?.release()
        codec = null
        extractor = null
        durationMs = 0
    }
}