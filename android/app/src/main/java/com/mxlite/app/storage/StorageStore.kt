package com.mxlite.app.storage

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.dataStore by preferencesDataStore("mx_storage")

class StorageStore(private val context: Context) {

    private val RECENT_FOLDERS = stringSetPreferencesKey("recent_folders")
    private val RECENT_FILES = stringSetPreferencesKey("recent_files")

    suspend fun addFolder(uri: Uri) {
        context.dataStore.edit {
            val set = it[RECENT_FOLDERS]?.toMutableSet() ?: mutableSetOf()
            set.add(uri.toString())
            it[RECENT_FOLDERS] = set
        }
    }

    suspend fun addFile(uri: Uri) {
        context.dataStore.edit {
            val set = it[RECENT_FILES]?.toMutableSet() ?: mutableSetOf()
            set.add(uri.toString())
            it[RECENT_FILES] = set
        }
    }

    suspend fun getFolders(): List<Uri> =
        context.dataStore.data.first()[RECENT_FOLDERS]
            ?.map(Uri::parse) ?: emptyList()

    suspend fun getFiles(): List<Uri> =
        context.dataStore.data.first()[RECENT_FILES]
            ?.map(Uri::parse) ?: emptyList()
}
