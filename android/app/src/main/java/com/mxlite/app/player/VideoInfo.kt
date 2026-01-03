
package com.mxlite.app.player

data class VideoInfo(
    val width: Int,
    val height: Int,
    val rotation: Int = 0,
    val sarNum: Int = 1,
    val sarDen: Int = 1
) {
    val aspectRatio: Float
        get() = (width * sarNum.toFloat()) / (height * sarDen)
}
