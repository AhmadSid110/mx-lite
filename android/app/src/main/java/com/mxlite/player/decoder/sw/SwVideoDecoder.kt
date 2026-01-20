package com.mxlite.player.decoder.sw

import android.view.Surface
import com.mxlite.player.decoder.VideoDecoder
import java.io.FileDescriptor

/**
 * Software video decoder (FFmpeg-backed in Phase 2.2+)
 *
 * IMPORTANT:
 * - No audio
 * - No clock
 * - No UI
 * - No Surface ownership
 * - ZERO dependencies in constructor
 */
class SwVideoDecoder : VideoDecoder {

    override fun prepare(
        fd: FileDescriptor,
        surface: Surface
    ) {
        // Phase 2.1: NO-OP
        // Phase 2.2: nativePrepare(fd, surface)
    }

    override fun play() {
        // Phase 2.2: nativePlay()
    }

    override fun pause() {
        // Phase 2.2: nativePause()
    }

    override fun seekTo(positionMs: Long) {
        // Phase 2.2: nativeSeek(positionMs)
    }

    override fun stop() {
        // Phase 2.2: nativeStop()
    }

    override fun release() {
        // Phase 2.2: nativeRelease()
    }

    override fun attachSurface(surface: Surface) {
        // Phase 2.2: nativeAttachSurface(surface)
    }

    override fun detachSurface() {
        // Phase 2.2: nativeDetachSurface()
    }

    override fun recreateVideo() {
        // Phase 2.2: nativeRecreateVideo()
    }

    override val durationMs: Long
        get() = 0L

    override val isPlaying: Boolean
        get() = false

    override val videoWidth: Int
        get() = 0

    override val videoHeight: Int
        get() = 0

    override val decoderName: String
        get() = "Software (Skeleton)"

    override val outputFps: Float
        get() = 0f

    override val droppedFrames: Int
        get() = 0
}
