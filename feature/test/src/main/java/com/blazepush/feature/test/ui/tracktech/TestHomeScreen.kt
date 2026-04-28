package com.blazepush.feature.test.ui.tracktech
// @IgnoreFormatCheck

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.outlined.DoNotDisturbOn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import com.blazepush.core.domain.model.ConnectionState
import com.blazepush.core.domain.model.QualityLevel
import com.blazepush.core.domain.model.TestTemplate
import com.blazepush.feature.test.viewmodel.GpsDataViewModel
import com.blazepush.feature.test.viewmodel.TestSessionViewModel
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
fun TestHomeScreen(
    navController: NavController,
    modifier: Modifier = Modifier,
) {
    val gpsViewModel = koinInject<GpsDataViewModel>()
    val sessionViewModel = koinViewModel<TestSessionViewModel>()
    val gpsData by gpsViewModel.gpsData.collectAsState()
    val connectionState by gpsViewModel.connectionState.collectAsState()
    val dataQuality by gpsViewModel.dataQuality.collectAsState()

    val readiness = remember(connectionState, gpsData, dataQuality) {
        TabGatingPolicy.computeTabReadiness(connectionState, gpsData, dataQuality)
    }

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
                text = "Drive Test",
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
            onClick = {
                navController.navigateToTab("device")
            },
        )

        SpeedHero(speed = gpsData.speed.toInt(), isReady = gpsData.isTestReady)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "PERFORMANCE TEST",
                style = TrackTechTypography.UiTextLabel,
                color = TrackTechColors.Cyan,
            )
            PrimaryActionPanel(
                title = "0-100",
                subtitle = "ACCELERATION",
                leadingIcon = Icons.Filled.Speed,
                onClick = {
                    if (readiness.canEnterTestFlow) {
                        sessionViewModel.enterSmartLaunch(TestTemplate.Acceleration0To100, "Car")
                        navController.navigate("test_execution")
                    } else {
                        navController.navigateToTab("device")
                        if (connectionState == ConnectionState.DISCONNECTED) {
                            TrackTechEventBus.requestShowScanSheet()
                        }
                    }
                },
            )
            SecondaryActionPanel(
                title = "100-0",
                subtitle = "BRAKING",
                leadingIcon = Icons.Outlined.DoNotDisturbOn,
                onClick = {
                    if (readiness.canEnterTestFlow) {
                        sessionViewModel.enterSmartLaunch(TestTemplate.Braking100To0, "Car")
                        navController.navigate("test_execution")
                    } else {
                        navController.navigateToTab("device")
                        if (connectionState == ConnectionState.DISCONNECTED) {
                            TrackTechEventBus.requestShowScanSheet()
                        }
                    }
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
                text = "LATEST RESULT",
                style = TrackTechTypography.UiTextLabel,
                color = TrackTechColors.Cyan,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MetricTile(
                    label = "PERSONAL BEST",
                    value = "—.—",
                    unit = "s",
                    status = "0-100",
                    accentColor = TrackTechColors.Purple,
                    valueSize = MetricSize.Medium,
                    modifier = Modifier.weight(1f),
                )
                MetricTile(
                    label = "LAST RUN",
                    value = "—.—",
                    unit = "s",
                    status = "0-100",
                    accentColor = TrackTechColors.Cyan,
                    valueSize = MetricSize.Medium,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SpeedHero(speed: Int, isReady: Boolean) {
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
                text = "SPEED",
                style = TrackTechTypography.UiTextLabel,
                color = TrackTechColors.Cyan,
            )
            Spacer(Modifier.height(8.dp))
            Box {
                MetricNumber(
                    value = speed.toString(),
                    unit = "km/h",
                    size = MetricSize.Hero,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "STATUS",
                    style = TrackTechTypography.UiTextLabel,
                    color = TrackTechColors.TextSecondary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (isReady) "READY" else "STANDBY",
                    style = TrackTechTypography.UiTextLabel,
                    color = if (isReady) TrackTechColors.Green else TrackTechColors.TextMuted,
                )
            }
        }
    }
}

internal fun NavController.navigateToTab(route: String) {
    if (currentDestination?.route == route) return
    navigate(route) {
        val startId = graph.findStartDestination().id
        popUpTo(startId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
