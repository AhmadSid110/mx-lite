package com.mxlite.app.player

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.playbackSpeedDataStore by preferencesDataStore("mx_playback_speed_prefs")

/**
 * Stores playback speed preferences per video using DataStore.
 */
class PlaybackSpeedPrefsStore(private val context: Context) {
    
    /**
     * Load playback speed for a video
     */
    suspend fun loadSpeed(videoId: String): Float {
        val prefs = context.playbackSpeedDataStore.data.first()
        return prefs[floatPreferencesKey("${videoId}_speed")] ?: 1.0f
    }
    
    /**
     * Save playback speed for a video
     */
    suspend fun saveSpeed(videoId: String, speed: Float) {
        context.playbackSpeedDataStore.edit { store ->
            store[floatPreferencesKey("${videoId}_speed")] = speed
        }
    }
}
