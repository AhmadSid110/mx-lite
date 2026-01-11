package com.mxlite.app.player

object NativeAudioDebug {
    fun snapshot(): String {
        return """
engineCreated=${NativePlayer.dbgEngineCreated()}
aaudioOpened=${NativePlayer.dbgAAudioOpened()}
aaudioStarted=${NativePlayer.dbgAAudioStarted()}
aaudioError=${NativePlayer.dbgAAudioError()}
callbackCalled=${NativePlayer.dbgCallbackCalled()}
decoderProduced=${NativePlayer.dbgDecoderProduced()}
nativePlayCalled=${NativePlayer.dbgNativePlayCalled()}
bufferFill=${NativePlayer.dbgBufferFill()}
        """.trimIndent()
    }
}
