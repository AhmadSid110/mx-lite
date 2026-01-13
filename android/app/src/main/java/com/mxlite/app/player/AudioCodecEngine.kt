package com.mxlite.app.player

import android.media.*
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.max

class AudioCodecEngine : PlaybackClock {

    private var extractor: MediaExtractor? = null
    private var codec: MediaCodec? = null
    private var audioTrack: AudioTrack? = null

    private var sampleRate = 44100
    private var playing = false

    // 🔑 MASTER CLOCK: use native AAudio hardware timestamp
    override val positionMs: Long
        get() = NativePlayer.getClockUs() / 1000

    /* ───────── Public API ───────── */

    fun hasAudioTrack(file: File): Boolean {
        val ex = MediaExtractor()
        ex.setDataSource(file.absolutePath)
        for (i in 0 until ex.trackCount) {
            val mime =
                ex.getTrackFormat(i)
                    .getString(MediaFormat.KEY_MIME)
                    ?: continue
            if (mime.startsWith("audio/")) {
                ex.release()
                return true
            }
        }
        ex.release()
        return false
    }

    fun play(file: File) {
        release()
        playing = true
        // native clock will drive playback position

        extractor = MediaExtractor().apply {
            setDataSource(file.absolutePath)
        }

        val trackIndex = selectAudioTrack(extractor!!)
        extractor!!.selectTrack(trackIndex)

        val format = extractor!!.getTrackFormat(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME)!!

        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

        val channelConfig =
            if (channelCount == 1)
                AudioFormat.CHANNEL_OUT_MONO
            else
                AudioFormat.CHANNEL_OUT_STEREO

        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            channelConfig,
            AudioFormat.ENCODING_PCM_16BIT
        )

        // 🔴 CRITICAL: large buffer to avoid underruns
        val bufferSize = max(minBuffer * 4, 262144)

        audioTrack = AudioTrack(
            AudioManager.STREAM_MUSIC,
            sampleRate,
            channelConfig,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
            AudioTrack.MODE_STREAM
        ).apply {
            play()
        }

        codec = MediaCodec.createDecoderByType(mime).apply {
            configure(format, null, null, 0)
            start()
        }

        thread(name = "audio-decode") {
            decodeLoop()
        }
    }

    fun pause() {
        playing = false
        audioTrack?.pause()
    }

    fun seekTo(positionMs: Long) {
        extractor?.seekTo(
            positionMs * 1000,
            MediaExtractor.SEEK_TO_CLOSEST_SYNC
        )

        audioTrack?.pause()
        audioTrack?.flush()
        audioTrack?.play()
    }

    fun reset() {
        // native clock remains authoritative
        audioTrack?.pause()
        audioTrack?.flush()
    }

    fun release() {
        playing = false

        codec?.stop()
        codec?.release()
        extractor?.release()
        audioTrack?.release()

        codec = null
        extractor = null
        audioTrack = null
        // native clock remains authoritative
    }

    /* ───────── Decode Loop ───────── */

    private fun decodeLoop() {
        val extractor = extractor ?: return
        val codec = codec ?: return
        val track = audioTrack ?: return

        val info = MediaCodec.BufferInfo()

        while (playing) {

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

            val outIndex = codec.dequeueOutputBuffer(info, 10_000)
            if (outIndex >= 0) {
                val outBuffer = codec.getOutputBuffer(outIndex)!!
                val data = ByteArray(info.size)
                outBuffer.get(data)
                outBuffer.clear()

                track.write(data, 0, data.size)

                // Decoder PTS is metadata only; do not update master clock here.

                codec.releaseOutputBuffer(outIndex, false)
            }

            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                break
            }
        }
    }

    private fun selectAudioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return i
        }
        error("No audio track found")
    }
}