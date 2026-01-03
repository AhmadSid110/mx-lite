
package com.mxlite.app.player

data class PlayerTab(
    val id: Long,
    val path: String,
    val videoInfo: VideoInfo,
    val isSoftwareDecode: Boolean
)
