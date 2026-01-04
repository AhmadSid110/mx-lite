
package com.mxlite.app.storage

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.flow.first

private val Context.dataStore by preferencesDataStore("mx_storage")

class StorageStore(private val context: Context) {

    private val RECENT_FOLDERS = stringSetPreferencesKey("recent_folders")

    /**
     * Store SAF tree URI ONLY
     */
    suspend fun addFolder(treeUri: Uri) {
        val normalized = normalizeTreeUri(treeUri) ?: return

        context.dataStore.edit { prefs ->
            val set = prefs[RECENT_FOLDERS]?.toMutableSet() ?: mutableSetOf()
            set.add(normalized.toString())
            prefs[RECENT_FOLDERS] = set
        }
    }

    /**
     * Return only folders that:
     * - Still exist
     * - Still have permission
     */
    suspend fun getFolders(): List<Uri> {
        val stored = context.dataStore.data.first()[RECENT_FOLDERS] ?: emptySet()

        return stored.mapNotNull { uriString ->
            val uri = Uri.parse(uriString)
            if (hasPermission(uri) && DocumentFile.fromTreeUri(context, uri) != null) {
                uri
            } else null
        }
    }

    /**
     * Cleanup dead permissions on app start
     */
    suspend fun cleanup() {
        context.dataStore.edit { prefs ->
            val valid = prefs[RECENT_FOLDERS]
                ?.filter { hasPermission(Uri.parse(it)) }
                ?.toSet()
                ?: emptySet()

            prefs[RECENT_FOLDERS] = valid
        }
    }

    private fun hasPermission(uri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission
        }

    private fun normalizeTreeUri(uri: Uri): Uri? =
        if (uri.path?.contains("/tree/") == true) uri else null
}
