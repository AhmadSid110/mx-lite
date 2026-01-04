
package com.mxlite.app.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable

@Composable
fun CodecDialog(
    onInstall: () -> Unit,
    onUseSW: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Codec not supported") },
        text = {
            Text(
                "This video requires additional codecs. " +
                "You can install the codec pack or switch to software decoding."
            )
        },
        confirmButton = {
            Button(onClick = onInstall) {
                Text("Install Codec Pack")
            }
        },
        dismissButton = {
            Button(onClick = onUseSW) {
                Text("Use Software Decoder")
            }
        }
    )
}
