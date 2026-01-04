package com.mxlite.app.ui.player

import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File

@Composable
fun PlayerScreen(
    file: File,
    onBack: () -> Unit
) {
    var playing by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {

        TopAppBar(
            title = { Text(file.name) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Text("←")
                }
            }
        )

        AndroidView(
            factory = { ctx -> SurfaceView(ctx) },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.Black)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            Button(onClick = { playing = !playing }) {
                Text(if (playing) "Pause" else "Play")
            }
        }
    }
}
