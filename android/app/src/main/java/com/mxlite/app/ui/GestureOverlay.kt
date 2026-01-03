
package com.mxlite.app.ui

import android.app.Activity
import android.media.AudioManager
import android.provider.Settings
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.mxlite.app.player.PlayerViewModel
import kotlin.math.abs

@Composable
fun GestureOverlay(
    vm: PlayerViewModel,
    modifier: Modifier = Modifier
): Modifier {
    val ctx = LocalContext.current
    val activity = ctx as Activity
    val audio = ctx.getSystemService(AudioManager::class.java)
    val screenWidth = LocalConfiguration.current.screenWidthDp

    var startX by remember { mutableStateOf(0f) }
    var startY by remember { mutableStateOf(0f) }

    return modifier.pointerInput(Unit) {
        detectDragGestures(
            onDragStart = {
                startX = it.x
                startY = it.y
            },
            onDrag = { change, drag ->
                val dx = change.position.x - startX
                val dy = change.position.y - startY

                if (abs(dx) > abs(dy)) {
                    // SEEK
                    val deltaMs =
                        (dx / screenWidth) * 120_000
                    vm.seekBy(deltaMs.toLong())
                } else {
                    if (startX < size.width / 2) {
                        // BRIGHTNESS
                        val lp = activity.window.attributes
                        lp.screenBrightness =
                            (lp.screenBrightness - dy / size.height)
                                .coerceIn(0.01f, 1f)
                        activity.window.attributes = lp
                    } else {
                        // VOLUME
                        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        val delta = (-dy / size.height * max).toInt()
                        audio.setStreamVolume(
                            AudioManager.STREAM_MUSIC,
                            (audio.getStreamVolume(AudioManager.STREAM_MUSIC) + delta)
                                .coerceIn(0, max),
                            0
                        )
                    }
                }
            }
        )
    }
}
