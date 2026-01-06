package com.mxlite.app.player

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.view.Surface
import java.io.File
import kotlin.concurrent.thread

class MediaCodecEngine(
    private val context: Context,
    private val clock: PlaybackClock
) : PlayerEngine {

    private var extractor: MediaExtractor? = null
    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var running = false

    private var pfd: ParcelFileDescriptor? = null

    override val durationMs: Long = 0

    override val currentPositionMs: Long
        get() = clock.positionMs

    override val isPlaying: Boolean
        get() = running

    override fun attachSurface(surface: Surface) {
        this.surface = surface
    }

    // ───────── FILE PLAYBACK ─────────
    override fun play(file: File) {
        startExtractor {
            it.setDataSource(file.absolutePath)
        }
    }

    // ───────── SAF PLAYBACK (🔥 F-6) ─────────
    override fun play(uri: Uri) {
        startExtractor {
            pfd = context.contentResolver
                .openFileDescriptor(uri, "r")

            it.setDataSource(
                pfd!!.fileDescriptor,
                0,
                pfd!!.statSize
            )
        }
    }

    private fun startExtractor(configure: (MediaExtractor) -> Unit) {
        release()
        running = true

        extractor = MediaExtractor().also(configure)

        val trackIndex = selectVideoTrack(extractor!!)
        extractor!!.selectTrack(trackIndex)

        val format = extractor!!.getTrackFormat(trackIndex)
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
        val extractor = extractor ?: return
        val codec = codec ?: return
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
                        inIndex,
                        0,
                        size,
                        extractor.sampleTime,
                        0
                    )
                    extractor.advance()
                }
            }

            val outIndex = codec.dequeueOutputBuffer(info, 10_000)
            if (outIndex >= 0) {
                val videoMs = info.presentationTimeUs / 1000
                val audioMs = clock.positionMs
                val delta = videoMs - audioMs

                when {
                    delta > 30 -> Thread.sleep(delta)
                    delta < -50 -> {
                        codec.releaseOutputBuffer(outIndex, false)
                        continue
                    }
                }

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
        pfd?.close()

        codec = null
        extractor = null
        pfd = null
    }
}
