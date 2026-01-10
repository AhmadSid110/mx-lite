package com.mxlite.app.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Polls native audio debug state and exposes it as observable Compose state.
 * Designed for real-time debugging on physical devices without logcat.
 */
class NativeAudioDebug {
    
    data class DebugState(
        val engineCreated: Boolean = false,
        val aaudioOpened: Boolean = false,
        val aaudioStarted: Boolean = false,
        val callbackCalled: Boolean = false,
        val decoderProduced: Boolean = false,
        val bufferFill: Int = 0,
        val clockPositionMs: Long = 0
    )
    
    var state by mutableStateOf(DebugState())
        private set
    
    private var pollJob: Job? = null
    // Use Main dispatcher because mutableStateOf updates must be on main thread
    // JNI calls are simple atomic reads with no blocking, so main thread is safe
    private val scope = CoroutineScope(Dispatchers.Main)
    
    /**
     * Start polling native debug state every 250ms.
     * Safe to call multiple times (idempotent).
     */
    fun startPolling() {
        if (pollJob?.isActive == true) return
        
        pollJob = scope.launch {
            while (isActive) {
                try {
                    state = DebugState(
                        engineCreated = NativePlayer.dbgEngineCreated(),
                        aaudioOpened = NativePlayer.dbgAAudioOpened(),
                        aaudioStarted = NativePlayer.dbgAAudioStarted(),
                        callbackCalled = NativePlayer.dbgCallbackCalled(),
                        decoderProduced = NativePlayer.dbgDecoderProduced(),
                        bufferFill = NativePlayer.dbgBufferFill(),
                        clockPositionMs = NativePlayer.nativeGetClockUs() / 1000
                    )
                } catch (e: Exception) {
                    // JNI errors (e.g. native not initialized) - keep previous state
                    // All native atomics have safe default values (false/0)
                }
                
                delay(250)
            }
        }
    }
    
    /**
     * Stop polling.
     */
    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }
}
