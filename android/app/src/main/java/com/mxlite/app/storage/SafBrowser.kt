package com.mxlite.app.storage

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

class SafBrowser(
    private val context: Context
) {
    fun listFolder(treeUri: Uri): List<DocumentFile> {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        return listChildren(root)
    }

    fun listChildren(dir: DocumentFile): List<DocumentFile> {
        return dir.listFiles()
            .sortedWith(
                compareBy<DocumentFile> { !it.isDirectory }
                    .thenBy { it.name?.lowercase() }
            )
    }
}
