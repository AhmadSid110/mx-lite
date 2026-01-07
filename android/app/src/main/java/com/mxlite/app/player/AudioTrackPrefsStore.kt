package com.mxlite.app.player

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.io.File
import java.security.MessageDigest

private val Context.audioTrackPrefsDataStore by preferencesDataStore("mx_audio_track_prefs")

/**
 * Stores audio track preferences per video using DataStore.
 * Tracks which audio track index was selected for each video.
 */
class AudioTrackPrefsStore(private val context: Context) {

    /**
     * Load the saved audio track index for a video, or null if not set
     */
    suspend fun loadTrackIndex(videoId: String): Int? {
        val prefs = context.audioTrackPrefsDataStore.data.first()
        return prefs[intPreferencesKey("${videoId}_audioTrackIndex")]
    }

    /**
     * Save the selected audio track index for a video
     */
    suspend fun saveTrackIndex(videoId: String, trackIndex: Int) {
        context.audioTrackPrefsDataStore.edit { store ->
            store[intPreferencesKey("${videoId}_audioTrackIndex")] = trackIndex
        }
    }

    /**
     * Clear the saved audio track for a video (use default)
     */
    suspend fun clearTrackIndex(videoId: String) {
        context.audioTrackPrefsDataStore.edit { store ->
            store.remove(intPreferencesKey("${videoId}_audioTrackIndex"))
        }
    }

    companion object {
        /**
         * Generate a unique video ID from a file path
         * (Same algorithm as SubtitlePrefsStore for consistency)
         */
        fun videoIdFromFile(file: File): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(file.absolutePath.toByteArray())
            return bytes.joinToString("") { "%02x".format(it) }.take(16)
        }
    }
}
