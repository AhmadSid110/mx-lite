package com.mxlite.app.player

import android.media.*
import java.io.File
import kotlin.concurrent.thread

class AudioCodecEngine : PlaybackClock {

    private var extractor: MediaExtractor? = null
    private var codec: MediaCodec? = null
    private var trackIndex = -1

    private var audioTrack: AudioTrack? = null

    private var playing = false
    private var playedSamples = 0L
    private var sampleRate = 44100

    override val positionMs: Long
        get() = (playedSamples * 1000L) / sampleRate

    fun play(file: File) {
        release()
        playing = true

        extractor = MediaExtractor().apply {
            setDataSource(file.absolutePath)
        }

        trackIndex = selectAudioTrack(extractor!!)
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

        audioTrack = AudioTrack(
            AudioManager.STREAM_MUSIC,
            sampleRate,
            channelConfig,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuffer,
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

    private fun decodeLoop() {
        val extractor = extractor ?: return
        val codec = codec ?: return
        val info = MediaCodec.BufferInfo()

        while (playing) {

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
                val outBuffer = codec.getOutputBuffer(outIndex)!!
                val chunk = ByteArray(info.size)
                outBuffer.get(chunk)
                outBuffer.clear()

                audioTrack?.write(chunk, 0, chunk.size)

                // 🔑 CLOCK MASTER UPDATE
                val bytesPerSample = 2
                val channels = audioTrack!!.channelCount
                val samples = info.size / (bytesPerSample * channels)
                playedSamples += samples

                codec.releaseOutputBuffer(outIndex, false)
            }

            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
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

    fun pause() {
        playing = false
    }

    fun seekTo(positionMs: Long) {
        extractor?.seekTo(
            positionMs * 1000,
            MediaExtractor.SEEK_TO_CLOSEST_SYNC
        )
        playedSamples = (positionMs * sampleRate) / 1000
    }

    fun reset() {
        playedSamples = 0
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
    }
}
