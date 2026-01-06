package com.mxlite.app.subtitle

import java.io.File

object SrtParser {

    fun parse(file: File): List<SubtitleLine> {
        val lines = file.readLines()
        val out = mutableListOf<SubtitleLine>()
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

            out += SubtitleLine(
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
        val millis = ms.padStart(3, '0').take(3).toLongOrNull() ?: return null
        return (hours * 3600 + minutes * 60 + seconds) * 1000 + millis
    }
}
