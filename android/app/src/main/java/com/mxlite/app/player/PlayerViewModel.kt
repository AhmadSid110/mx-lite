package com.mxlite.app

import android.content.Context
import android.net.Uri
import android.view.Surface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider


import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.*

class PlayerViewModel
(private val context: Context) : ViewModel() {
    private val player = MediaCodecPlayer(context)
    private var surface: Surface? = null

    fun onSurfaceReady(surface: Surface) {
        this.surface = surface
    }

    fun play(uri: Uri) {
        surface ?: return
        player.prepare(uri, surface!!)
        player.play()
    }

    fun pause() {
        player.pause()
    }

    override fun onCleared() {
        player.release()
    }
}


import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.*

class PlayerViewModel
Factory(private val context: Context) :
    ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return PlayerViewModel(context) as T
    }
}

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private var tickerJob: Job? = null

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = CoroutineScope(Dispatchers.Default).launch {
            while (true) {
                _positionMs.value = player.getCurrentPositionMs()
                delay(500)
            }
        }
    }


    fun seekBy(deltaMs: Long) {
        player.seekBy(deltaMs)
    }
