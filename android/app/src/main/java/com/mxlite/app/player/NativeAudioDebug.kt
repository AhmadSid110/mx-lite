package com.mxlite.app.player

object NativeAudioDebug {
    fun snapshot(): String {
        return """
engineCreated=${NativePlayer.dbgEngineCreated()}
aaudioOpened=${NativePlayer.dbgAAudioOpened()}
aaudioStarted=${NativePlayer.dbgAAudioStarted()}
callbackCalled=${NativePlayer.dbgCallbackCalled()}
decoderProduced=${NativePlayer.dbgDecoderProduced()}
bufferFill=${NativePlayer.dbgBufferFill()}
        """.trimIndent()
    }
}
