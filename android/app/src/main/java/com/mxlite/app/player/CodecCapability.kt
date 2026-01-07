package com.mxlite.app.player

/**
 * Information about a media track's codec.
 * Extracted from a media file using MediaExtractor.
 */
data class TrackCodecInfo(
    val trackIndex: Int,
    val trackType: TrackType,
    val mimeType: String,
    val codecName: String? = null
) {
    enum class TrackType {
        VIDEO,
        AUDIO,
        UNKNOWN
    }
    
    val displayMimeType: String
        get() = mimeType.removePrefix("video/").removePrefix("audio/")
}

/**
 * Capability information for a codec.
 * Indicates whether a decoder is available and its characteristics.
 */
data class CodecCapability(
    val mimeType: String,
    val isSupported: Boolean,
    val decoderName: String? = null,
    val isHardwareAccelerated: Boolean = false,
    val isSoftwareOnly: Boolean = false
) {
    val displayDecoderType: String
        get() = when {
            isHardwareAccelerated -> "Hardware"
            isSoftwareOnly -> "Software"
            else -> "Unknown"
        }
}
