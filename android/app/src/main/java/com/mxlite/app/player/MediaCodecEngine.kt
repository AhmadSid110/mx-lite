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
    private var running = false

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

        decodeLoop()
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
                codec.releaseOutputBuffer(outIndex, true)
            }

            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
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

    override fun pause() {}
    override fun seekTo(positionMs: Long) {}

    override fun release() {
        running = false
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
