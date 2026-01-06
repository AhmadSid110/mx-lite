package com.mxlite.app.storage

import android.content.Context
import android.net.Uri
import java.io.File

object SafFileCopier {

    /**
     * Copies SAF Uri into cache so MediaCodec / ExoPlayer can read it
     */
    fun copyToCache(
        context: Context,
        uri: Uri
    ): File {
        val name =
            uri.lastPathSegment
                ?.substringAfterLast('/')
                ?: "saf_media"

        val outFile = File(context.cacheDir, name)

        context.contentResolver.openInputStream(uri)!!.use { input ->
            outFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        return outFile
    }
}
