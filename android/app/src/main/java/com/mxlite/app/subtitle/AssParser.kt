package com.mxlite.app.subtitle

import java.io.File
import java.io.InputStream

/**
 * Parser for ASS/SSA subtitle files.
 * Extracts timing and text, ignoring advanced features like:
 * - Karaoke effects
 * - Animations
 * - Vector drawing
 * - Complex formatting
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
        
        // Strip ASS formatting codes
        val cleanText = stripAssFormatting(text)
        
        return SubtitleCue(
            startMs = startTime,
            endMs = endTime,
            text = cleanText
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
     * Remove ASS formatting codes from text
     * Handles: {\...}, {\\...}, and other override blocks
     */
    private fun stripAssFormatting(text: String): String {
        var result = text
        
        // Remove override blocks: {...}
        result = result.replace(Regex("""\{[^}]*\}"""), "")
        
        // Replace \N (line break) with actual line break
        result = result.replace("\\N", "\n")
        
        // Replace \n with line break (lowercase variant)
        result = result.replace("\\n", "\n")
        
        // Replace \h with space (hard space)
        result = result.replace("\\h", " ")
        
        return result.trim()
    }
}
