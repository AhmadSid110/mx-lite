
package com.mxlite.app.player

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.SurfaceHolder
import kotlin.math.max
import kotlin.math.min

class VideoRenderer(
    private val surfaceHolder: SurfaceHolder,
    private val audio: AudioRenderer
) {

    fun render(frame: VideoFrame) {
        val audioClock = audio.getClockMs()

        // Sync: wait for audio
        if (frame.ptsMs > audioClock + 20) return

        val bitmap = yuv420ToBitmap(
            frame.data,
            frame.width,
            frame.height
        )

        val canvas: Canvas = surfaceHolder.lockCanvas() ?: return
        val dst = Rect(0, 0, canvas.width, canvas.height)

        canvas.drawBitmap(bitmap, null, dst, null)
        surfaceHolder.unlockCanvasAndPost(canvas)
    }

    // VERY SIMPLE YUV420P → RGB (CPU, debug-safe)
    private fun yuv420ToBitmap(
        yuv: ByteArray,
        width: Int,
        height: Int
    ): Bitmap {

        val frameSize = width * height
        val rgb = IntArray(frameSize)

        var yp = 0
        for (j in 0 until height) {
            var uvp = frameSize + (j shr 1) * width
            var u = 0
            var v = 0

            for (i in 0 until width) {
                val y = (0xff and yuv[yp].toInt()) - 16
                if ((i and 1) == 0) {
                    v = (0xff and yuv[uvp++].toInt()) - 128
                    u = (0xff and yuv[uvp++].toInt()) - 128
                }

                val y1192 = 1192 * max(y, 0)
                var r = y1192 + 1634 * v
                var g = y1192 - 833 * v - 400 * u
                var b = y1192 + 2066 * u

                r = min(262143, max(0, r))
                g = min(262143, max(0, g))
                b = min(262143, max(0, b))

                rgb[yp] =
                    (0xff000000.toInt()
                        or ((r shl 6) and 0xff0000)
                        or ((g shr 2) and 0xff00)
                        or ((b shr 10) and 0xff))
                yp++
            }
        }

        return Bitmap.createBitmap(rgb, width, height, Bitmap.Config.ARGB_8888)
    }
}
