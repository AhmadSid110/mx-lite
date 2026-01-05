package com.mxlite.app.player

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

/**
 * Converts SAF Uri → temporary File for legacy engines.
 * Later you can remove this when MediaCodec supports FD input.
 */
class SafPlayerAdapter(
    private val context: Context,
    private val engine: PlayerEngine
) {

    fun play(uri: Uri) {
        val temp = copyToTempFile(uri)
        engine.play(temp)
    }

    private fun copyToTempFile(uri: Uri): File {
        val input = context.contentResolver.openInputStream(uri)
            ?: error("Cannot open SAF stream")

        val tempFile = File.createTempFile(
            "saf_play_",
            ".mp4",
            context.cacheDir
        )

        FileOutputStream(tempFile).use { output ->
            input.use { it.copyTo(output) }
        }

        return tempFile
    }
}
