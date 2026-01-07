package com.mxlite.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.mxlite.app.storage.StorageStore
import com.mxlite.app.ui.AppRoot
import kotlinx.coroutines.launch

// 🔒 SAF tree permission helper
fun persistTreePermission(context: Context, uri: Uri) {
    val flags =
        Intent.FLAG_GRANT_READ_URI_PERMISSION or
        Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    context.contentResolver.takePersistableUriPermission(uri, flags)
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestMediaPermissionsIfNeeded()

        // 🔒 SAF permission recovery (CRITICAL)
        lifecycleScope.launch {
            StorageStore(this@MainActivity).cleanup()
        }

        setContent {
            AppRoot()
        }
    }

    /**
     * Android media permission request (filesystem playback)
     */
    private fun requestMediaPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            val permissions = arrayOf(
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO
            )

            val missing = permissions.any {
                ContextCompat.checkSelfPermission(this, it)
                        != PackageManager.PERMISSION_GRANTED
            }

            if (missing) {
                ActivityCompat.requestPermissions(this, permissions, 100)
            }
        } else {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                    100
                )
            }
        }
    }

    /**
     * Call this AFTER folder picker returns a URI
     */
    fun persistTreePermission(uri: Uri) {
        val flags =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION

        contentResolver.takePersistableUriPermission(uri, flags)
    }
}