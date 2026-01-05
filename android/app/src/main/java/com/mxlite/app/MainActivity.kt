package com.mxlite.app

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.mxlite.app.storage.StorageStore
import com.mxlite.app.ui.AppRoot
import kotlinx.coroutines.launch

fun persistTreePermission(context: android.content.Context, uri: Uri) {
    val flags =
        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    context.contentResolver.takePersistableUriPermission(uri, flags)
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 🔒 SAF+ permission recovery (CRITICAL)
        lifecycleScope.launch {
            StorageStore(this@MainActivity).cleanup()
        }

        setContent {
            AppRoot()
        }
    }

    /**
     * Call this AFTER folder picker returns a URI
     */
    fun persistTreePermission(uri: Uri) {
        val flags =
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION

        contentResolver.takePersistableUriPermission(uri, flags)
    }
}
