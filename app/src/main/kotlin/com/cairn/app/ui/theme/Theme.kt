package com.cairn.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = CairnBlue,
    onPrimary = Color.White,
    primaryContainer = CairnBlue.copy(alpha = 0.12f),
    onPrimaryContainer = CairnBlueDark,
    secondary = CairnPink,
    onSecondary = CairnText,
    secondaryContainer = CairnPink.copy(alpha = 0.3f),
    onSecondaryContainer = CairnPinkDark,
    tertiary = CairnBlueDark,
    background = CairnWhite,
    onBackground = CairnText,
    surface = CairnSurface,
    onSurface = CairnText,
    surfaceVariant = CairnSurface,
    onSurfaceVariant = CairnTextMuted,
    outline = CairnTextMuted.copy(alpha = 0.5f),
    error = CairnDanger,
)

private val DarkColorScheme = darkColorScheme(
    primary = CairnBlueDarkMode,
    onPrimary = Color.White,
    primaryContainer = CairnBlueDarkMode.copy(alpha = 0.2f),
    onPrimaryContainer = CairnBlue,
    secondary = CairnPinkDarkMode,
    onSecondary = Color.White,
    secondaryContainer = CairnPinkDarkMode.copy(alpha = 0.2f),
    onSecondaryContainer = CairnPink,
    tertiary = CairnBlue,
    background = CairnBgDark,
    onBackground = CairnTextDark,
    surface = CairnSurfaceDark,
    onSurface = CairnTextDark,
    surfaceVariant = CairnSurfaceDark,
    onSurfaceVariant = CairnTextMuted,
    outline = CairnTextMuted.copy(alpha = 0.3f),
    error = CairnDanger,
)

@Composable
fun CairnTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(), // 使用默认 Material 3 字体
        content = content
    )
}
