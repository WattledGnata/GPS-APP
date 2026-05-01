package com.blazepush.feature.test.ui.tracktech
// @IgnoreFormatCheck

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.blazepush.feature.test.R

/**
 * Track Tech V2 字体角色。
 */
private val Dseg7FontFamily = FontFamily(
    Font(R.font.dseg7_classic_bold, FontWeight.Normal)
)

object TrackTechTypography {
    // RacingTitle —— 页面标题 / 主操作标题
    val RacingTitleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.ExtraBold,
        fontStyle = FontStyle.Italic,
        fontSize = 28.sp,
        letterSpacing = 0.05.em,
    )
    val RacingTitleMedium = RacingTitleLarge.copy(fontSize = 20.sp)
    val RacingTitleSmall = RacingTitleLarge.copy(fontSize = 16.sp)

    // Mechanical —— 仪表瞬时数字读数（DSEG7 七段，仅用于 SPEED / SATS / Hz / HDOP / FRESH / DROPPED 等纯数字）
    val MechanicalHero = TextStyle(
        fontFamily = Dseg7FontFamily,
        fontWeight = FontWeight.Normal,
        fontStyle = FontStyle.Normal,
        fontSize = 96.sp,
        letterSpacing = 0.em,
    )
    val MechanicalLarge = MechanicalHero.copy(fontSize = 60.sp)
    val MechanicalMedium = MechanicalHero.copy(fontSize = 36.sp)
    val MechanicalSmall = MechanicalHero.copy(fontSize = 20.sp)

    // Score —— 成绩 / 时间 / 计数 / 文字状态（SansSerif Italic Bold，赛车风）
    val ScoreHero = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.ExtraBold,
        fontStyle = FontStyle.Italic,
        fontSize = 96.sp,
        letterSpacing = 0.02.em,
    )
    val ScoreLarge = ScoreHero.copy(fontSize = 60.sp)
    val ScoreMedium = ScoreHero.copy(fontSize = 36.sp)
    val ScoreSmall = ScoreHero.copy(fontSize = 20.sp)

    // Deprecated alias —— 默认走 Score（与 MetricKind 默认值一致）；保留兼容旧 baseline 直接引用
    @Deprecated("Use ScoreHero or MechanicalHero explicitly", ReplaceWith("ScoreHero"))
    val MetricHero = ScoreHero

    @Deprecated("Use ScoreMedium or MechanicalMedium explicitly", ReplaceWith("ScoreMedium"))
    val MetricMedium = ScoreMedium

    @Deprecated("Use ScoreSmall or MechanicalSmall explicitly", ReplaceWith("ScoreSmall"))
    val MetricSmall = ScoreSmall

    // UiText —— 副文 / 列表 / 状态
    val UiTextBody = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
    )
    val UiTextSmall = UiTextBody.copy(fontSize = 12.sp)
    // section label "BLE" "SATS" "RATE" "PERFORMANCE TEST" 等大写文案
    val UiTextLabel = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        letterSpacing = 0.10.em,
    )
}
