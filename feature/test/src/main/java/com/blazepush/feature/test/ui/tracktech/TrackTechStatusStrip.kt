package com.blazepush.feature.test.ui.tracktech
// @IgnoreFormatCheck

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.GraphicEq

data class TrackTechStatusItem(
    val icon: ImageVector,
    val label: String,
    val color: Color,
)

@Composable
fun TrackTechStatusStrip(
    items: List<TrackTechStatusItem>,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val baseModifier = modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp)
    val rowModifier = if (onClick != null) baseModifier.clickable(onClick = onClick) else baseModifier
    Row(
        modifier = rowModifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { item ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = item.icon,
                    contentDescription = item.label,
                    tint = item.color,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = item.label,
                    style = TrackTechTypography.UiTextSmall,
                    color = item.color,
                )
            }
        }
    }
}

/** 由 GpsDataViewModel 当前状态派生 3 项 status item。 */
fun statusItemsFromGpsState(
    gpsReady: Boolean,
    frequencyHz: Int,
    signalLabel: String,  // "Good signal" / "Weak signal" / "No signal"
    signalIsGood: Boolean,
): List<TrackTechStatusItem> = listOf(
    TrackTechStatusItem(
        icon = Icons.Filled.GpsFixed,
        label = if (gpsReady) "GPS ready" else "GPS no fix",
        color = if (gpsReady) TrackTechColors.Green else TrackTechColors.Red,
    ),
    TrackTechStatusItem(
        icon = Icons.Filled.GraphicEq,
        label = "${frequencyHz}Hz",
        color = TrackTechColors.Cyan,
    ),
    TrackTechStatusItem(
        icon = Icons.Filled.SignalCellularAlt,
        label = signalLabel,
        color = if (signalIsGood) TrackTechColors.Green else TrackTechColors.Red,
    ),
)
