package com.mxlite.app.storage

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

fun listSafFolder(
    context: Context,
    treeUri: Uri
): List<DocumentFile> {
    val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()

    return root.listFiles().sortedWith(
        compareBy<DocumentFile> { !it.isDirectory }
            .thenBy { it.name?.lowercase() }
    )
}
