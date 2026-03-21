package com.race.gps.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 霓虹主题颜色定义
 * iOS 风格深色霓虹配色
 */
object NeonColors {
    // 主色调 - 霓虹蓝
    val Primary = Color(0xFF00F0FF)
    val PrimaryVariant = Color(0xFF00C0CF)

    // 次要色 - 霓虹橙
    val Secondary = Color(0xFFFFA000)
    val SecondaryVariant = Color(0xFFFF8000)

    // 强调色 - 霓虹���
    val Accent = Color(0xFF00FF78)
    val AccentVariant = Color(0xFF00CF60)

    // 紫色 - 用于特殊状态
    val Purple = Color(0xFFA855F7)
    val PurpleVariant = Color(0xFF8835D7)

    // 背景色
    val Background = Color(0xFF0A0A0E)
    val Surface = Color(0xFF12121A)
    val SurfaceVariant = Color(0xFF1A1A24)

    // 卡片背景
    val CardBackground = Color(0xFF1E1E2A)

    // 文字颜色
    val OnBackground = Color(0xFFE0E0E8)
    val OnSurface = Color(0xFFD0D0D8)
    val OnSurfaceVariant = Color(0xFF808088)

    // 状态颜色
    val Success = Accent
    val Warning = Secondary
    val Error = Color(0xFFFF3B30)
    val Info = Primary

    // 分割线
    val Divider = Color(0xFF2A2A36)

    // 半透明遮罩
    val Overlay = Color(0xCC000000)
    val OverlayLight = Color(0x66000000)

    // 输入框
    val TextFieldBackground = Color(0xFF1A1A24)
    val TextFieldBorder = Color(0xFF3A3A4A)
    val TextFieldBorderFocused = Primary

    // 按钮状态
    val ButtonBackground = Primary
    val ButtonBackgroundDisabled = Color(0xFF3A3A4A)
    val ButtonOnBackground = Color(0xFF0A0A0E)
    val ButtonOnBackgroundDisabled = Color(0xFF606068)
}

/**
 * 渐变色定义
 */
object NeonGradients {
    val PrimaryGradient = listOf(
        Color(0xFF00F0FF),
        Color(0xFF0080FF)
    )

    val AccentGradient = listOf(
        Color(0xFF00FF78),
        Color(0xFF00F0FF)
    )

    val PurpleGradient = listOf(
        Color(0xFFA855F7),
        Color(0xFF00F0FF)
    )

    val OrangeGradient = listOf(
        Color(0xFFFFA000),
        Color(0xFFFF3B30)
    )
}
