package com.nitin.filmcam.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Palette: warm off-white surfaces, dark ink text, terracotta accent.
val Cream = Color(0xFFF4EFE6)
val Paper = Color(0xFFFBF8F2)
val Sand = Color(0xFFE8E0D2)
val Ink = Color(0xFF2B2621)
val InkSoft = Color(0xFF6B625A)
val Terracotta = Color(0xFFC96442)
val RecordRed = Color(0xFFB8432F)

private val FilmLightColors = lightColorScheme(
    primary = Terracotta,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF3D9CE),
    onPrimaryContainer = Ink,
    secondary = InkSoft,
    onSecondary = Color.White,
    secondaryContainer = Sand,
    onSecondaryContainer = Ink,
    background = Cream,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = Sand,
    onSurfaceVariant = InkSoft,
    surfaceContainer = Paper,
    surfaceContainerHigh = Paper,
    surfaceContainerHighest = Sand,
    surfaceContainerLow = Cream,
    outline = Color(0xFFBFB4A6),
)

/**
 * The app theme. Deliberately **light only**: we never look at isSystemInDarkTheme(),
 * so the UI stays light even when the phone is in dark mode.
 */
@Composable
fun FilmCamTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = FilmLightColors,
        typography = Typography(),
        content = content,
    )
}
