package com.codex.android.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val CodexDarkColorScheme = darkColorScheme(
    primary = CodexPrimary,
    onPrimary = Color.White,
    primaryContainer = CodexPrimary.copy(alpha = 0.15f),
    onPrimaryContainer = CodexPrimaryLight,
    secondary = CodexSecondary,
    onSecondary = Color.Black,
    secondaryContainer = CodexSecondary.copy(alpha = 0.15f),
    onSecondaryContainer = CodexSecondary,
    tertiary = ReplitBlue,
    onTertiary = Color.Black,
    background = CodexBackground,
    onBackground = CodexOnSurface,
    surface = CodexSurface,
    onSurface = CodexOnSurface,
    surfaceVariant = CodexSurfaceVariant,
    onSurfaceVariant = CodexOnSurfaceVariant,
    outline = CodexOutline,
    error = CodexError,
    onError = Color.White,
    errorContainer = CodexError.copy(alpha = 0.15f),
    onErrorContainer = CodexError,
    inverseSurface = CodexOnSurface,
    inverseOnSurface = CodexSurface,
    surfaceTint = CodexPrimary,
)

private val CodexLightColorScheme = lightColorScheme(
    primary = CodexPrimary,
    onPrimary = Color.White,
    primaryContainer = CodexPrimaryLight.copy(alpha = 0.3f),
    onPrimaryContainer = CodexPrimaryDark,
    secondary = CodexSecondary,
    onSecondary = Color.White,
    background = Color(0xFFF8F8FC),
    onBackground = Color(0xFF1A1A2E),
    surface = Color.White,
    onSurface = Color(0xFF1A1A2E),
    surfaceVariant = Color(0xFFF0F0F5),
    onSurfaceVariant = Color(0xFF5A5A72),
    outline = Color(0xFFD0D0DC),
    error = CodexError,
    onError = Color.White,
)

@Composable
fun CodexTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) CodexDarkColorScheme else CodexLightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.surface.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = CodexTypography,
        shapes = CodexShapes,
        content = content
    )
}
