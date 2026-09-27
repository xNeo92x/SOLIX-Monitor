package de.solixmonitor.android.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

val SolixGreen = Color(0xFF006C4C)
val SolixGreenLight = Color(0xFF70DBB0)
val SolarAmber = Color(0xFFF5A900)
val GridBlue = Color(0xFF3578E5)
val BatteryViolet = Color(0xFF7C5CFF)

private val LightColors = lightColorScheme(
    primary = SolixGreen,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF8CF8CB),
    onPrimaryContainer = Color(0xFF002116),
    secondary = Color(0xFF4C6358),
    background = Color(0xFFF7FAF7),
    surface = Color(0xFFF7FAF7),
    surfaceVariant = Color(0xFFE8EEE9),
    onSurface = Color(0xFF191C1A),
    onSurfaceVariant = Color(0xFF404943),
    outline = Color(0xFF707973),
    error = Color(0xFFBA1A1A),
)

private val DarkColors = darkColorScheme(
    primary = SolixGreenLight,
    onPrimary = Color(0xFF003828),
    primaryContainer = Color(0xFF00513A),
    onPrimaryContainer = Color(0xFF8CF8CB),
    secondary = Color(0xFFB3CCBF),
    background = Color(0xFF101412),
    surface = Color(0xFF101412),
    surfaceVariant = Color(0xFF232925),
    onSurface = Color(0xFFE1E3DF),
    onSurfaceVariant = Color(0xFFC0C9C3),
    outline = Color(0xFF8A938D),
    error = Color(0xFFFFB4AB),
)

@Composable
fun SolixTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (dark) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colors.background.toArgb()
            window.navigationBarColor = colors.background.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(colorScheme = colors, content = content)
}
