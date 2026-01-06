package com.mxlite.app.subtitle

import java.io.File

object SrtParser {

    fun parse(file: File): List<SubtitleLine> {
        val lines = file.readLines()
        val out = mutableListOf<SubtitleLine>()
        var i = 0

        while (i < lines.size) {
            // skip index
            i++
            if (i >= lines.size) break

            val time = lines[i]
            val (start, end) = time.split(" --> ").map { parseTime(it) }
            i++

            val text = StringBuilder()
            while (i < lines.size && lines[i].isNotBlank()) {
                text.append(lines[i]).append('\n')
                i++
            }

            out += SubtitleLine(
                startMs = start,
                endMs = end,
                text = text.toString().trim()
            )

            i++ // skip blank
        }
        return out
    }

    private fun parseTime(s: String): Long {
        val (h, m, rest) = s.split(":")
        val (sec, ms) = rest.split(",")
        return (
            h.toLong() * 3600 +
            m.toLong() * 60 +
            sec.toLong()
        ) * 1000 + ms.toLong()
    }
}
