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

                if (!videoRunning) {
                    codec.releaseOutputBuffer(outIndex, false)
                    continue
                }

                val framePtsUs = info.presentationTimeUs

                val audioUs = NativePlayer.getClockUs()
                if (audioUs <= 0L) {
                    codec.releaseOutputBuffer(outIndex, false)
                    continue
                }

                val diffUs = framePtsUs - audioUs

                // ------------------------------------------------
                // SUPPRESS FIRST FRAMES AFTER SEEK / PLAY
                // ------------------------------------------------
                if (suppressRenderUntilAudioCatchup) {
                    if (framePtsUs > audioUs) {
                        codec.releaseOutputBuffer(outIndex, false)
                        continue
                    } else {
                        suppressRenderUntilAudioCatchup = false
                    }
                }

                // ------------------------------------------------
                // VIDEO AHEAD → WAIT
                // ------------------------------------------------
                if (diffUs > 15_000) { // 15ms guard
                    Thread.sleep(diffUs / 1000)
                    codec.releaseOutputBuffer(outIndex, true)
                }

                // ------------------------------------------------
                // VIDEO TOO LATE → DROP
                // ------------------------------------------------
                else if (diffUs < -50_000) { // > 1 frame late
                    codec.releaseOutputBuffer(outIndex, false)
                }

                // ------------------------------------------------
                // IN SYNC → RENDER
                // ------------------------------------------------
                else {
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
        videoRunning = false
    }

    override fun seekTo(positionMs: Long) {
        // STOP VIDEO BEFORE SEEK
        val wasRunning = running
        running = false

        // ensure rendering is disabled immediately
        videoRunning = false

        try {
            decodeThread?.join()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        // FLUSH VIDEO CODEC — invalidates old frames and clears output queue
        codec?.flush()

        // RESET VIDEO TIMING STATE
        lastRenderedPtsUs = Long.MIN_VALUE

        // re-enable rendering only after flush; suppress until audio catches up
        videoRunning = true
        suppressRenderUntilAudioCatchup = true

        // SEEK VIDEO EXTRACTOR (must match audio seek target)
        extractor?.seekTo(
            positionMs * 1000,
            MediaExtractor.SEEK_TO_CLOSEST_SYNC
        )

        // Restart decode thread only if it was running.
        if (wasRunning) {
            running = true
            decodeThread = thread(name = "video-decode") { decodeLoop() }
        }
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