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
    private var decodeThread: Thread? = null
    private var lastRenderedPtsUs: Long = Long.MIN_VALUE
    private var suppressRenderUntilAudioCatchup: Boolean = false

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

        extractor = MediaExtractor().apply {
            setDataSource(file.absolutePath)
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

        // START DECODE THREAD
        decodeThread = Thread { decodeLoop() }
        decodeThread?.start()
    }

    private fun decodeLoop() {
        val codec = codec ?: return
        val extractor = extractor ?: return
        val info = MediaCodec.BufferInfo()

        while (running) {

            // ───── INPUT ─────
            val inIndex = codec.dequeueInputBuffer(10_000)
            if (inIndex >= 0) {
                val buffer = codec.getInputBuffer(inIndex)!!
                val size = extractor.readSampleData(buffer, 0)

                if (size < 0) {
                    codec.queueInputBuffer(
                        inIndex,
                        0,
                        0,
                        0,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    )
                } else {
                    codec.queueInputBuffer(
                        inIndex,
                        0,
                        size,
                        extractor.sampleTime,
                        0
                    )
                    extractor.advance()
                }
            }

            // ───── OUTPUT ─────
            val outIndex = codec.dequeueOutputBuffer(info, 10_000)

            if (outIndex >= 0) {
                val videoPtsUs = info.presentationTimeUs
                val audioUs = NativePlayer.getClockUs()

                // Edge Case B: audio clock can be 0 briefly after start/seek — do not render
                if (audioUs <= 0L) {
                    // release without rendering and continue waiting
                    codec.releaseOutputBuffer(outIndex, false)
                    continue
                }

                val diffUs = videoPtsUs - audioUs

                // --------------------------------------------------
                // CASE 1: VIDEO TOO EARLY → WAIT (poll audio clock)
                // --------------------------------------------------
                if (diffUs > 0) {
                    while (running) {
                        val audioNow = NativePlayer.getClockUs()
                        val remaining = videoPtsUs - audioNow

                        if (remaining <= 0) {
                            codec.releaseOutputBuffer(outIndex, true)
                            suppressRenderUntilAudioCatchup = false
                            break
                        }

                        if (remaining < -40_000) {
                            // Became late while waiting — drop
                            codec.releaseOutputBuffer(outIndex, false)
                            suppressRenderUntilAudioCatchup = false
                            break
                        }

                        Thread.sleep(1)
                    }
                }

                // --------------------------------------------------
                // CASE 2: VIDEO TOO LATE → DROP
                // --------------------------------------------------
                else if (diffUs < -40_000) {
                    codec.releaseOutputBuffer(outIndex, false)
                    suppressRenderUntilAudioCatchup = false
                }

                // --------------------------------------------------
                // CASE 3: IN SYNC → RENDER (but ensure post-seek check)
                // --------------------------------------------------
                else {
                    if (suppressRenderUntilAudioCatchup) {
                        // Wait until audio clock reaches the frame PTS
                        while (running && NativePlayer.getClockUs() < videoPtsUs) {
                            Thread.sleep(1)
                        }
                        suppressRenderUntilAudioCatchup = false
                    }
                    codec.releaseOutputBuffer(outIndex, true)
                }
            }
        }
    }

    private fun selectVideoTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) return i
        }
        error("No video track found")
    }

    override fun pause() {
        running = false
    }

    override fun seekTo(positionMs: Long) {
        // STOP VIDEO BEFORE SEEK
        val wasRunning = running
        running = false

        try {
            decodeThread?.join()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        // FLUSH VIDEO CODEC — invalidates old frames and clears output queue
        codec?.flush()

        // RESET VIDEO TIMING STATE
        lastRenderedPtsUs = Long.MIN_VALUE
        // Ensure we don't render immediately after seek; wait for audio clock
        suppressRenderUntilAudioCatchup = true

        // SEEK VIDEO EXTRACTOR (must match audio seek target)
        extractor?.seekTo(
            positionMs * 1000,
            MediaExtractor.SEEK_TO_CLOSEST_SYNC
        )

        // Do NOT render immediately after this. Restart decode thread only if it was running.
        if (wasRunning) {
            running = true
            decodeThread = thread(name = "video-decode") { decodeLoop() }
        }
    }

    override fun release() {
        running = false
        codec?.stop()
        codec?.release()
        extractor?.release()
        codec = null
        extractor = null
        durationMs = 0
    }
}