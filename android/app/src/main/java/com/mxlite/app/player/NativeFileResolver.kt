package com.mxlite.app.player

import android.content.Context
import android.net.Uri
import java.io.File

object NativeFileResolver {

    fun resolveToInternalPath(
        context: Context,
        uri: Uri
    ): String {
        val input = context.contentResolver.openInputStream(uri)
            ?: error("Cannot open input stream")

        val outFile = File(
            context.cacheDir,
            "native_media_${System.currentTimeMillis()}"
        )

        outFile.outputStream().use { output ->
            input.use { it.copyTo(output) }
        }

        return outFile.absolutePath
    }
}