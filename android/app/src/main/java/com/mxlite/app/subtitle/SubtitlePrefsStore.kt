package com.mxlite.app.subtitle

import android.content.Context
import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.io.File
import java.security.MessageDigest

private val Context.subtitlePrefsDataStore by preferencesDataStore("mx_subtitle_prefs")

/**
 * Stores subtitle preferences per video using DataStore.
 * Preferences are keyed by a hash of the video file path or SAF URI.
 */
class SubtitlePrefsStore(private val context: Context) {

    /**
     * Subtitle preferences for a specific video
     */
    data class SubtitlePrefs(
        val enabled: Boolean = true,
        val offsetMs: Long = 0L,
        val fontSizeSp: Float = 18f,
        val textColor: Color = Color.White,
        val bgOpacity: Float = 0.6f,
        val selectedTrackId: String? = null,
        val bottomMarginDp: Float = 48f
    )

    /**
     * Load preferences for a video
     */
    suspend fun load(videoId: String): SubtitlePrefs {
        val prefs = context.subtitlePrefsDataStore.data.first()
        
        return SubtitlePrefs(
            enabled = prefs[booleanPreferencesKey("${videoId}_enabled")] ?: true,
            offsetMs = prefs[longPreferencesKey("${videoId}_offsetMs")] ?: 0L,
            fontSizeSp = prefs[floatPreferencesKey("${videoId}_fontSizeSp")] ?: 18f,
            textColor = prefs[intPreferencesKey("${videoId}_textColor")]?.let { Color(it) } ?: Color.White,
            bgOpacity = prefs[floatPreferencesKey("${videoId}_bgOpacity")] ?: 0.6f,
            selectedTrackId = prefs[stringPreferencesKey("${videoId}_selectedTrackId")],
            bottomMarginDp = prefs[floatPreferencesKey("${videoId}_bottomMarginDp")] ?: 48f
        )
    }

    /**
     * Save preferences for a video
     */
    suspend fun save(videoId: String, prefs: SubtitlePrefs) {
        context.subtitlePrefsDataStore.edit { store ->
            store[booleanPreferencesKey("${videoId}_enabled")] = prefs.enabled
            store[longPreferencesKey("${videoId}_offsetMs")] = prefs.offsetMs
            store[floatPreferencesKey("${videoId}_fontSizeSp")] = prefs.fontSizeSp
            store[intPreferencesKey("${videoId}_textColor")] = prefs.textColor.toArgb()
            store[floatPreferencesKey("${videoId}_bgOpacity")] = prefs.bgOpacity
            prefs.selectedTrackId?.let {
                store[stringPreferencesKey("${videoId}_selectedTrackId")] = it
            }
            store[floatPreferencesKey("${videoId}_bottomMarginDp")] = prefs.bottomMarginDp
        }
    }

    companion object {
        /**
         * Generate a unique video ID from a file path
         */
        fun videoIdFromFile(file: File): String {
            return hashString(file.absolutePath)
        }

        /**
         * Generate a unique video ID from a SAF URI
         */
        fun videoIdFromUri(uri: Uri): String {
            return hashString(uri.toString())
        }

        private fun hashString(input: String): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
            return bytes.joinToString("") { "%02x".format(it) }.take(16)
        }
    }
}
