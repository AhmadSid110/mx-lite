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

    /**
     * 🔒 CRITICAL:
     * Disable Activity-level back handling.
     *
     * - Prevents Android from calling finish()
     * - Prevents app from minimizing on back gesture
     * - Allows Compose BackHandler to fully control navigation
     *
     * This is REQUIRED when using a single-activity Compose architecture.
     */
    @Deprecated(
        message = "Handled by Compose BackHandler",
        level = DeprecationLevel.HIDDEN
    )
    override fun onBackPressed() {
        // NO-OP — handled in Compose
    }
}