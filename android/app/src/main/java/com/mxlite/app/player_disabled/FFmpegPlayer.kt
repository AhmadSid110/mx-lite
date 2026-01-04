
package com.mxlite.app.player

class FFmpegPlayer {

    external fun nativeOpen(path: String): Boolean
    external fun nativeGetDurationMs(): Long

    external fun nativeReadVideoFrame(): VideoFrame?
    external fun nativeReadAudioFrame(): AudioFrame?

    external fun nativeSeekTo(ms: Long)

    fun seekTo(ms: Long) {
        nativeSeekTo(ms)
    }
    
    external fun nativeGetAudioTracks(): Array<AudioTrackInfo>
    external fun nativeSelectAudioStream(index: Int)
    
    external fun nativeClose()


    companion object {
        init {
            System.loadLibrary("ffmpeg_jni")
        }
    }
}

data class VideoFrame(
    val width: Int,
    val height: Int,
    val ptsMs: Long,
    val data: ByteArray
)

data class AudioFrame(
    val sampleRate: Int,
    val channels: Int,
    val ptsMs: Long,
    val pcm: ByteArray
)
