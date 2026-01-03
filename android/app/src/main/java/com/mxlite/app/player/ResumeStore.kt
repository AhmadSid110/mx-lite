
package com.mxlite.app.player

import android.content.Context
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.security.MessageDigest

private val Context.dataStore by preferencesDataStore("resume_store")

object ResumeStore {

    private fun keyFor(path: String): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(path.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    fun save(context: Context, path: String, positionMs: Long) {
        val key = longPreferencesKey(keyFor(path))
        runBlocking {
            context.dataStore.edit {
                it[key] = positionMs
            }
        }
    }

    fun load(context: Context, path: String): Long {
        val key = longPreferencesKey(keyFor(path))
        return runBlocking {
            context.dataStore.data.first()[key] ?: 0L
        }
    }
}
