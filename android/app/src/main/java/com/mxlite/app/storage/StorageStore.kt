package com.mxlite.app.storage

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "mx_storage"
)

class StorageStore(private val context: Context) {

    private val RECENT_FOLDERS = stringSetPreferencesKey("recent_folders")
    private val RECENT_FILES = stringSetPreferencesKey("recent_files")

    suspend fun addFolder(uri: Uri) {
        context.dataStore.edit { prefs ->
            val set = prefs[RECENT_FOLDERS]?.toMutableSet() ?: mutableSetOf()
            set.add(uri.toString())
            prefs[RECENT_FOLDERS] = set
        }
    }

    suspend fun addFile(uri: Uri) {
        context.dataStore.edit { prefs ->
            val set = prefs[RECENT_FILES]?.toMutableSet() ?: mutableSetOf()
            set.add(uri.toString())
            prefs[RECENT_FILES] = set
        }
    }

    suspend fun getFolders(): List<Uri> =
        context.dataStore.data.first()[RECENT_FOLDERS]
            ?.map(Uri::parse) ?: emptyList()

    suspend fun getFiles(): List<Uri> =
        context.dataStore.data.first()[RECENT_FILES]
            ?.map(Uri::parse) ?: emptyList()
}
