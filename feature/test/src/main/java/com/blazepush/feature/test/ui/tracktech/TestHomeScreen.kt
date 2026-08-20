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
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.outlined.DoNotDisturbOn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.blazepush.core.domain.model.ConnectionState
import com.blazepush.core.domain.model.QualityLevel
import com.blazepush.core.domain.model.TestTemplate
import com.blazepush.feature.test.R
import com.blazepush.feature.test.viewmodel.GpsDataViewModel
import com.blazepush.feature.test.viewmodel.TestSessionViewModel
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
fun TestHomeScreen(
    navController: NavController,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    sessionViewModel: TestSessionViewModel = koinViewModel(),
) {
    val gpsViewModel = koinInject<GpsDataViewModel>()
    val gpsData by gpsViewModel.gpsData.collectAsState()
    val connectionState by gpsViewModel.connectionState.collectAsState()
    val dataQuality by gpsViewModel.dataQuality.collectAsState()
    val lapGpsReadiness by sessionViewModel.lapGpsReadiness.collectAsState()

    val readiness = remember(connectionState, gpsData, dataQuality) {
        TabGatingPolicy.computeTabReadiness(connectionState, gpsData, dataQuality)
    }

    val bestAcc by sessionViewModel.bestAcceleration0To100.collectAsState()
    val recentRuns by sessionViewModel.recentRuns.collectAsState()
    val lastAcc = remember(recentRuns) {
        // recentRuns 已按 timestamp DESC，第一条 acc_0_100 即"最近一次 0-100"。
        // PERSONAL BEST / LAST RUN 区块字面量 "0-100"，所以只取加速测试，不混制动测试。
        recentRuns.firstOrNull { it.testTemplateId == "acc_0_100" }
    }

    val signalGood = dataQuality.overall == QualityLevel.EXCELLENT ||
        dataQuality.overall == QualityLevel.GOOD
    val signalLabel = when (dataQuality.overall) {
        QualityLevel.EXCELLENT, QualityLevel.GOOD -> stringResource(R.string.quality_good_signal)
        QualityLevel.FAIR -> stringResource(R.string.quality_weak_signal)
        QualityLevel.POOR -> stringResource(R.string.quality_no_signal)
    }
    val gpsPresentation = remember(lapGpsReadiness, connectionState) {
        GpsReadinessPresentationMapper.present(lapGpsReadiness, connectionState)
    }
    val statusItems = statusItemsFromGpsState(
        gpsStatusLabel = stringResource(gpsPresentation.shortLabelRes),
        gpsStatusTone = gpsPresentation.tone,
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
                text = stringResource(R.string.screen_drive_test),
                style = TrackTechTypography.RacingTitleLarge,
                color = TrackTechColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        TrackTechStatusStrip(
            items = statusItems,
            onClick = {
                onTabSelected(TabIndex.Device)
            },
        )

        SpeedHero(speed = gpsData.speed.toInt(), gpsPresentation = gpsPresentation)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.label_performance_test),
                style = TrackTechTypography.UiTextLabel,
                color = TrackTechColors.Cyan,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            PrimaryActionPanel(
                title = "0-100",
                subtitle = stringResource(R.string.label_acceleration),
                leadingIcon = Icons.Filled.Speed,
                onClick = {
                    if (readiness.canEnterTestFlow) {
                        sessionViewModel.enterSmartLaunch(TestTemplate.Acceleration0To100, "Car")
                        navController.navigate("test_execution")
                    } else {
                        onTabSelected(TabIndex.Device)
                        if (connectionState == ConnectionState.DISCONNECTED) {
                            TrackTechEventBus.requestShowScanSheet()
                        }
                    }
                },
            )
            SecondaryActionPanel(
                title = "100-0",
                subtitle = stringResource(R.string.label_braking),
                leadingIcon = Icons.Outlined.DoNotDisturbOn,
                onClick = {
                    if (readiness.canEnterTestFlow) {
                        sessionViewModel.enterSmartLaunch(TestTemplate.Braking100To0, "Car")
                        navController.navigate("test_execution")
                    } else {
                        onTabSelected(TabIndex.Device)
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
                text = stringResource(R.string.label_latest_result),
                style = TrackTechTypography.UiTextLabel,
                color = TrackTechColors.Cyan,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MetricTile(
                    label = stringResource(R.string.label_personal_best),
                    value = bestAcc?.let { "%.2f".format(it.totalTime) } ?: "—.—",
                    unit = "s",
                    status = "0-100",
                    accentColor = TrackTechColors.Purple,
                    valueSize = MetricSize.Medium,
                    modifier = Modifier.weight(1f),
                )
                MetricTile(
                    label = stringResource(R.string.label_last_run),
                    value = lastAcc?.let { "%.2f".format(it.totalTime) } ?: "—.—",
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
private fun SpeedHero(speed: Int, gpsPresentation: GpsReadinessPresentation) {
    val isReady = gpsPresentation.tone == GpsReadinessTone.READY
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
                text = stringResource(R.string.label_speed),
                style = TrackTechTypography.UiTextLabel,
                color = TrackTechColors.Cyan,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Box {
                MetricNumber(
                    value = speed.toString(),
                    unit = "km/h",
                    size = MetricSize.Hero,
                    kind = MetricKind.Mechanical,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.label_status),
                    style = TrackTechTypography.UiTextLabel,
                    color = TrackTechColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (isReady) {
                        stringResource(R.string.speed_status_ready)
                    } else {
                        stringResource(gpsPresentation.shortLabelRes)
                    },
                    style = TrackTechTypography.UiTextLabel,
                    color = if (isReady) TrackTechColors.Green else TrackTechColors.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
