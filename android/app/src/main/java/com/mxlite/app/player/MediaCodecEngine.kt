package com.mxlite.app.player

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.view.Surface
import java.io.File

class MediaCodecEngine(
    private val clock: PlaybackClock
) : PlayerEngine {

    private var extractor: MediaExtractor? = null
    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var decodeThread: Thread? = null
    @Volatile private var running = false
    private var hasAudio = false
    private var currentFilePath: String? = null

    override var durationMs: Long = 0
        private set

    override val currentPositionMs: Long
        get() = if (hasAudio)
            NativePlayer.getClockUs() / 1000
        else
            clock.positionMs

    override val isPlaying: Boolean
        get() = running

    override fun attachSurface(surface: Surface) {
        this.surface = surface
    }

    private fun syncAndRender(outIndex: Int, info: MediaCodec.BufferInfo) {
        val audioUs = NativePlayer.getClockUs()
        if (audioUs <= 0) {
            codec!!.releaseOutputBuffer(outIndex, false)
            return
        }

        val diffUs = info.presentationTimeUs - audioUs

        when {
            diffUs > 0 -> {
                try {
                    Thread.sleep(diffUs / 1000)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    codec!!.releaseOutputBuffer(outIndex, false)
                    return
                }
                codec!!.releaseOutputBuffer(outIndex, true)
            }
            diffUs < -40_000 -> {
                codec!!.releaseOutputBuffer(outIndex, false)
            }
            else -> {
                codec!!.releaseOutputBuffer(outIndex, true)
            }
        }
    }

    private fun startDecodeThread() {
        decodeThread = Thread {
            val info = MediaCodec.BufferInfo()
            var eosReached = false

            while (running && !eosReached) {

                // INPUT
                val inIndex = codec!!.dequeueInputBuffer(0)
                if (inIndex >= 0) {
                    val buf = codec!!.getInputBuffer(inIndex)!!
                    val size = extractor!!.readSampleData(buf, 0)

                    if (size > 0) {
                        codec!!.queueInputBuffer(
                            inIndex, 0, size,
                            extractor!!.sampleTime, 0
                        )
                        extractor!!.advance()
                    } else {
                        codec!!.queueInputBuffer(
                            inIndex, 0, 0, 0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        eosReached = true
                    }
                }

                // OUTPUT
                val outIndex = codec!!.dequeueOutputBuffer(info, 10_000)
                if (outIndex >= 0) {
                    syncAndRender(outIndex, info)
                }
            }
        }

        decodeThread!!.start()
    }

    override fun play(file: File) {
        release()

        currentFilePath = file.absolutePath

        extractor = MediaExtractor().apply {
            setDataSource(file.absolutePath)
        }

        hasAudio = (0 until extractor!!.trackCount).any {
            extractor!!.getTrackFormat(it)
                .getString(MediaFormat.KEY_MIME)
                ?.startsWith("audio/") == true
        }

        val videoTrack = selectVideoTrack(extractor!!)
        if (videoTrack < 0) {
            // No video track found
            release()
            return
        }
        extractor!!.selectTrack(videoTrack)

        val format = extractor!!.getTrackFormat(videoTrack)
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

        running = true
        startDecodeThread()
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
        running = false
        decodeThread?.interrupt()
        try {
            decodeThread?.join()
        } catch (_: Exception) {}
    }

    override fun seekTo(positionMs: Long) {
        // VIDEO SEEK = DESTROY & RECREATE
        val filePath = currentFilePath ?: return
        release()
        
        extractor = MediaExtractor().apply {
            setDataSource(filePath)
        }
        
        // Restore hasAudio state
        hasAudio = (0 until extractor!!.trackCount).any {
            extractor!!.getTrackFormat(it)
                .getString(MediaFormat.KEY_MIME)
                ?.startsWith("audio/") == true
        }
        
        extractor!!.seekTo(positionMs * 1000, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        
        val videoTrack = selectVideoTrack(extractor!!)
        if (videoTrack < 0) {
            // No video track found
            release()
            return
        }
        extractor!!.selectTrack(videoTrack)

        val format = extractor!!.getTrackFormat(videoTrack)
        
        // Restore durationMs
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

        running = true
        startDecodeThread()
    }

    override fun resume() {
        // No-op for video
    }

    override fun release() {
        running = false

        try {
            decodeThread?.join()
        } catch (_: Exception) {}

        codec?.stop()
        codec?.release()
        extractor?.release()

        codec = null
        extractor = null
    }
}