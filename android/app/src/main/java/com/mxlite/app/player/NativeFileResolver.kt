package com.mxlite.app.player

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

object NativeFileResolver {

    fun resolveToInternalPath(context: Context, uri: Uri): String {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Cannot open input stream")

        val outFile = File(context.filesDir, "native_audio.tmp")

        FileOutputStream(outFile).use { output ->
            input.use { it.copyTo(output) }
        }

        return outFile.absolutePath
    }
}