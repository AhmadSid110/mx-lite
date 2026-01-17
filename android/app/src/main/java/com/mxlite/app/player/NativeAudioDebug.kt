package com.mxlite.app.player

object NativeAudioDebug {
    fun snapshot(): String {
        val decodeActive = NativePlayer.dbgDecodeActive()
        return """
engineCreated=${NativePlayer.dbgEngineCreated()}
audioOpened=${NativePlayer.dbgAAudioOpened()}
audioStarted=${NativePlayer.dbgAAudioStarted()}
audioError=${NativePlayer.dbgAAudioError()} (${NativePlayer.dbgAAudioErrorString()})
callbackCalled=${NativePlayer.dbgCallbackCalled()}
decoderProduced=${NativePlayer.dbgDecoderProduced()}
nativePlayCalled=${NativePlayer.dbgNativePlayCalled()}
bufferFill=${NativePlayer.dbgBufferFill()}
openStage = ${NativePlayer.dbgOpenStage()}
DECODE ACTIVE = $decodeActive
        """.trimIndent()
    }
}
