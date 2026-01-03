
package com.mxlite.app.player

class AudioDecodeThread(
    private val player: FFmpegPlayer,
    private val audio: AudioRenderer
) : Thread("AudioDecodeThread") {

    @Volatile
    private var running = true

    override fun run() {
        while (running) {
            val frame = player.nativeReadAudioFrame() ?: break

            if (audio.getClockMs() == 0L) {
                audio.init(
                    sampleRate = frame.sampleRate,
                    channels = frame.channels
                )
            }

            audio.write(frame)
        }
    }

    fun shutdown() {
        running = false
    }
}
