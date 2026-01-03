
package com.mxlite.app.player

import java.util.concurrent.LinkedBlockingQueue

class VideoFrameQueue(
    private val maxSize: Int = 10,
    private val dropThresholdMs: Long = 40
) {

    private val queue = LinkedBlockingQueue<VideoFrame>(maxSize)

    fun push(frame: VideoFrame) {
        if (!queue.offer(frame)) {
            // Drop oldest if queue full
            queue.poll()
            queue.offer(frame)
        }
    }

    fun popForRender(audioClockMs: Long): VideoFrame? {
        val frame = queue.peek() ?: return null

        
        return when {
            frame.ptsMs < audioClockMs - dropThresholdMs -> {
                queue.poll() // drop late frame
                null
            }
            frame.ptsMs <= audioClockMs -> queue.poll() // render
            else -> null // too early
        }

            else -> null // too early
        }
    }

    fun clear() {
        queue.clear()
    }
}
