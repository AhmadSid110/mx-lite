package com.mxlite.app.ui.browser

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.mxlite.app.storage.StorageStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun rememberFolderPicker(
    onPicked: (Uri) -> Unit
): () -> Unit {
    val context = LocalContext.current
    val activity = context as Activity
    val store = StorageStore(context)

    val launcher =
        (activity as androidx.activity.ComponentActivity)
            .registerForActivityResult(
                ActivityResultContracts.OpenDocumentTree()
            ) { uri ->
                if (uri != null) {
                    persist(context, uri)
                    CoroutineScope(Dispatchers.IO).launch {
                        store.addFolder(uri)
                    }
                    onPicked(uri)
                }
            }

    return {
        launcher.launch(null)
    }
}

private fun persist(context: Context, uri: Uri) {
    context.contentResolver.takePersistableUriPermission(
        uri,
        Intent.FLAG_GRANT_READ_URI_PERMISSION
    )
}
