package com.mxlite.app.player

object NativeAudioDebug {
    fun snapshot(engine: PlayerEngine? = null, hasSurface: Boolean = false): String {
        val decodeActive = NativePlayer.dbgDecodeActive()
        return """
Surface=$hasSurface
ENGINE PLAYING=${engine?.isPlaying ?: "null"}
DECODE ACTIVE = $decodeActive
decoderProduced=${NativePlayer.dbgDecoderProduced()}
audioOpened=${NativePlayer.dbgAAudioOpened()}
audioStarted=${NativePlayer.dbgAAudioStarted()}
callbackCalled=${NativePlayer.dbgCallbackCalled()}
CLOCK US = ${NativePlayer.virtualClockUs()}
CLOCK LOG = ${NativePlayer.dbgGetClockLog()}
        """.trimIndent()
    }
}
