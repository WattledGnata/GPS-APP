package com.blazepush.feature.test.ui.tracktech
// @IgnoreFormatCheck

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.blazepush.core.domain.model.ConnectionState
import com.blazepush.core.domain.model.QualityLevel
import com.blazepush.feature.test.viewmodel.GpsDataViewModel
import com.blazepush.feature.test.viewmodel.TestSessionViewModel
import org.koin.compose.koinInject
import org.koin.androidx.compose.koinViewModel

@Composable
fun LapsHomeScreen(
    navController: NavController,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    testSessionViewModel: TestSessionViewModel = koinViewModel(),
) {
    val gpsViewModel = koinInject<GpsDataViewModel>()
    val gpsData by gpsViewModel.gpsData.collectAsState()
    val connectionState by gpsViewModel.connectionState.collectAsState()
    val dataQuality by gpsViewModel.dataQuality.collectAsState()
    val availableTracks by testSessionViewModel.availableTracks.collectAsState()

    val readiness = remember(connectionState, gpsData, dataQuality) {
        TabGatingPolicy.computeTabReadiness(connectionState, gpsData, dataQuality)
    }
    val context = LocalContext.current

    val signalGood = dataQuality.overall == QualityLevel.EXCELLENT ||
        dataQuality.overall == QualityLevel.GOOD
    val signalLabel = when (dataQuality.overall) {
        QualityLevel.EXCELLENT, QualityLevel.GOOD -> "Good signal"
        QualityLevel.FAIR -> "Weak signal"
        QualityLevel.POOR -> "No signal"
    }
    val statusItems = statusItemsFromGpsState(
        gpsReady = gpsData.isTestReady,
        frequencyHz = gpsData.frequency.toInt(),
        signalLabel = signalLabel,
        signalIsGood = signalGood,
    )

    val currentTrackName = availableTracks.firstOrNull()?.name ?: "Shanghai Tianma"

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(TrackTechColors.Background)
            .verticalScroll(rememberScrollState())
            .padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Laps",
                style = TrackTechTypography.RacingTitleLarge,
                color = TrackTechColors.TextPrimary,
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.HelpOutline,
                contentDescription = "Help",
                tint = TrackTechColors.TextSecondary,
                modifier = Modifier.size(20.dp),
            )
        }

        TrackTechStatusStrip(
            items = statusItems,
            onClick = { onTabSelected(TabIndex.Device) },
        )

        CurrentTrackPanel(trackName = currentTrackName)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PrimaryActionPanel(
                title = "START LAP SESSION",
                subtitle = "BEGIN TIMING",
                leadingIcon = Icons.Filled.Flag,
                onClick = {
                    if (readiness.canEnterTestFlow) {
                        Toast.makeText(
                            context,
                            "Lap session entry — placeholder for future round",
                            Toast.LENGTH_SHORT,
                        ).show()
                    } else {
                        Toast.makeText(
                            context,
                            "Connect a GPS device first",
                            Toast.LENGTH_SHORT,
                        ).show()
                        onTabSelected(TabIndex.Device)
                        if (connectionState == ConnectionState.DISCONNECTED) {
                            TrackTechEventBus.requestShowScanSheet()
                        }
                    }
                },
            )
            SecondaryActionPanel(
                title = "CHANGE TRACK",
                subtitle = "BROWSE PRESETS",
                leadingIcon = Icons.Filled.SwapHoriz,
                accentColor = TrackTechColors.Cyan,
                onClick = {
                    Toast.makeText(
                        context,
                        "Track selection — placeholder for future round",
                        Toast.LENGTH_SHORT,
                    ).show()
                },
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "RECENT BEST",
                style = TrackTechTypography.UiTextLabel,
                color = TrackTechColors.Cyan,
            )
            MetricTile(
                label = currentTrackName.uppercase(),
                value = "1:32.457",
                status = "Personal Best · placeholder",
                accentColor = TrackTechColors.Purple,
                valueSize = MetricSize.Medium,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "NEARBY TRACKS",
                style = TrackTechTypography.UiTextLabel,
                color = TrackTechColors.Cyan,
            )
            listOf("Shanghai Tianma", "TFIC LPCC", "Coming soon").forEach { name ->
                TrackTechRow(
                    leadingIcon = Icons.Filled.Flag,
                    title = name.uppercase(),
                    subtitle = "Preset · placeholder",
                    onClick = {
                        Toast.makeText(context, "Track detail placeholder", Toast.LENGTH_SHORT).show()
                    },
                )
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun CurrentTrackPanel(trackName: String) {
    CutCornerPanel(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        cutSize = 16.dp,
        cutCorners = cutCornersDiagonal,
        contentPadding = 20.dp,
    ) {
        Column {
            Text(
                text = "CURRENT TRACK",
                style = TrackTechTypography.UiTextLabel,
                color = TrackTechColors.Cyan,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = trackName,
                style = TrackTechTypography.RacingTitleMedium,
                color = TrackTechColors.TextPrimary,
            )
            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .background(TrackTechColors.SurfaceDark),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "TRACK PREVIEW · PLACEHOLDER",
                    style = TrackTechTypography.UiTextSmall,
                    color = TrackTechColors.TextMuted,
                )
            }
        }
    }
}
