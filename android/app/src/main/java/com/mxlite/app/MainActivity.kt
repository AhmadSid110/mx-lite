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
import com.mxlite.app.ui.theme.MxLiteTheme
import com.mxlite.app.debug.CrashHandler
import androidx.compose.material3.ExperimentalMaterial3Api

@ExperimentalMaterial3Api
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
        CrashHandler.install(this)

        setContent {
            MxLiteTheme {
                if (permissionGranted) {
                    AppRoot()
                } else {
                    PermissionWaitingScreen()
                }

                // For testing Phase 1, you can temporarily replace the above with a Surface:
                /*
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Text("Phase 1 Complete: Cinema Theme Active")
                }
                */
            }
        }

        permissionLauncher.launch(requiredPermissions())
    }

    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
}