package com.mxlite.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.mxlite.app.ui.AppRoot

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            AppRoot()
        }
    }

    // Prevent system back from finishing the activity; Compose handles navigation.
    override fun onBackPressed() {
        // Intentionally empty.
    }
}