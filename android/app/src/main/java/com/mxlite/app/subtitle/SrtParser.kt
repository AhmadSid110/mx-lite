
package com.mxlite.app.subtitle

import java.io.File

object SrtParser {

    fun parse(file: File): List<Subtitle> {
        val result = mutableListOf<Subtitle>()
        val lines = file.readLines()
        var i = 0

        while (i < lines.size) {
            if (lines[i].trim().isEmpty()) {
                i++
                continue
            }

            i++ // index line
            if (i >= lines.size) break

            val time = lines[i]
            val parts = time.split(" --> ")
            if (parts.size != 2) {
                i++
                continue
            }

            val start = parseTime(parts[0])
            val end = parseTime(parts[1])

            i++
            val textBuilder = StringBuilder()
            while (i < lines.size && lines[i].isNotBlank()) {
                textBuilder.append(lines[i]).append("\n")
                i++
            }

            result.add(
                Subtitle(
                    startMs = start,
                    endMs = end,
                    text = textBuilder.toString().trim()
                )
            )
        }
        return result
    }

    private fun parseTime(t: String): Long {
        // 00:01:02,345
        val p = t.replace(',', ':').split(':')
        return (
            p[0].toLong() * 3600 +
            p[1].toLong() * 60 +
            p[2].toLong()
        ) * 1000 + p[3].toLong()
    }
}
