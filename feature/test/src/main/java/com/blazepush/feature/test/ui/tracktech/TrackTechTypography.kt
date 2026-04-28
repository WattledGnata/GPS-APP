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

    // Metric —— 速度/时间/卫星数/频率（DSEG7 七段数码字体）
    val MetricHero = TextStyle(
        fontFamily = Dseg7FontFamily,
        fontWeight = FontWeight.Normal,
        fontStyle = FontStyle.Normal,
        fontSize = 96.sp,
        letterSpacing = 0.em,
    )
    val MetricMedium = MetricHero.copy(fontSize = 36.sp)
    val MetricSmall = MetricHero.copy(fontSize = 20.sp)

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
