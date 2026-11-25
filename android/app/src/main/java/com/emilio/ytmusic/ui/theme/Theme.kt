package com.emilio.ytmusic.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = AppleRose,
    secondary = AppleCyan,
    tertiary = ApplePurple,
    background = DarkBackground,
    surface = SurfaceCard,
    onPrimary = TextPrimary,
    onSecondary = OLEDBlack,
    onBackground = TextPrimary,
    onSurface = TextPrimary
)

@Composable
fun YTMusicTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = OLEDBlack.toArgb()
            window.navigationBarColor = OLEDBlack.toArgb()
            val insets = WindowCompat.getInsetsController(window, view)
            insets.isAppearanceLightStatusBars = false
            insets.isAppearanceLightNavigationBars = false
        }
    }

    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
