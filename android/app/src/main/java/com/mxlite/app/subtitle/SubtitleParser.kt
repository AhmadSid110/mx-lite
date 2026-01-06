package com.mxlite.app.subtitle

import android.content.Context
import android.net.Uri
import android.content.ContentResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

object SubtitleParser {

    suspend fun parseFile(file: File): List<SubtitleCue> = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext emptyList()
        runCatching {
            val format = detectFormat(file)
            format.cues
        }.getOrElse { emptyList() }
    }

    suspend fun parseUri(context: Context, uri: Uri): List<SubtitleCue> =
        withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val format = detectFormatFromStream(stream, uri.lastPathSegment ?: "")
                    format.cues
                } ?: emptyList()
            }.getOrElse { emptyList() }
        }

    fun parse(
        resolver: ContentResolver,
        uri: Uri
    ): SubtitleFormat {
        val name = uri.lastPathSegment?.lowercase() ?: ""

        resolver.openInputStream(uri)?.use { stream ->
            return when {
                name.endsWith(".srt") -> parseSrt(stream)
                name.endsWith(".ass") -> parseAss(stream)
                else -> error("Unsupported subtitle format")
            }
        }

        error("Unable to open subtitle stream")
    }

    // ───────── FORMAT PARSERS ─────────

    private fun parseSrt(stream: InputStream): SubtitleFormat.Srt {
        val cues = SrtParser.parse(stream)
        return SubtitleFormat.Srt(cues)
    }

    private fun parseAss(stream: InputStream): SubtitleFormat.Ass {
        val cues = AssParser.parse(stream)
        return SubtitleFormat.Ass(cues)
    }

    private fun detectFormat(file: File): SubtitleFormat {
        val name = file.name.lowercase()
        return when {
            name.endsWith(".srt") -> SubtitleFormat.Srt(SrtParser.parse(file))
            name.endsWith(".ass") || name.endsWith(".ssa") -> SubtitleFormat.Ass(AssParser.parse(file))
            else -> {
                // Try to detect by content
                val lines = file.readLines()
                detectFormatFromLines(lines)
            }
        }
    }

    private fun detectFormatFromStream(stream: InputStream, fileName: String): SubtitleFormat {
        val name = fileName.lowercase()
        return when {
            name.endsWith(".srt") -> SubtitleFormat.Srt(SrtParser.parse(stream))
            name.endsWith(".ass") || name.endsWith(".ssa") -> SubtitleFormat.Ass(AssParser.parse(stream))
            else -> {
                // Try to detect by content
                val lines = stream.bufferedReader().readLines()
                detectFormatFromLines(lines)
            }
        }
    }

    private fun detectFormatFromLines(lines: List<String>): SubtitleFormat {
        val hasAssMarkers = lines.any { line ->
            val trimmed = line.trim()
            trimmed.startsWith("[Script Info]", ignoreCase = true) ||
            trimmed.startsWith("[Events]", ignoreCase = true) ||
            trimmed.startsWith("Format:", ignoreCase = true) ||
            trimmed.startsWith("Dialogue:", ignoreCase = true)
        }

        return if (hasAssMarkers) {
            SubtitleFormat.Ass(AssParser.parse(lines.joinToString("\n").byteInputStream()))
        } else {
            SubtitleFormat.Srt(SrtParser.parse(lines.joinToString("\n").byteInputStream()))
        }
    }
}
