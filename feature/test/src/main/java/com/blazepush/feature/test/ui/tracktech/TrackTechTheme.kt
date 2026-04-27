package com.blazepush.feature.test.ui.tracktech
// @IgnoreFormatCheck

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Track Tech V2 Compose Theme。
 *
 * 与现有 NeonTheme 嵌套使用：MainActivity NeonTheme { Surface { TrackTechAppShell() } }，
 * TrackTechAppShell 内部用 TrackTechTheme { ... } 提供 TrackTech color/typography
 * CompositionLocal。
 */
val LocalTrackTechColors = staticCompositionLocalOf { TrackTechColors }
val LocalTrackTechTypography = staticCompositionLocalOf { TrackTechTypography }
val LocalTrackTechSemantic = staticCompositionLocalOf { TrackTechSemantic }

@Composable
fun TrackTechTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalTrackTechColors provides TrackTechColors,
        LocalTrackTechTypography provides TrackTechTypography,
        LocalTrackTechSemantic provides TrackTechSemantic,
    ) {
        content()
    }
}
