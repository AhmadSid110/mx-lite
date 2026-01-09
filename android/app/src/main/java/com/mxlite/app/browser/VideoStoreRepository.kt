package com.mxlite.app.browser

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

const val DEFAULT_FOLDER_NAME = "Videos"

data class VideoItem(
    val contentUri: Uri,
    val name: String,
    val folder: String
)

object VideoStoreRepository {

    fun load(context: Context): List<VideoItem> {
        val items = mutableListOf<VideoItem>()

        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.RELATIVE_PATH
        )

        try {
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                MediaStore.Video.Media.DATE_ADDED + " DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol)
                    val folder = cursor.getString(pathCol)?.trimEnd('/') ?: DEFAULT_FOLDER_NAME

                    items.add(
                        VideoItem(
                            contentUri = ContentUris.withAppendedId(
                                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                                id
                            ),
                            name = name,
                            folder = folder
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // Query failed - return empty list
            // Possible causes: permission denied, storage unavailable, etc.
            e.printStackTrace()
        }
        
        return items
    }
}
