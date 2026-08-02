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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

enum class MetricSize { Hero, Large, Medium, Small }

enum class MetricKind { Mechanical, Score }

@Composable
fun MetricNumber(
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    size: MetricSize = MetricSize.Medium,
    kind: MetricKind = MetricKind.Score,
    valueColor: Color = TrackTechColors.TextPrimary,
    unitColor: Color = TrackTechColors.TextSecondary,
    maxValueFontScale: Float? = null,
) {
    val numberStyle: TextStyle = when (kind) {
        MetricKind.Mechanical -> when (size) {
            MetricSize.Hero -> TrackTechTypography.MechanicalHero
            MetricSize.Large -> TrackTechTypography.MechanicalLarge
            MetricSize.Medium -> TrackTechTypography.MechanicalMedium
            MetricSize.Small -> TrackTechTypography.MechanicalSmall
        }
        MetricKind.Score -> when (size) {
            MetricSize.Hero -> TrackTechTypography.ScoreHero
            MetricSize.Large -> TrackTechTypography.ScoreLarge
            MetricSize.Medium -> TrackTechTypography.ScoreMedium
            MetricSize.Small -> TrackTechTypography.ScoreSmall
        }
    }
    val fontScale = LocalDensity.current.fontScale
    val valueScaleCorrection = maxValueFontScale
        ?.takeIf { fontScale > it }
        ?.let { it / fontScale }
        ?: 1f
    val fittedNumberStyle = numberStyle.copy(
        fontSize = numberStyle.fontSize * valueScaleCorrection,
    )
    val unitStyle: TextStyle = when (size) {
        MetricSize.Hero -> TrackTechTypography.UiTextBody
        MetricSize.Large -> TrackTechTypography.UiTextBody
        MetricSize.Medium -> TrackTechTypography.UiTextSmall
        MetricSize.Small -> TrackTechTypography.UiTextSmall
    }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            text = value,
            style = fittedNumberStyle,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!unit.isNullOrEmpty()) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = unit,
                style = unitStyle,
                color = unitColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
