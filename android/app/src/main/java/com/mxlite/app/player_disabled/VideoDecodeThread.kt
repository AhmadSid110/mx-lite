
package com.mxlite.app.player

class VideoDecodeThread(
    private val frameQueue: VideoFrameQueue,
    private val player: FFmpegPlayer,
    private val renderer: VideoRenderer
) : Thread("VideoDecodeThread") {

    @Volatile
    private var running = true

    override fun run() {
        
        while (running) {
            val audioClock = audioRenderer.getClockMs()

            val frame = player.nativeReadVideoFrame() ?: continue
            renderer.render(frame)
        }
    }

    fun shutdown() {
        running = false
    }
}
