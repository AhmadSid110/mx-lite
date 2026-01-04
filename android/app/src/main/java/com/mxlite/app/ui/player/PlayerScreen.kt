package com.mxlite.app.ui.player

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import com.mxlite.app.player.VideoDecoder
import java.io.File

@Composable
fun PlayerScreen(
    file: File,
    onBack: () -> Unit
) {
    var decoder by remember { mutableStateOf<VideoDecoder?>(null) }
    var playing by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {

        TopAppBar(
            title = { Text(file.name) },
            navigationIcon = {
                IconButton(onClick = {
                    decoder?.shutdown()
                    decoder = null
                    onBack()
                }) {
                    Text("←")
                }
            }
        )

        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            if (playing) {
                                decoder = VideoDecoder(file, holder.surface).also { it.start() }
                            }
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            decoder?.shutdown()
                            decoder = null
                        }

                        override fun surfaceChanged(
                            holder: SurfaceHolder,
                            format: Int,
                            width: Int,
                            height: Int
                        ) {}
                    })
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            Button(onClick = {
                if (!playing) {
                    playing = true
                } else {
                    decoder?.shutdown()
                    decoder = null
                    playing = false
                }
            }) {
                Text(if (playing) "Stop" else "Play")
            }
        }
    }
}
