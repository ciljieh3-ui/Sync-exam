package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val PrismColorScheme = darkColorScheme(
    primary = PrismMint,
    onPrimary = PrismNavy,
    primaryContainer = PrismSurfaceElevated,
    onPrimaryContainer = PrismMint,
    secondary = PrismViolet,
    onSecondary = PrismNavy,
    secondaryContainer = PrismSurfaceVariant,
    onSecondaryContainer = PrismViolet,
    tertiary = PrismWarn,
    background = PrismNavy,
    onBackground = PrismText,
    surface = PrismSurface,
    onSurface = PrismText,
    surfaceVariant = PrismSurfaceVariant,
    onSurfaceVariant = PrismTextMuted,
    outline = PrismBorder,
    error = PrismDanger,
    onError = Color.White,
)

@Composable
fun PrismTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PrismColorScheme,
        typography = Typography,
        content = content,
    )
}
