package com.mxlite.app.subtitle

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object SubtitleParser {

    suspend fun parseFile(file: File): List<SubtitleCue> = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext emptyList()
        runCatching { parseLines(file.readLines()) }.getOrElse { emptyList() }
    }

    suspend fun parseUri(context: Context, uri: Uri): List<SubtitleCue> =
        withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val lines = stream.bufferedReader().readLines()
                    parseLines(lines)
                } ?: emptyList()
            }.getOrElse { emptyList() }
        }

    private fun parseLines(lines: List<String>): List<SubtitleCue> {
        val out = mutableListOf<SubtitleCue>()
        var i = 0

        while (i < lines.size) {
            if (lines[i].isBlank()) {
                i++
                continue
            }

            val timeLineIndex = if (lines[i].isNotEmpty() && lines[i].all { it.isDigit() }) i + 1 else i
            if (timeLineIndex >= lines.size) break

            val time = lines[timeLineIndex]
            val times = time.split(" --> ")
            if (times.size != 2) {
                i = timeLineIndex + 1
                continue
            }
            val start = parseTime(times[0])
            val end = parseTime(times[1])
            if (start == null || end == null) {
                i = timeLineIndex + 1
                continue
            }

            val text = StringBuilder()
            var textIndex = timeLineIndex + 1
            while (textIndex < lines.size && lines[textIndex].isNotBlank()) {
                text.append(lines[textIndex]).append('\n')
                textIndex++
            }

            out += SubtitleCue(
                startMs = start,
                endMs = end,
                text = text.toString().trim()
            )

            i = textIndex + 1
        }
        return out
    }

    private fun parseTime(s: String): Long? {
        val parts = s.trim().split(":")
        if (parts.size != 3) return null
        val (h, m, rest) = parts
        val restParts = rest.split(",")
        if (restParts.size != 2) return null
        val (sec, ms) = restParts
        val hours = h.toLongOrNull() ?: return null
        val minutes = m.toLongOrNull() ?: return null
        val seconds = sec.toLongOrNull() ?: return null
        if (ms.length !in 1..3) return null
        val millisRaw = ms.toLongOrNull() ?: return null
        val millis = when (ms.length) {
            1 -> millisRaw * 100
            2 -> millisRaw * 10
            else -> millisRaw
        }
        return (hours * 3600 + minutes * 60 + seconds) * 1000 + millis
    }
}
