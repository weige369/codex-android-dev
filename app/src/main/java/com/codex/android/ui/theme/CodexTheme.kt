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
    primary = CodexBrandOrange,
    onPrimary = Color.White,
    primaryContainer = CodexBrandOrange.copy(alpha = 0.15f),
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
    surfaceTint = CodexBrandOrange,
)

private val CodexLightColorScheme = lightColorScheme(
    primary = CodexBrandOrange,
    onPrimary = Color.White,
    primaryContainer = CodexBrandOrange.copy(alpha = 0.15f),
    onPrimaryContainer = CodexPrimaryDark,
    secondary = CodexSecondary,
    onSecondary = Color.White,
    background = CodexCanvas,
    onBackground = CodexInk,
    surface = Color.White,
    onSurface = CodexInk,
    surfaceVariant = CodexHairline,
    onSurfaceVariant = Color(0xFF5A5650),
    outline = CodexHairline,
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
