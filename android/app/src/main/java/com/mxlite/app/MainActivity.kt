package com.mxlite.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val vm: PlayerViewModel = viewModel(
                factory = PlayerViewModelFactory(applicationContext)
            )
            PlayerScreen(vm)
        }
    }
}