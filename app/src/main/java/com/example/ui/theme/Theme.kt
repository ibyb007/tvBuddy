package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val TvBuddyColorScheme = darkColorScheme(
    primary = AccentCyan,
    onPrimary = Color(0xFF00363D),
    primaryContainer = Color(0xFF004F58),
    onPrimaryContainer = Color(0xFF97F0FF),
    secondary = AccentPurple,
    onSecondary = Color(0xFF2C1060),
    secondaryContainer = Color(0xFF432978),
    onSecondaryContainer = Color(0xFFEADBFF),
    tertiary = AccentIndigo,
    onTertiary = Color.White,
    background = DarkBackground,
    onBackground = TextPrimary,
    surface = DarkSurface,
    onSurface = TextPrimary,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    outline = CardBorder,
    outlineVariant = DarkSurfaceElevated,
    error = StatusLive,
    onError = Color.White
)

@Composable
fun TvBuddyTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = TvBuddyColorScheme,
        typography = Typography,
        content = content
    )
}

// Retain alias for template compatibility
@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    TvBuddyTheme(content = content)
}
