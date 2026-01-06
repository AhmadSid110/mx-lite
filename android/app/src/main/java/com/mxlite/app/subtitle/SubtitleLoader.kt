package com.mxlite.app.subtitle

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object SubtitleLoader {

    suspend fun loadFromFile(file: File): List<SubtitleCue> = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext emptyList()
        runCatching { SrtParser.parse(file) }.getOrElse { emptyList() }
    }

    suspend fun loadFromUri(context: Context, uri: Uri): List<SubtitleCue> =
        withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val lines = stream.bufferedReader().readLines()
                    SrtParser.parseLines(lines)
                } ?: emptyList()
            }.getOrElse { emptyList() }
        }
}
