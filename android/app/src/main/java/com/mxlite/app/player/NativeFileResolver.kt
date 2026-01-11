package com.mxlite.app.player

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

object NativeFileResolver {

    fun resolveToInternalPath(context: Context, path: String): String {
        val file = File(path)
        if (file.canRead()) {
            return file.absolutePath
        }

        // Fallback: treat as Uri
        val uri = Uri.parse(path)

        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Cannot open URI")

        val outFile = File(context.cacheDir, "native_audio.tmp")

        FileOutputStream(outFile).use { output ->
            input.copyTo(output)
        }

        return outFile.absolutePath
    }
}