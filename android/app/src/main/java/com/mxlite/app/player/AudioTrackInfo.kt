package com.mxlite.app.player

import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File

/**
 * Audio track metadata extracted from media file.
 * Used for displaying available audio tracks to the user.
 */
data class AudioTrackInfo(
    val trackIndex: Int,
    val language: String?,
    val mimeType: String,
    val channelCount: Int,
    val sampleRate: Int
) {
    val displayName: String
        get() = buildString {
            append("Track ${trackIndex + 1}")
            if (language != null) {
                append(" ($language)")
            }
            append(" - $channelCount ch, ${sampleRate / 1000}kHz")
        }
}

/**
 * Extracts audio track information from a media file.
 * Uses MediaExtractor to enumerate available audio tracks.
 * This is a read-only operation that doesn't modify the engine.
 */
object AudioTrackExtractor {
    
    /**
     * Get all audio tracks from a media file
     */
    fun extractAudioTracks(file: File): List<AudioTrackInfo> {
        val tracks = mutableListOf<AudioTrackInfo>()
        val extractor = MediaExtractor()
        
        try {
            extractor.setDataSource(file.absolutePath)
            
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                
                if (mime.startsWith("audio/")) {
                    val language = if (format.containsKey(MediaFormat.KEY_LANGUAGE)) {
                        format.getString(MediaFormat.KEY_LANGUAGE)
                    } else null
                    
                    val channelCount = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    } else 2
                    
                    val sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    } else 44100
                    
                    tracks.add(
                        AudioTrackInfo(
                            trackIndex = i,
                            language = language,
                            mimeType = mime,
                            channelCount = channelCount,
                            sampleRate = sampleRate
                        )
                    )
                }
            }
        } finally {
            extractor.release()
        }
        
        return tracks
    }
}
