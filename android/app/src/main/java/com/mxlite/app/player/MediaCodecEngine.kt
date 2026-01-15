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
    @Volatile private var videoRunning = false
    @Volatile private var inputEOS = false
    private var decodeThread: Thread? = null
    private var lastRenderedPtsUs: Long = Long.MIN_VALUE
    private var suppressRenderUntilAudioCatchup = false
    private var videoStartNano: Long = 0L
    private var firstVideoPtsUs: Long = Long.MIN_VALUE
    private var hasAudio: Boolean = false

    override var durationMs: Long = 0
        private set

    override val currentPositionMs: Long
        get() = if (hasAudio)
            NativePlayer.getClockUs() / 1000
        else
            clock.positionMs

    override val isPlaying: Boolean
        get() = videoRunning

    override fun attachSurface(surface: Surface) {
        this.surface = surface
    }

    private fun handleVideoFrame(outIndex: Int, info: MediaCodec.BufferInfo) {
        val framePtsUs = info.presentationTimeUs

        // Prevent backward or duplicate frames
        if (lastRenderedPtsUs != Long.MIN_VALUE && framePtsUs <= lastRenderedPtsUs) {
            codec!!.releaseOutputBuffer(outIndex, false)
            return
        }

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
            return
        }

        val diffUs = framePtsUs - audioUs

        if (suppressRenderUntilAudioCatchup) {
            if (info.presentationTimeUs > audioUs) {
                codec!!.releaseOutputBuffer(outIndex, false)
                return
            }
            suppressRenderUntilAudioCatchup = false
        }

        when {
            diffUs > 15_000 -> {
                var waitMs = diffUs / 1000
                while (waitMs > 0) {
                    val chunk = min(waitMs, 2L)
                    try {
                        Thread.sleep(chunk)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                    waitMs -= chunk
                }

                codec!!.releaseOutputBuffer(outIndex, true)
                lastRenderedPtsUs = framePtsUs
            }
            diffUs < -50_000 -> {
                codec!!.releaseOutputBuffer(outIndex, false)
            }
            else -> {
                codec!!.releaseOutputBuffer(outIndex, true)
                lastRenderedPtsUs = framePtsUs
            }
        }
    }

    private fun startDecodeLoop() {
        // Prevent starting a second decode thread
        if (decodeThread?.isAlive == true) return
        videoRunning = true

        decodeThread = Thread {
            while (videoRunning && !Thread.currentThread().isInterrupted) {

                // INPUT — gate EOS so we only send it once
                val inIndex = codec?.dequeueInputBuffer(0) ?: break
                if (!inputEOS && inIndex >= 0) {
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
                        inputEOS = true
                    }
                }

                // OUTPUT
                val info = MediaCodec.BufferInfo()
                val outIndex = codec!!.dequeueOutputBuffer(info, 2_000)

                when (outIndex) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // must be consumed; nothing to do here
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        continue
                    }
                    in 0..Int.MAX_VALUE -> {
                        handleVideoFrame(outIndex, info)
                    }
                    else -> {
                        // unknown code; ignore
                    }
                }
            }
        }

        decodeThread?.start()
    }

    override fun play(file: File) {
        release()

        // RESET VIDEO STATE
        lastRenderedPtsUs = Long.MIN_VALUE
        suppressRenderUntilAudioCatchup = true
        inputEOS = false

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
        if (trackIndex < 0) {
            // No video track found — clean up and bail
            release()
            return
        }
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
        startDecodeLoop()
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
        // Video never pauses — only audio pauses
        // PlayerController manages this
    }

    override fun seekTo(positionMs: Long) {
        // Video does NOT seek internally
        // It will be recreated by PlayerController
    }

    override fun resume() {
        // Video never resumes — only audio resumes
        // PlayerController manages this
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