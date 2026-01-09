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

        thread(name = "video-decode") {
            decodeLoop()
        }
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
            if (outIndex < 0) continue

            val videoPtsMs = info.presentationTimeUs / 1000
            val audioMs = clock.positionMs

            // Audio not started yet → fallback to video PTS
            val masterMs =
                if (audioMs > 0) audioMs else videoPtsMs

            val delta = videoPtsMs - masterMs

            when {
                // Video early → short sleep
                delta > 15 -> {
                    Thread.sleep(min(delta, 10))
                }

                // Video late → drop frame
                delta < -40 -> {
                    codec.releaseOutputBuffer(outIndex, false)
                    continue
                }
            }

            codec.releaseOutputBuffer(outIndex, true)

            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                break
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
        extractor?.seekTo(
            positionMs * 1000,
            MediaExtractor.SEEK_TO_CLOSEST_SYNC
        )
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