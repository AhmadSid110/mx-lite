
package com.mxlite.app.player

class PlayerController(
    private val ffmpegPlayer: FFmpegPlayer,
    private val audioRenderer: AudioRenderer,
    private val videoRenderer: VideoRenderer
) {

    private var audioThread: AudioDecodeThread? = null
    private var videoThread: VideoDecodeThread? = null

    fun startPlayback() {
        audioThread = AudioDecodeThread(ffmpegPlayer, audioRenderer).also {
            it.start()
        }

        videoThread = VideoDecodeThread(
            ffmpegPlayer,
            videoRenderer
        ).also {
            it.start()
        }
    }

    
    fun switchAudioTrack(trackIndex: Int) {
        audioThread?.shutdown()
        audioThread = null

        ffmpegPlayer.nativeSelectAudioStream(trackIndex)

        // Seek to current audio clock position
        ffmpegPlayer.nativeSeekTo(audioRenderer.getClockMs())

        startPlayback()
    }

    fun stopPlayback() {

        audioThread?.shutdown()
        videoThread?.shutdown()

        audioThread = null
        videoThread = null

        audioRenderer.stop()
    }

    /**
     * MX-style seek:
     * 1. Stop decode threads
     * 2. Native seek + decoder flush
     * 3. Restart threads
     */
    fun seekTo(targetMs: Int) {
        // Pause decoding
        audioThread?.shutdown()
        videoThread?.shutdown()

        audioThread = null
        videoThread = null

        // Native seek
        ffmpegPlayer.seekTo(targetMs)

        // Restart decoding
        startPlayback()
    }
}
