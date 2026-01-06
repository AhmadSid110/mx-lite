package com.mxlite.app.subtitle

import android.net.Uri
import android.content.ContentResolver
import java.io.InputStream

object SubtitleParser {

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
}
