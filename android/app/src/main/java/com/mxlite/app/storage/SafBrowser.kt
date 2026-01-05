package com.mxlite.app.storage

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.util.Locale

fun listSafFolder(
    context: Context,
    treeUri: Uri
): List<DocumentFile> {
    val root = DocumentFile.fromTreeUri(context, treeUri)
        ?: return emptyList()

    return root.listFiles().sortedWith(
        compareBy<DocumentFile>(
            { !it.isDirectory },                       // folders first
            { it.name?.lowercase(Locale.ROOT) ?: "" }  // null-safe, stable
        )
    )
}
