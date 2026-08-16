package com.dimroom.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.dimroom.data.repository.ThemeMode

/**
 * A deliberately neutral palette: a photo editor's chrome must not tint the viewer's perception of
 * the image, so surfaces stay grey and the single accent is reserved for controls.
 */
private val Accent = Color(0xFFE8813A)
private val AccentDark = Color(0xFFF3A063)

private val DarkColors = darkColorScheme(
    primary = AccentDark,
    onPrimary = Color(0xFF20140A),
    primaryContainer = Color(0xFF5A3315),
    onPrimaryContainer = Color(0xFFFFDCC4),
    secondary = Color(0xFFB8B2AC),
    background = Color(0xFF101010),
    onBackground = Color(0xFFE6E2DE),
    surface = Color(0xFF141414),
    onSurface = Color(0xFFE6E2DE),
    surfaceVariant = Color(0xFF262626),
    onSurfaceVariant = Color(0xFFC4C0BC),
    outline = Color(0xFF3A3A3A),
)

private val LightColors = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDCC4),
    onPrimaryContainer = Color(0xFF331200),
    secondary = Color(0xFF6B625B),
    background = Color(0xFFFBF9F7),
    onBackground = Color(0xFF1C1B1A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1C1B1A),
    surfaceVariant = Color(0xFFEDE8E4),
    onSurfaceVariant = Color(0xFF4C4642),
    outline = Color(0xFFCFC8C2),
)

@Composable
fun DimroomTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = Typography(),
        content = content,
    )
}
