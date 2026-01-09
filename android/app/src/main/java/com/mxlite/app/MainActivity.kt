package com.mxlite.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import com.mxlite.app.ui.AppRoot
import com.mxlite.app.ui.PermissionWaitingScreen

class MainActivity : ComponentActivity() {

    private var permissionGranted by mutableStateOf(false)

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->
            permissionGranted = result.values.any { it }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            if (permissionGranted) {
                AppRoot()
            } else {
                PermissionWaitingScreen()
            }
        }

        requestMediaPermission()
    }

    private fun requestMediaPermission() {
        val permissions =
            if (Build.VERSION.SDK_INT >= 33) {
                arrayOf(
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_AUDIO
                )
            } else {
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            }

        permissionLauncher.launch(permissions)
    }
}