package com.mxlite.app.player

import android.media.*
import android.os.SystemClock
import android.view.Surface
import java.io.File
import kotlin.concurrent.thread

class MediaCodecEngine : PlayerEngine {

    private var extractor: MediaExtractor? = null
    private var videoCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    private var audioTrack: AudioTrack? = null
    private var surface: Surface? = null
    private var running = false

    private var audioStartPtsUs = 0L

    override fun attachSurface(surface: Surface) {
        this.surface = surface
    }

    override fun play(file: File) {
        release()
        running = true

        extractor = MediaExtractor().apply {
            setDataSource(file.absolutePath)
        }

        val videoTrack = selectTrack("video/")
        val audioTrackIndex = selectTrack("audio/")

        extractor!!.selectTrack(videoTrack)
        extractor!!.selectTrack(audioTrackIndex)

        val videoFormat = extractor!!.getTrackFormat(videoTrack)
        val audioFormat = extractor!!.getTrackFormat(audioTrackIndex)

        val videoMime = videoFormat.getString(MediaFormat.KEY_MIME)!!
        val audioMime = audioFormat.getString(MediaFormat.KEY_MIME)!!

        setupAudio(audioFormat, audioMime)
        setupVideo(videoFormat, videoMime)

        thread(name = "AudioDecode") { audioLoop(audioTrackIndex) }
        thread(name = "VideoDecode") { videoLoop(videoTrack) }
    }

    private fun setupAudio(format: MediaFormat, mime: String) {
        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val channelConfig =
            if (channels == 1) AudioFormat.CHANNEL_OUT_MONO
            else AudioFormat.CHANNEL_OUT_STEREO

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
            AudioTrack.getMinBufferSize(
                sampleRate,
                channelConfig,
                AudioFormat.ENCODING_PCM_16BIT
            ),
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        ).apply { play() }

        audioCodec = MediaCodec.createDecoderByType(mime).apply {
            configure(format, null, null, 0)
            start()
        }
    }

    private fun setupVideo(format: MediaFormat, mime: String) {
        videoCodec = MediaCodec.createDecoderByType(mime).apply {
            configure(format, surface, null, 0)
            start()
        }
    }

    private fun audioLoop(trackIndex: Int) {
        val codec = audioCodec ?: return
        val extractor = extractor ?: return
        val info = MediaCodec.BufferInfo()

        while (running) {
            val inIndex = codec.dequeueInputBuffer(10_000)
            if (inIndex >= 0) {
                val buffer = codec.getInputBuffer(inIndex)!!
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) {
                    codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                } else {
                    codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                    extractor.advance()
                }
            }

            val outIndex = codec.dequeueOutputBuffer(info, 10_000)
            if (outIndex >= 0) {
                if (audioStartPtsUs == 0L) audioStartPtsUs = info.presentationTimeUs
                val outBuffer = codec.getOutputBuffer(outIndex)!!
                val pcm = ByteArray(info.size)
                outBuffer.get(pcm)
                outBuffer.clear()
                audioTrack?.write(pcm, 0, pcm.size)
                codec.releaseOutputBuffer(outIndex, false)
            }
        }
    }

    private fun videoLoop(trackIndex: Int) {
        val codec = videoCodec ?: return
        val extractor = extractor ?: return
        val info = MediaCodec.BufferInfo()

        while (running) {
            val inIndex = codec.dequeueInputBuffer(10_000)
            if (inIndex >= 0) {
                val buffer = codec.getInputBuffer(inIndex)!!
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) {
                    codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                } else {
                    codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                    extractor.advance()
                }
            }

            val outIndex = codec.dequeueOutputBuffer(info, 10_000)
            if (outIndex >= 0) {
                val videoPtsMs = (info.presentationTimeUs - audioStartPtsUs) / 1000
                val audioPosMs =
                    audioTrack?.playbackHeadPosition?.toLong()?.times(1000)
                        ?.div(audioTrack!!.sampleRate) ?: 0

                val delay = videoPtsMs - audioPosMs
                if (delay > 0) Thread.sleep(delay)

                codec.releaseOutputBuffer(outIndex, true)
            }
        }
    }

    private fun selectTrack(prefix: String): Int {
        val ex = extractor!!
        for (i in 0 until ex.trackCount) {
            val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(prefix)) return i
        }
        error("Track $prefix not found")
    }

    override fun pause() {}
    override fun seekTo(positionMs: Long) {}
    override fun release() {
        running = false
        videoCodec?.release()
        audioCodec?.release()
        audioTrack?.release()
        extractor?.release()
    }

    override val durationMs: Long get() = 0
    override val currentPositionMs: Long get() = 0
    override val isPlaying: Boolean get() = running
}
