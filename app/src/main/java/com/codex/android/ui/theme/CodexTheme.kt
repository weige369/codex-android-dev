package com.codex.android.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
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

/**
 * Generate a full color scheme variant from a custom primary color.
 * Creates harmonious container/onContainer variants using alpha overlays.
 */
private fun ColorScheme.withCustomPrimary(customPrimary: Color): ColorScheme {
    return copy(
        primary = customPrimary,
        onPrimary = Color.White,
        primaryContainer = customPrimary.copy(alpha = 0.15f),
        onPrimaryContainer = customPrimary,
        surfaceTint = customPrimary,
    )
}

/**
 * Generate a full color scheme variant from a custom secondary color.
 */
private fun ColorScheme.withCustomSecondary(customSecondary: Color): ColorScheme {
    return copy(
        secondary = customSecondary,
        onSecondary = Color.White,
        secondaryContainer = customSecondary.copy(alpha = 0.15f),
        onSecondaryContainer = customSecondary,
    )
}

/**
 * Codex Theme — Upgraded with Dynamic Color + Custom Color support.
 *
 * @param darkTheme Whether to use dark theme. Defaults to system setting.
 * @param dynamicColor Whether to use Material You dynamic colors (Android 12+). Defaults to true.
 * @param customPrimary Optional custom primary color to override the default brand orange.
 * @param customSecondary Optional custom secondary color to override the default teal.
 */
@Composable
fun CodexTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    customPrimary: Color? = null,
    customSecondary: Color? = null,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current

    // Build base color scheme: Dynamic Color (Android 12+) or brand colors
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val activity = context as? Activity
            if (darkTheme) {
                dynamicDarkColorScheme(activity ?: context)
            } else {
                dynamicLightColorScheme(activity ?: context)
            }
        }
        darkTheme -> CodexDarkColorScheme
        else -> CodexLightColorScheme
    }.let { scheme ->
        // Apply custom color overrides on top of whatever base we chose
        var result = scheme
        if (customPrimary != null) {
            result = result.withCustomPrimary(customPrimary)
        }
        if (customSecondary != null) {
            result = result.withCustomSecondary(customSecondary)
        }
        result
    }

    // Edge-to-edge + transparent status bar with correct icon contrast
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // Transparent status & navigation bar for edge-to-edge
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            WindowCompat.setDecorFitsSystemWindows(window, false)
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
