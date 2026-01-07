package com.mxlite.app.player

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File

/**
 * Controller for extracting codec information from media files
 * and detecting decoder availability.
 * 
 * READ-ONLY operations - does not modify any playback engines.
 * Uses MediaExtractor to read track information and MediaCodecList
 * to check decoder availability.
 */
object CodecInfoController {
    
    /**
     * Extract all track codec information from a media file.
     * Returns a list of tracks with their MIME types and codec details.
     */
    fun extractTrackInfo(file: File): List<TrackCodecInfo> {
        val tracks = mutableListOf<TrackCodecInfo>()
        val extractor = MediaExtractor()
        
        try {
            extractor.setDataSource(file.absolutePath)
            
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                
                val trackType = when {
                    mime.startsWith("video/") -> TrackCodecInfo.TrackType.VIDEO
                    mime.startsWith("audio/") -> TrackCodecInfo.TrackType.AUDIO
                    else -> TrackCodecInfo.TrackType.UNKNOWN
                }
                
                // Skip non-media tracks
                if (trackType == TrackCodecInfo.TrackType.UNKNOWN) {
                    continue
                }
                
                // Try to get codec name from format (API 29+)
                val codecName = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q &&
                    format.containsKey(MediaFormat.KEY_CODECS_STRING)) {
                    format.getString(MediaFormat.KEY_CODECS_STRING)
                } else null
                
                tracks.add(
                    TrackCodecInfo(
                        trackIndex = i,
                        trackType = trackType,
                        mimeType = mime,
                        codecName = codecName
                    )
                )
            }
        } finally {
            extractor.release()
        }
        
        return tracks
    }
    
    /**
     * Detect decoder capability for a specific MIME type.
     * Checks if a decoder is available and its characteristics.
     */
    fun detectCodecCapability(mimeType: String): CodecCapability {
        val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
        
        // Create appropriate format based on MIME type
        val format = if (mimeType.startsWith("video/")) {
            MediaFormat.createVideoFormat(mimeType, 1920, 1080)
        } else if (mimeType.startsWith("audio/")) {
            MediaFormat.createAudioFormat(mimeType, 44100, 2)
        } else {
            // Fallback for unknown types
            MediaFormat().apply { setString(MediaFormat.KEY_MIME, mimeType) }
        }
        
        val decoderName = codecList.findDecoderForFormat(format)
        
        if (decoderName == null) {
            return CodecCapability(
                mimeType = mimeType,
                isSupported = false
            )
        }
        
        // Get codec info to determine hardware/software
        val codecInfo = try {
            codecList.codecInfos.find { it.name == decoderName }
        } catch (e: Exception) {
            null
        }
        
        val isHardware = codecInfo?.let { isHardwareAccelerated(it) } ?: false
        val isSoftware = codecInfo?.let { isSoftwareOnly(it) } ?: false
        
        return CodecCapability(
            mimeType = mimeType,
            isSupported = true,
            decoderName = decoderName,
            isHardwareAccelerated = isHardware,
            isSoftwareOnly = isSoftware
        )
    }
    
    /**
     * Get codec capabilities for all tracks in a file.
     * Combines track extraction with capability detection.
     */
    fun getFileCodecInfo(file: File): List<Pair<TrackCodecInfo, CodecCapability>> {
        val tracks = extractTrackInfo(file)
        return tracks.map { track ->
            track to detectCodecCapability(track.mimeType)
        }
    }
    
    /**
     * Check if there are any unsupported codecs in the file.
     * Returns a list of unsupported MIME types.
     */
    fun getUnsupportedCodecs(file: File): List<String> {
        val codecInfo = getFileCodecInfo(file)
        return codecInfo
            .filter { !it.second.isSupported }
            .map { it.first.mimeType }
    }
    
    /**
     * Check if a codec is hardware accelerated.
     * Based on codec capabilities and flags.
     */
    private fun isHardwareAccelerated(codecInfo: MediaCodecInfo): Boolean {
        return try {
            // Check if it's NOT software-only
            // Note: isAlias requires API 29+, so we skip it for compatibility
            val name = codecInfo.name.lowercase()
            !name.contains("sw") && !name.contains("google")
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Check if a codec is software-only.
     * Based on codec name patterns.
     */
    private fun isSoftwareOnly(codecInfo: MediaCodecInfo): Boolean {
        return try {
            val name = codecInfo.name.lowercase()
            name.contains("sw") || 
            name.contains("google") ||
            name.contains("omx.google")
        } catch (e: Exception) {
            false
        }
    }
}
