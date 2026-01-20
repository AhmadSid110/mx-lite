package com.mxlite.player.decoder.sw

internal class NativeSwDecoder {

    companion object {
        init {
            System.loadLibrary("mxlite-swdecoder")
        }
    }

    private var nativePtr: Long = nativeCreate()

    private external fun nativeCreate(): Long
    private external fun nativePrepare(ptr: Long, fd: Int)
    private external fun nativePlay(ptr: Long)
    private external fun nativePause(ptr: Long)
    private external fun nativeSeek(ptr: Long, positionMs: Long)
    private external fun nativeStop(ptr: Long)
    private external fun nativeRelease(ptr: Long)

    fun prepare(fd: Int) = nativePrepare(nativePtr, fd)
    fun play() = nativePlay(nativePtr)
    fun pause() = nativePause(nativePtr)
    fun seek(positionMs: Long) = nativeSeek(nativePtr, positionMs)
    fun stop() = nativeStop(nativePtr)

    fun release() {
        if (nativePtr != 0L) {
            nativeRelease(nativePtr)
            nativePtr = 0L
        }
    }
}
