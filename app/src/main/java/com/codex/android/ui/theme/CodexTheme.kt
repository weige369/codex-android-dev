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
import com.codex.android.core.designsystem.*

/**
 * Codex Android v2.0 Theme — dark-only professional IDE aesthetic.
 * All color tokens reference core/designsystem/Color.kt.
 */

private val CodexColorScheme = darkColorScheme(
    primary = CxPrimary,
    onPrimary = CxBackground,
    primaryContainer = CxPrimary.copy(alpha = 0.15f),
    onPrimaryContainer = CxPrimary,
    secondary = CxBlue,
    onSecondary = CxWhite,
    secondaryContainer = CxBlue.copy(alpha = 0.15f),
    onSecondaryContainer = CxBlue,
    tertiary = CxWarning,
    onTertiary = CxBackground,
    background = CxBackground,
    onBackground = CxTextPrimary,
    surface = CxSurface,
    onSurface = CxTextPrimary,
    surfaceVariant = CxSurfaceVariant,
    onSurfaceVariant = CxTextSecondary,
    outline = CxBorder,
    outlineVariant = CxBorder.copy(alpha = 0.5f),
    error = CxError,
    onError = CxWhite,
    errorContainer = CxError.copy(alpha = 0.15f),
    onErrorContainer = CxError,
    inverseSurface = CxTextPrimary,
    inverseOnSurface = CxSurface,
    surfaceTint = CxPrimary,
)

@Composable
fun CodexTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = CodexColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = CxBackground.toArgb()
            window.navigationBarColor = CxSurface.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = CxTypography,
        content = content
    )
}
