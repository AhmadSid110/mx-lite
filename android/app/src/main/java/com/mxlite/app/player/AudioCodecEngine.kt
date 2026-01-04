package com.mxlite.app.player

import android.media.*
import java.io.File

class AudioCodecEngine {

    private var extractor: MediaExtractor? = null
    private var codec: MediaCodec? = null
    private var audioTrack: AudioTrack? = null

    @Volatile
    private var running = false

    private var decodeThread: Thread? = null

    fun play(file: File) {
        release()
        running = true

        extractor = MediaExtractor().apply {
            setDataSource(file.absolutePath)
        }

        val trackIndex = selectAudioTrack(extractor!!)
        extractor!!.selectTrack(trackIndex)

        val format = extractor!!.getTrackFormat(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME)!!

        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

        val channelConfig =
            if (channelCount == 1)
                AudioFormat.CHANNEL_OUT_MONO
            else
                AudioFormat.CHANNEL_OUT_STEREO

        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            channelConfig,
            AudioFormat.ENCODING_PCM_16BIT
        )

        audioTrack = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(channelConfig)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build(),
            minBuf,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )

        audioTrack!!.play()

        codec = MediaCodec.createDecoderByType(mime).apply {
            configure(format, null, null, 0)
            start()
        }

        decodeThread = Thread({ decodeLoop() }, "AudioDecodeThread").apply { start() }
    }

    private fun decodeLoop() {
        val codec = codec ?: return
        val extractor = extractor ?: return
        val track = audioTrack ?: return
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
                val outBuf = codec.getOutputBuffer(outIndex)!!
                val pcm = ByteArray(info.size)
                outBuf.get(pcm)
                outBuf.clear()

                track.write(pcm, 0, pcm.size)
                PlaybackClock.audioPositionMs = (track.playbackHeadPosition * 1000L) / sampleRate
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

    fun release() {
        running = false
        decodeThread?.join(200)
        decodeThread = null

        codec?.stop()
        codec?.release()
        extractor?.release()
        audioTrack?.stop()
        audioTrack?.release()

        codec = null
        extractor = null
        audioTrack = null
    }
}
