package com.mxlite.app.player

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.view.Surface
import java.io.File

class MediaCodecEngine : PlayerEngine {

    private var extractor: MediaExtractor? = null
    private var codec: MediaCodec? = null
    private var surface: Surface? = null

    @Volatile
    private var running = false

    private var decodeThread: Thread? = null

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
        val mime = format.getString(MediaFormat.KEY_MIME)!!

        codec = MediaCodec.createDecoderByType(mime).apply {
            configure(format, surface, null, 0)
            start()
        }

        decodeThread = Thread({ decodeLoop() }, "MediaCodecDecodeThread").apply { start() }
    }

    private fun decodeLoop() {
        val codec = codec ?: return
        val extractor = extractor ?: return
        val info = MediaCodec.BufferInfo()

        while (running) {
            val inIndex = codec.dequeueInputBuffer(10_000)
            if (inIndex >= 0) {
                val buffer = codec.getInputBuffer(inIndex)!!
                val size = extractor.readSampleData(buffer, 0)

                if (size < 0) {
                    codec.queueInputBuffer(
                        inIndex, 0, 0, 0,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    )
                } else {
                    codec.queueInputBuffer(
                        inIndex, 0, size,
                        extractor.sampleTime, 0
                    )
                    extractor.advance()
                }
            }

            val outIndex = codec.dequeueOutputBuffer(info, 10_000)
            if (outIndex >= 0) {
                val ptsMs = info.presentationTimeUs / 1000
                while (running) {
                    if (ptsMs <= PlaybackClock.audioPositionMs + 10) break
                    Thread.sleep(1)
                }
                codec.releaseOutputBuffer(outIndex, true)
            }

            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
        }
    }

    override fun pause() { running = false }

    override fun seekTo(positionMs: Long) {
        extractor?.seekTo(positionMs * 1000, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
    }

    override fun release() {
        running = false
        decodeThread?.join(200)
        decodeThread = null

        codec?.stop()
        codec?.release()
        extractor?.release()

        codec = null
        extractor = null
    }

    override val durationMs: Long get() = 0
    override val currentPositionMs: Long get() = 0
    override val isPlaying: Boolean get() = running
}
