
package com.mxlite.app.player

import android.content.Context
import android.media.*
import android.net.Uri
import android.view.Surface
import kotlin.concurrent.thread

class MediaCodecPlayer(private val context: Context) {

    private var videoExtractor: MediaExtractor? = null
    private var audioExtractor: MediaExtractor? = null

    private var videoCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null

    private var audioTrack: AudioTrack? = null
    private var audioSampleRate = 44100

    @Volatile
    private var isPlaying = false

    fun prepare(uri: Uri, surface: Surface) {
        videoExtractor = MediaExtractor()
        audioExtractor = MediaExtractor()

        context.contentResolver.openFileDescriptor(uri, "r")?.let {
            videoExtractor!!.setDataSource(it.fileDescriptor)
            audioExtractor!!.setDataSource(it.fileDescriptor)
        }

        var vTrack = -1
        var aTrack = -1

        for (i in 0 until videoExtractor!!.trackCount) {
            val format = videoExtractor!!.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/") && vTrack < 0) vTrack = i
            if (mime.startsWith("audio/") && aTrack < 0) aTrack = i
        }

        videoExtractor!!.selectTrack(vTrack)
        val vFormat = videoExtractor!!.getTrackFormat(vTrack)
        videoCodec = MediaCodec.createDecoderByType(
            vFormat.getString(MediaFormat.KEY_MIME)!!
        )
        videoCodec!!.configure(vFormat, surface, null, 0)
        videoCodec!!.start()

        audioExtractor!!.selectTrack(aTrack)
        val aFormat = audioExtractor!!.getTrackFormat(aTrack)
        audioCodec = MediaCodec.createDecoderByType(
            aFormat.getString(MediaFormat.KEY_MIME)!!
        )
        audioCodec!!.configure(aFormat, null, null, 0)
        audioCodec!!.start()

        val sr = aFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val ch = aFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

        audioTrack = AudioTrack(
            AudioManager.STREAM_MUSIC,
            sr,
            if (ch == 1)
                AudioFormat.CHANNEL_OUT_MONO
            else
                AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
            AudioTrack.getMinBufferSize(
                sr,
                if (ch == 1)
                    AudioFormat.CHANNEL_OUT_MONO
                else
                    AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            ),
            AudioTrack.MODE_STREAM
        )

        audioTrack!!.play()
    }

    fun play() {
        isPlaying = true
        startVideo()
        startAudio()
    }

    fun pause() {
        isPlaying = false
    }

    fun release() {
        isPlaying = false
        videoExtractor?.release()
        audioExtractor?.release()
        videoCodec?.release()
        audioCodec?.release()
        audioTrack?.release()
    }

    private fun startVideo() = thread {
        val info = MediaCodec.BufferInfo()
        while (isPlaying) {
            val inIdx = videoCodec!!.dequeueInputBuffer(10_000)
            if (inIdx >= 0) {
                val buf = videoCodec!!.getInputBuffer(inIdx)!!
                val size = videoExtractor!!.readSampleData(buf, 0)
                if (size < 0) {
                    videoCodec!!.queueInputBuffer(
                        inIdx, 0, 0, 0,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    )
                } else {
                    videoCodec!!.queueInputBuffer(
                        inIdx, 0, size,
                        videoExtractor!!.sampleTime, 0
                    )
                    videoExtractor!!.advance()
                }
            }

            val outIdx = videoCodec!!.dequeueOutputBuffer(info, 10_000)
            if (outIdx >= 0) {
                videoCodec!!.releaseOutputBuffer(outIdx, true)
            }
        }
    }

    private fun startAudio() = thread {
        val info = MediaCodec.BufferInfo()
        while (isPlaying) {
            val inIdx = audioCodec!!.dequeueInputBuffer(10_000)
            if (inIdx >= 0) {
                val buf = audioCodec!!.getInputBuffer(inIdx)!!
                val size = audioExtractor!!.readSampleData(buf, 0)
                if (size < 0) {
                    audioCodec!!.queueInputBuffer(
                        inIdx, 0, 0, 0,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    )
                } else {
                    audioCodec!!.queueInputBuffer(
                        inIdx, 0, size,
                        audioExtractor!!.sampleTime, 0
                    )
                    audioExtractor!!.advance()
                }
            }

            val outIdx = audioCodec!!.dequeueOutputBuffer(info, 10_000)
            if (outIdx >= 0) {
                val buf = audioCodec!!.getOutputBuffer(outIdx)!!
                val pcm = ByteArray(info.size)
                buf.get(pcm)
                buf.clear()
                audioTrack!!.write(pcm, 0, pcm.size)
                audioCodec!!.releaseOutputBuffer(outIdx, false)
            }
        }
    }
}


    fun getCurrentPositionMs(): Long {
        val track = audioTrack ?: return 0L
        val sampleRate = 44100
        return track.playbackHeadPosition * 1000L / audioSampleRate
    }


    fun seekBy(deltaMs: Long) {
        videoExtractor?.seekTo(
            (getCurrentPositionMs() + deltaMs) * 1000,
            MediaExtractor.SEEK_TO_CLOSEST_SYNC
        )
        audioExtractor?.seekTo(
            (getCurrentPositionMs() + deltaMs) * 1000,
            MediaExtractor.SEEK_TO_CLOSEST_SYNC
        )
    }
