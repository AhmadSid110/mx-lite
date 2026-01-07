package com.mxlite.app.subtitle

import androidx.compose.ui.graphics.Color
import java.io.File
import java.io.InputStream

/**
 * Parser for ASS/SSA subtitle files.
 * Extracts timing, text, and basic styling (bold, italic, underline, color, font size).
 * Ignores advanced features like:
 * - Karaoke effects
 * - Animations
 * - Vector drawing
 * - Positioning tags
 */
object AssParser {
    
    /**
     * Parse an ASS/SSA file into subtitle cues
     */
    fun parse(file: File): List<SubtitleCue> {
        val lines = file.readLines()
        return parseLines(lines)
    }

    /**
     * Parse an ASS/SSA stream into subtitle cues
     */
    fun parse(stream: InputStream): List<SubtitleCue> {
        val lines = stream.bufferedReader().readLines()
        return parseLines(lines)
    }
    
    /**
     * Parse ASS/SSA lines into subtitle cues
     */
    private fun parseLines(lines: List<String>): List<SubtitleCue> {
        val cues = mutableListOf<SubtitleCue>()
        var inEventsSection = false
        var formatLine: String? = null
        
        for (line in lines) {
            val trimmed = line.trim()
            
            // Detect Events section
            if (trimmed.equals("[Events]", ignoreCase = true)) {
                inEventsSection = true
                continue
            }
            
            // Detect other sections (exit Events)
            if (trimmed.startsWith("[") && trimmed.endsWith("]") && !trimmed.equals("[Events]", ignoreCase = true)) {
                inEventsSection = false
                formatLine = null
                continue
            }
            
            if (!inEventsSection) continue
            
            // Parse Format line to find column indices
            if (trimmed.startsWith("Format:", ignoreCase = true)) {
                formatLine = trimmed.substringAfter("Format:").trim()
                continue
            }
            
            // Parse Dialogue lines
            if (trimmed.startsWith("Dialogue:", ignoreCase = true)) {
                val format = formatLine ?: continue
                val dialogueLine = trimmed.substringAfter("Dialogue:").trim()
                
                parseDialogueLine(dialogueLine, format)?.let { cue ->
                    cues.add(cue)
                }
            }
        }
        
        return cues.sortedBy { it.startMs }
    }
    
    /**
     * Parse a single Dialogue line using the Format specification
     */
    private fun parseDialogueLine(line: String, format: String): SubtitleCue? {
        val formatFields = format.split(",").map { it.trim() }
        val values = line.split(",", limit = formatFields.size)
        
        if (values.size < formatFields.size) return null
        
        // Find indices for required fields
        val startIndex = formatFields.indexOfFirst { it.equals("Start", ignoreCase = true) }
        val endIndex = formatFields.indexOfFirst { it.equals("End", ignoreCase = true) }
        val textIndex = formatFields.indexOfFirst { it.equals("Text", ignoreCase = true) }
        
        if (startIndex == -1 || endIndex == -1 || textIndex == -1) return null
        
        val startTime = parseAssTime(values[startIndex].trim()) ?: return null
        val endTime = parseAssTime(values[endIndex].trim()) ?: return null
        
        // Extract text (everything from textIndex onward, joined with commas if split)
        val text = values.drop(textIndex).joinToString(",").trim()
        
        // Extract styling and clean text
        val (cleanText, style) = extractStyleAndCleanText(text)
        
        return SubtitleCue(
            startMs = startTime,
            endMs = endTime,
            text = cleanText,
            style = style
        )
    }
    
    /**
     * Parse ASS time format: H:MM:SS.CC (hours:minutes:seconds.centiseconds)
     */
    private fun parseAssTime(timeStr: String): Long? {
        val parts = timeStr.trim().split(":")
        if (parts.size != 3) return null
        
        val hours = parts[0].toLongOrNull() ?: return null
        val minutes = parts[1].toLongOrNull() ?: return null
        
        val secParts = parts[2].split(".")
        if (secParts.size != 2) return null
        
        val seconds = secParts[0].toLongOrNull() ?: return null
        val centiseconds = secParts[1].toLongOrNull() ?: return null
        
        return (hours * 3600 + minutes * 60 + seconds) * 1000 + centiseconds * 10
    }
    
    /**
     * Extract supported styling from ASS override blocks and clean text.
     * Supported: bold (\b1), italic (\i1), underline (\u1), color (\c&H...), font size (\fs)
     * Returns cleaned text and extracted style.
     */
    private fun extractStyleAndCleanText(text: String): Pair<String, SubtitleStyle> {
        var bold = false
        var italic = false
        var underline = false
        var fontSizeSp: Float? = null
        var color: Color? = null
        
        // Find all override blocks {...}
        val overrideRegex = Regex("""\{([^}]*)\}""")
        val matches = overrideRegex.findAll(text)
        
        for (match in matches) {
            val overrideContent = match.groupValues[1]
            
            // Parse override tags
            val tags = overrideContent.split("\\").filter { it.isNotEmpty() }
            for (tag in tags) {
                when {
                    // Bold: \b1 = bold on, \b0 = bold off
                    tag.startsWith("b1") -> bold = true
                    tag.startsWith("b0") -> bold = false
                    
                    // Italic: \i1 = italic on, \i0 = italic off
                    tag.startsWith("i1") -> italic = true
                    tag.startsWith("i0") -> italic = false
                    
                    // Underline: \u1 = underline on, \u0 = underline off
                    tag.startsWith("u1") -> underline = true
                    tag.startsWith("u0") -> underline = false
                    
                    // Font size: \fs<size>
                    tag.startsWith("fs") -> {
                        val size = tag.substring(2).toFloatOrNull()
                        if (size != null && size > 0) {
                            fontSizeSp = size
                        }
                    }
                    
                    // Primary color: \c&H<bbggrr>& or \c&HBBGGRR&
                    tag.startsWith("c&H") && tag.endsWith("&") -> {
                        val colorHex = tag.substring(3, tag.length - 1)
                        color = parseAssColor(colorHex)
                    }
                    
                    // Alternative color format: \1c&H<bbggrr>&
                    tag.startsWith("1c&H") && tag.endsWith("&") -> {
                        val colorHex = tag.substring(4, tag.length - 1)
                        color = parseAssColor(colorHex)
                    }
                }
            }
        }
        
        // Remove all override blocks
        var cleanText = text.replace(overrideRegex, "")
        
        // Replace \N (line break) with actual line break
        cleanText = cleanText.replace("\\N", "\n")
        
        // Replace \n with line break (lowercase variant)
        cleanText = cleanText.replace("\\n", "\n")
        
        // Replace \h with space (hard space)
        cleanText = cleanText.replace("\\h", " ")
        
        val style = SubtitleStyle(
            bold = bold,
            italic = italic,
            underline = underline,
            fontSizeSp = fontSizeSp,
            color = color
        )
        
        return Pair(cleanText.trim(), style)
    }
    
    /**
     * Parse ASS color format: &HBBGGRR or BBGGRR (BGR hex)
     * ASS uses BGR format, not RGB
     */
    private fun parseAssColor(hex: String): Color? {
        return try {
            // Remove any leading/trailing whitespace or &H prefix
            val cleanHex = hex.replace("&H", "").replace("&", "").trim()
            
            // Parse as BGR (6 digits expected)
            if (cleanHex.length == 6) {
                val bb = cleanHex.substring(0, 2).toInt(16)
                val gg = cleanHex.substring(2, 4).toInt(16)
                val rr = cleanHex.substring(4, 6).toInt(16)
                Color(red = rr / 255f, green = gg / 255f, blue = bb / 255f)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}
