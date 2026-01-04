package com.mxlite.app.player

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.view.Surface
import java.io.File

class VideoDecoder(
    private val file: File,
    private val surface: Surface
) : Thread("VideoDecoder") {

    @Volatile
    private var running = true

    override fun run() {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)

        var videoTrackIndex = -1
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) {
                videoTrackIndex = i
                extractor.selectTrack(i)
                break
            }
        }

        if (videoTrackIndex == -1) return

        val format = extractor.getTrackFormat(videoTrackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME)!!

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, surface, null, 0)
        codec.start()

        val bufferInfo = MediaCodec.BufferInfo()

        while (running) {
            val inputIndex = codec.dequeueInputBuffer(10_000)
            if (inputIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputIndex)!!
                val sampleSize = extractor.readSampleData(inputBuffer, 0)

                if (sampleSize < 0) {
                    codec.queueInputBuffer(
                        inputIndex,
                        0,
                        0,
                        0,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    )
                    break
                } else {
                    codec.queueInputBuffer(
                        inputIndex,
                        0,
                        sampleSize,
                        extractor.sampleTime,
                        0
                    )
                    extractor.advance()
                }
            }

            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
            if (outputIndex >= 0) {
                codec.releaseOutputBuffer(outputIndex, true)
            }
        }

        codec.stop()
        codec.release()
        extractor.release()
    }

    fun shutdown() {
        running = false
        interrupt()
    }
}
