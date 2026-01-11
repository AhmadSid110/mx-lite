package com.mxlite.app.player

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

/**
 * Resolves content:// URIs into real readable file paths
 * for native (C++) code.
 */
object NativeFileResolver {

    fun resolveToInternalPath(context: Context, uri: Uri): String {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: error("Cannot open URI: $uri")

        val outFile = File(
            context.cacheDir,
            "native_audio_${System.currentTimeMillis()}"
        )

        FileOutputStream(outFile).use { output ->
            inputStream.use { input ->
                input.copyTo(output)
            }
        }

        return outFile.absolutePath
    }
}