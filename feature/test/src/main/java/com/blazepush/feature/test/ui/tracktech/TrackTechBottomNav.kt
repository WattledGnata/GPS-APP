package com.blazepush.feature.test.ui.tracktech
// @IgnoreFormatCheck

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

data class TrackTechTabItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

val DefaultTrackTechTabs = listOf(
    TrackTechTabItem("test", "Test", Icons.Filled.Speed),
    TrackTechTabItem("laps", "Laps", Icons.Filled.Flag),
    TrackTechTabItem("records", "Records", Icons.Filled.Insights),
    TrackTechTabItem("device", "Device", Icons.Filled.Bluetooth),
)

@Composable
fun TrackTechBottomNav(
    currentPage: Int,
    onTabSelected: (Int) -> Unit,
    tabs: List<TrackTechTabItem> = DefaultTrackTechTabs,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(68.dp)
            .background(TrackTechColors.SurfaceDark)
            .border(1.dp, TrackTechColors.BorderAlpha60)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEachIndexed { index, tab ->
            val selected = currentPage == index
            TrackTechBottomNavItem(
                tab = tab,
                selected = selected,
                onClick = {
                    if (currentPage != index) {
                        onTabSelected(index)
                    }
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun TrackTechBottomNavItem(
    tab: TrackTechTabItem,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val indicatorShape = CutCornerPanelShape(cutSize = 6.dp, cutCorners = cutCornersDiagonal)
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(indicatorShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .then(
                if (selected) {
                    Modifier
                        .background(TrackTechColors.PurpleAlpha20, indicatorShape)
                        .border(1.dp, TrackTechColors.Purple, indicatorShape)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = tab.icon,
                contentDescription = tab.label,
                tint = if (selected) TrackTechColors.Purple else TrackTechColors.TextSecondary,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = tab.label,
                style = TrackTechTypography.UiTextSmall,
                color = if (selected) TrackTechColors.TextPrimary else TrackTechColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
