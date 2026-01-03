
package com.mxlite.app.player

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class PlayerGestureController(
    private val activity: Activity,
    private val playerController: PlayerController,
    private val getCurrentPositionMs: () -> Long
) : View.OnTouchListener {

    private val audioManager =
        activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val maxVolume =
        audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    private var startX = 0f
    private var startY = 0f

    private var startVolume = 0
    private var startBrightness = 0f

    private var seeking = false
    private var seekOffsetMs = 0L

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {

            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y

                startVolume =
                    audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

                startBrightness =
                    activity.window.attributes.screenBrightness.takeIf {
                        it >= 0f
                    } ?: 0.5f

                seeking = false
                seekOffsetMs = 0
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - startX
                val dy = event.y - startY

                if (!seeking && abs(dx) > abs(dy)) {
                    seeking = true
                }

                if (seeking) {
                    // Horizontal → SEEK (preview only)
                    seekOffsetMs = (dx * 50).toLong()
                } else {
                    // Vertical gestures
                    val percent = -dy / v.height

                    if (startX > v.width / 2) {
                        // RIGHT → VOLUME
                        val newVol =
                            (startVolume + percent * maxVolume)
                                .toInt()
                                .coerceIn(0, maxVolume)

                        audioManager.setStreamVolume(
                            AudioManager.STREAM_MUSIC,
                            newVol,
                            0
                        )
                    } else {
                        // LEFT → BRIGHTNESS
                        val lp = activity.window.attributes
                        lp.screenBrightness =
                            (startBrightness + percent)
                                .coerceIn(0.05f, 1f)
                        activity.window.attributes = lp
                    }
                }
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                if (seeking && seekOffsetMs != 0L) {
                    val target =
                        max(0L, getCurrentPositionMs() + seekOffsetMs)

                    playerController.seekTo(target.toInt())
                }
                seeking = false
            }
        }
        return true
    }
}
