package com.mxlite.app.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Force Dark Scheme
private val DarkColorScheme = darkColorScheme(
    primary = CinemaAccent,
    secondary = CinemaSecondary,
    background = CinemaBackground,
    surface = CinemaSurface,
    surfaceVariant = CinemaSurfaceVariant,
    error = CinemaError,
    onPrimary = CinemaBackground,
    onSecondary = CinemaBackground,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    onSurfaceVariant = TextSecondary
)

@Composable
fun MxLiteTheme(
    content: @Composable () -> Unit
) {
    // We strictly use Dark Mode (Cinema Look)
    val colorScheme = DarkColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // Status bar color matches background for seamless look
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            
            // Force light icons on status bar (since background is dark)
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        // typography = Typography, // Uncomment if you have custom type
        content = content
    )
}
