package com.blazepush.feature.test.ui.tracktech
// @IgnoreFormatCheck

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.outlined.DoNotDisturbOn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController

@Composable
fun RecordsHomeScreen(
    @Suppress("UNUSED_PARAMETER") navController: NavController,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var selectedSegment by remember { mutableStateOf("PERFORMANCE") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(TrackTechColors.Background)
            .verticalScroll(rememberScrollState())
            .padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Records",
            style = TrackTechTypography.RacingTitleLarge,
            color = TrackTechColors.TextPrimary,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        SegmentedControl(
            options = listOf("PERFORMANCE", "LAPS"),
            selected = selectedSegment,
            onSelect = { selectedSegment = it },
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MetricTile(
                    label = "BEST 0-100",
                    value = "4.21",
                    unit = "s",
                    status = "—",
                    accentColor = TrackTechColors.Purple,
                    modifier = Modifier.weight(1f),
                )
                MetricTile(
                    label = "BEST 100-0",
                    value = "36.8",
                    unit = "m",
                    status = "—",
                    accentColor = TrackTechColors.Red,
                    modifier = Modifier.weight(1f),
                )
                MetricTile(
                    label = "RUNS",
                    value = "24",
                    unit = null,
                    status = "—",
                    accentColor = TrackTechColors.Cyan,
                    modifier = Modifier.weight(1f),
                )
            }
            SpeedCurvePlaceholder()
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "RECENT RUNS",
                style = TrackTechTypography.UiTextLabel,
                color = TrackTechColors.Cyan,
            )
            listOf(
                Triple("0-100", "4.58 s", Icons.Filled.Speed),
                Triple("100-0", "38.2 m", Icons.Outlined.DoNotDisturbOn),
                Triple("0-100", "4.71 s", Icons.Filled.Speed),
                Triple("100-0", "39.5 m", Icons.Outlined.DoNotDisturbOn),
                Triple("0-100", "4.62 s", Icons.Filled.Speed),
            ).forEach { (label, value, icon) ->
                TrackTechRow(
                    leadingIcon = icon,
                    title = label,
                    subtitle = "$value · placeholder",
                    onClick = {
                        Toast.makeText(context, "Run detail placeholder", Toast.LENGTH_SHORT).show()
                    },
                )
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SegmentedControl(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = CutCornerPanelShape(cutSize = 8.dp, cutCorners = cutCornersAll)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(TrackTechColors.Surface, shape)
            .border(1.dp, TrackTechColors.BorderAlpha60, shape)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { opt ->
            val isSelected = opt == selected
            val itemShape = CutCornerPanelShape(cutSize = 6.dp, cutCorners = cutCornersDiagonal)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(itemShape)
                    .background(
                        if (isSelected) TrackTechColors.PurpleAlpha20 else Color.Transparent,
                        itemShape,
                    )
                    .let {
                        if (isSelected) it.border(1.dp, TrackTechColors.Purple, itemShape) else it
                    }
                    .clickable { onSelect(opt) }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = opt,
                    style = TrackTechTypography.UiTextLabel,
                    color = if (isSelected) TrackTechColors.TextPrimary else TrackTechColors.TextSecondary,
                )
            }
        }
    }
}

@Composable
private fun SpeedCurvePlaceholder() {
    CutCornerPanel(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp),
        cutSize = 12.dp,
        cutCorners = cutCornersDiagonal,
        contentPadding = 16.dp,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "SPEED CURVE",
                    style = TrackTechTypography.UiTextLabel,
                    color = TrackTechColors.Cyan,
                )
                Text(
                    text = "30s · placeholder",
                    style = TrackTechTypography.UiTextSmall,
                    color = TrackTechColors.TextMuted,
                )
            }
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(TrackTechColors.SurfaceDark),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Cyan speed curve · future round",
                    style = TrackTechTypography.UiTextSmall,
                    color = TrackTechColors.TextMuted,
                )
            }
        }
    }
}
