package com.mxlite.app.storage

import android.content.Context
import android.net.Uri
import android.content.Intent

fun persistTreePermission(context: Context, uri: Uri) {
    context.contentResolver.takePersistableUriPermission(
        uri,
        Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    )
}
