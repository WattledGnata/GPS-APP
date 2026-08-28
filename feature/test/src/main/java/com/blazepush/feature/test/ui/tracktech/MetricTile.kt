package com.blazepush.feature.test.ui.tracktech
// @IgnoreFormatCheck

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 紧凑指标小卡：上标 label + 中央 MetricNumber + 底部 status 文字。
 *
 * 用于 Device Home 的 Quick Status Row（BLE / SATS / RATE 三卡）+ Test Home 的 Latest Result。
 */
@Composable
fun MetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    status: String? = null,
    accentColor: Color = TrackTechColors.Cyan,
    valueSize: MetricSize = MetricSize.Medium,
    valueKind: MetricKind = MetricKind.Score,
    // round add-realtime-lap-delta：DELTA tile 需要数字本身按正/负染色（绿/红）。
    // 默认 null 表示沿用 baseline 的 TextPrimary（白色），现有调用方零改动。
    valueColor: Color? = null,
    // 遥测数字优先完整显示；仅调用方显式设置时限制数字字号的系统放大上限。
    maxValueFontScale: Float? = null,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
) {
    CutCornerPanel(
        modifier = modifier.fillMaxWidth(),
        cutSize = 8.dp,
        cutCorners = cutCornersAll,
        contentPadding = 12.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = horizontalAlignment,
        ) {
            Text(
                text = label,
                style = TrackTechTypography.UiTextLabel,
                color = accentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            MetricNumber(
                value = value,
                unit = unit,
                size = valueSize,
                kind = valueKind,
                valueColor = valueColor ?: TrackTechColors.TextPrimary,
                unitColor = TrackTechColors.TextSecondary,
                maxValueFontScale = maxValueFontScale,
            )
            if (!status.isNullOrEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = status,
                    style = TrackTechTypography.UiTextSmall,
                    color = TrackTechColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
