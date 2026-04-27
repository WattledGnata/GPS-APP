package com.blazepush.feature.test.ui.tracktech
// @IgnoreFormatCheck

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

enum class MetricSize { Hero, Medium, Small }

@Composable
fun MetricNumber(
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    size: MetricSize = MetricSize.Medium,
    valueColor: Color = TrackTechColors.TextPrimary,
    unitColor: Color = TrackTechColors.TextSecondary,
) {
    val numberStyle: TextStyle = when (size) {
        MetricSize.Hero -> TrackTechTypography.MetricHero
        MetricSize.Medium -> TrackTechTypography.MetricMedium
        MetricSize.Small -> TrackTechTypography.MetricSmall
    }
    val unitStyle: TextStyle = when (size) {
        MetricSize.Hero -> TrackTechTypography.UiTextBody
        MetricSize.Medium -> TrackTechTypography.UiTextSmall
        MetricSize.Small -> TrackTechTypography.UiTextSmall
    }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            text = value,
            style = numberStyle,
            color = valueColor,
        )
        if (!unit.isNullOrEmpty()) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = unit,
                style = unitStyle,
                color = unitColor,
            )
        }
    }
}
