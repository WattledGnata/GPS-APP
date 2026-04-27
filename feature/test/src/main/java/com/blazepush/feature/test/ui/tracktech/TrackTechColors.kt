package com.blazepush.feature.test.ui.tracktech
// @IgnoreFormatCheck

import androidx.compose.ui.graphics.Color

/**
 * Track Tech V2 色号 token。
 *
 * 完整复用 docs/design/track-tech-v2-cc-guidance.md §Color Guidance 的 12 个 hex 值。
 *
 * 比例约束：黑/石墨 70% · 文字灰白 15% · 紫色 8% · cyan 4% · green/red 3%
 */
object TrackTechColors {
    // 基础底色
    val Background = Color(0xFF07080D)
    val Surface = Color(0xFF11131C)
    val SurfaceDark = Color(0xFF0B0D13)
    val Border = Color(0xFF303442)

    // 强调色（克制使用）
    val Purple = Color(0xFF9B5CFF)
    val DeepPurple = Color(0xFF5B2AA8)
    val Cyan = Color(0xFF67E8F9)
    val Green = Color(0xFF76D05E)
    val Red = Color(0xFFF25F5C)

    // 文字
    val TextPrimary = Color(0xFFECECF2)
    val TextSecondary = Color(0xFFA5A6B1)
    val TextMuted = Color(0xFF70727E)

    // 派生（透明叠加）
    val PurpleAlpha20 = Purple.copy(alpha = 0.20f)
    val PurpleAlpha40 = Purple.copy(alpha = 0.40f)
    val CyanAlpha60 = Cyan.copy(alpha = 0.60f)
    val BorderAlpha60 = Border.copy(alpha = 0.60f)
}

/**
 * 按语义绑定颜色，解耦"使用方"与"色号"。未来调色只改本对象一层。
 */
object TrackTechSemantic {
    val ReadyAccent = TrackTechColors.Green
    val ConnectingAccent = TrackTechColors.Cyan
    val NotReadyAccent = TrackTechColors.Red
    val SelectedAccent = TrackTechColors.Purple
    val PrimaryActionAccent = TrackTechColors.Purple
    val SecondaryActionAccent = TrackTechColors.Red
    val TelemetryLine = TrackTechColors.Cyan
    val MetricLabel = TrackTechColors.Cyan
}
