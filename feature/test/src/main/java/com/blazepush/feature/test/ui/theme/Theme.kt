package com.blazepush.feature.test.ui.theme

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

/**
 * 霓虹深色主题
 * iOS 风格的 Material3 主题
 */
private val DarkColorScheme = darkColorScheme(
    primary = NeonColors.Primary,
    onPrimary = Color(0xFF0A0A0E),
    primaryContainer = NeonColors.PrimaryVariant,
    onPrimaryContainer = NeonColors.Primary,

    secondary = NeonColors.Secondary,
    onSecondary = Color(0xFF0A0A0E),
    secondaryContainer = NeonColors.SecondaryVariant,
    onSecondaryContainer = NeonColors.Secondary,

    tertiary = NeonColors.Accent,
    onTertiary = Color(0xFF0A0A0E),
    tertiaryContainer = NeonColors.AccentVariant,
    onTertiaryContainer = NeonColors.Accent,

    background = NeonColors.Background,
    onBackground = NeonColors.OnBackground,

    surface = NeonColors.Surface,
    onSurface = NeonColors.OnSurface,
    surfaceVariant = NeonColors.SurfaceVariant,
    onSurfaceVariant = NeonColors.OnSurfaceVariant,

    error = NeonColors.Error,
    onError = Color.White,

    outline = NeonColors.Divider,
    outlineVariant = NeonColors.TextFieldBorder
)

/**
 * 霓虹主题 Composable
 */
@Composable
fun NeonTheme(
    darkTheme: Boolean = true, // 默认深色主题
    dynamicColor: Boolean = false, // 禁用动态颜色以保持霓虹风格
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        darkTheme -> DarkColorScheme
        else -> DarkColorScheme // 始终使用深色主题
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.surface.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = NeonTypography,
        content = content
    )
}
