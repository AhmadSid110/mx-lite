package com.mxlite.app

import android.content.Context
import android.net.Uri
import android.view.Surface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

class PlayerViewModel(private val context: Context) : ViewModel() {
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

class PlayerViewModelFactory(private val context: Context) :
    ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return PlayerViewModel(context) as T
    }
}