package com.blazepush.feature.test.ui.tracktech
// @IgnoreFormatCheck

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.blazepush.core.domain.model.ConnectionState
import com.blazepush.core.domain.model.DataQuality
import com.blazepush.core.domain.model.GpsData
import com.blazepush.core.domain.model.QualityLevel
import com.blazepush.feature.test.viewmodel.GpsDataViewModel
import org.koin.compose.koinInject

@Composable
fun GpsDetailsScreen(
    navController: NavController,
    modifier: Modifier = Modifier,
) {
    val gpsViewModel = koinInject<GpsDataViewModel>()
    val gpsData by gpsViewModel.gpsData.collectAsState()
    val connectionState by gpsViewModel.connectionState.collectAsState()
    val dataQuality by gpsViewModel.dataQuality.collectAsState()

    val qualityLabel = when (dataQuality.overall) {
        QualityLevel.EXCELLENT -> "Excellent"
        QualityLevel.GOOD -> "Good"
        QualityLevel.FAIR -> "Fair"
        QualityLevel.POOR -> "Poor"
    }
    val frequencyHz = gpsData.frequency.toInt().coerceAtLeast(0)
    val tabReadiness = TabGatingPolicy.computeTabReadiness(
        connectionState = connectionState,
        gpsData = gpsData,
        dataQuality = dataQuality,
    )
    val isReady = tabReadiness.canEnterTestFlow
    // 4 badges 与 TabGatingPolicy 4 条件一一对应，unmetConditions 驱动
    val bleOk = !tabReadiness.unmetConditions.contains(TabReadinessCondition.BLE_CONNECTED)
    val freshOk = !tabReadiness.unmetConditions.contains(TabReadinessCondition.DATA_FRESH)
    val satsOk = !tabReadiness.unmetConditions.contains(TabReadinessCondition.SATELLITES_SUFFICIENT)
    val hdopOk = !tabReadiness.unmetConditions.contains(TabReadinessCondition.HDOP_GOOD)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(TrackTechColors.Background)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        GpsDetailsHeader(
            isReady = isReady,
            onBack = { navController.popBackStack() },
        )

        ReadinessHeroBanner(
            isReady = isReady,
            bleOk = bleOk,
            freshOk = freshOk,
            satsOk = satsOk,
            hdopOk = hdopOk,
        )

        DetailsSection(title = "SIGNAL")
        SignalGrid(gpsData = gpsData, qualityLabel = qualityLabel)

        DetailsSection(title = "DATA STREAM")
        DataStreamGrid(frequencyHz = frequencyHz, dataQuality = dataQuality)

        DetailsSection(title = "POSITION")
        PositionPanel(gpsData = gpsData)

        DetailsSection(title = "DEVICE")
        DeviceInfoPanel(connectionState = connectionState, isStale = gpsData.isStale)

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun GpsDetailsHeader(isReady: Boolean, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CutCornerPanelShape(cutSize = 6.dp, cutCorners = cutCornersAll))
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.ArrowBack,
                contentDescription = "Back",
                tint = TrackTechColors.TextPrimary,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = "GPS Details",
            style = TrackTechTypography.RacingTitleMedium,
            color = TrackTechColors.TextPrimary,
            modifier = Modifier.weight(1f),
        )
        ReadinessBadge(isReady = isReady)
    }
}

@Composable
private fun ReadinessBadge(isReady: Boolean) {
    val color = if (isReady) TrackTechColors.Green else TrackTechColors.TextMuted
    val label = if (isReady) "READY" else "NOT READY"
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, color, RoundedCornerShape(16.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(color, CircleShape),
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = label,
                style = TrackTechTypography.UiTextLabel,
                color = color,
            )
        }
    }
}

@Composable
private fun ReadinessHeroBanner(
    isReady: Boolean,
    bleOk: Boolean,
    freshOk: Boolean,
    satsOk: Boolean,
    hdopOk: Boolean,
) {
    val accent = if (isReady) TrackTechColors.Green else TrackTechColors.Red
    val heroTitle = if (isReady) "READY TO TEST" else "NOT READY"
    val heroSub = if (isReady) "All readiness checks passed" else "Some conditions not met"

    CutCornerPanel(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        cutSize = 16.dp,
        cutCorners = cutCornersDiagonal,
        contentPadding = 0.dp,
        borderColor = accent.copy(alpha = 0.6f),
    ) {
        Box {
            // Decorative diagonal lines on the right
            Canvas(
                modifier = Modifier
                    .matchParentSize(),
            ) {
                val lineColor = TrackTechColors.Purple.copy(alpha = 0.15f)
                val step = 18.dp.toPx()
                var x = size.width * 0.55f
                while (x < size.width + size.height) {
                    drawLine(
                        color = lineColor,
                        start = Offset(x, 0f),
                        end = Offset(x - size.height, size.height),
                        strokeWidth = 1.2.dp.toPx(),
                    )
                    x += step
                }
            }

            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = heroTitle,
                    style = TrackTechTypography.RacingTitleLarge,
                    color = accent,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = heroSub,
                    style = TrackTechTypography.UiTextSmall,
                    color = TrackTechColors.TextSecondary,
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    CheckBadge(label = "BLE", ok = bleOk)
                    CheckDivider()
                    CheckBadge(label = "FRESH", ok = freshOk)
                    CheckDivider()
                    CheckBadge(label = "SATS", ok = satsOk)
                    CheckDivider()
                    CheckBadge(label = "HDOP", ok = hdopOk)
                }
            }
        }
    }
}

@Composable
private fun CheckBadge(label: String, ok: Boolean) {
    val color = if (ok) TrackTechColors.Green else TrackTechColors.Red
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .background(color.copy(alpha = 0.18f), CircleShape)
                .border(1.dp, color.copy(alpha = 0.5f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (ok) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(10.dp),
                )
            } else {
                Box(Modifier.size(6.dp).background(color, CircleShape))
            }
        }
        Spacer(Modifier.width(5.dp))
        Text(
            text = label,
            style = TrackTechTypography.UiTextLabel,
            color = TrackTechColors.TextSecondary,
        )
    }
}

@Composable
private fun CheckDivider() {
    Box(
        modifier = Modifier
            .height(14.dp)
            .width(1.dp)
            .background(TrackTechColors.Border),
    )
}

@Composable
private fun DetailsSection(title: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = TrackTechTypography.UiTextLabel,
            color = TrackTechColors.Purple,
        )
        Spacer(Modifier.width(8.dp))
        Canvas(
            modifier = Modifier
                .weight(1f)
                .height(12.dp),
        ) {
            val slashW = 6.dp.toPx()
            val slashGap = 4.dp.toPx()
            val lineColor = TrackTechColors.Purple.copy(alpha = 0.30f)
            var x = 0f
            while (x < size.width) {
                drawLine(
                    color = lineColor,
                    start = Offset(x, size.height),
                    end = Offset(x + slashW, 0f),
                    strokeWidth = 1.5.dp.toPx(),
                )
                x += slashW + slashGap
            }
        }
    }
}

@Composable
private fun SignalGrid(gpsData: GpsData, qualityLabel: String) {
    val satsOk = gpsData.satelliteCount >= 6
    val hdopOk = gpsData.hdop > 0.0 && gpsData.hdop < 2.0
    val fixLabel = when (gpsData.fixQuality) {
        0 -> "—"
        2 -> "3D+"
        else -> "3D"
    }
    val fixStatus = if (gpsData.fixQuality > 0) "Locked" else "No Fix"
    val qualityColor = when (qualityLabel) {
        "Excellent", "Good" -> TrackTechColors.Green
        "Fair" -> TrackTechColors.Cyan
        else -> TrackTechColors.Red
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DetailMetricTile(
                label = "SATELLITES",
                value = gpsData.satelliteCount.toString(),
                unit = null,
                status = if (satsOk) "Good" else "Low",
                dotColor = if (satsOk) TrackTechColors.Green else TrackTechColors.Red,
                valueKind = MetricKind.Mechanical,
                modifier = Modifier.weight(1f),
            )
            DetailMetricTile(
                label = "HDOP",
                value = "%.1f".format(gpsData.hdop),
                unit = null,
                status = if (hdopOk) "Good" else "Poor",
                dotColor = if (hdopOk) TrackTechColors.Green else TrackTechColors.Red,
                valueKind = MetricKind.Mechanical,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DetailMetricTile(
                label = "FIX",
                value = fixLabel,
                unit = null,
                status = fixStatus,
                dotColor = if (gpsData.fixQuality > 0) TrackTechColors.Green else TrackTechColors.Red,
                modifier = Modifier.weight(1f),
            )
            DetailMetricTile(
                label = "QUALITY",
                value = qualityLabel,
                unit = null,
                status = if (qualityLabel == "Good" || qualityLabel == "Excellent") "Stable" else "Unstable",
                dotColor = qualityColor,
                valueColor = qualityColor,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun DataStreamGrid(frequencyHz: Int, dataQuality: DataQuality) {
    val freshMs = dataQuality.dataAge
    val isFresh = freshMs < 1000L
    // packetLoss 已经是 0-100 的百分比，直接格式化，不再乘 100
    val packetLossPct = dataQuality.packetLoss
    val droppedStr = "%.1f".format(packetLossPct)
    // D9: 字母 s / ms 拆到 unit，避免 Mechanical (DSEG7) 渲染字母变形
    val freshDisplayStr = when {
        freshMs <= 999L -> freshMs.toString()
        freshMs <= 9999L -> "%.1f".format(freshMs / 1000.0)
        else -> "—"
    }
    val freshDisplayUnit: String? = when {
        freshMs <= 999L -> "ms"
        freshMs <= 9999L -> "s"
        else -> null
    }
    val lastPacketStr = when {
        freshMs < 1000L -> "Now"
        freshMs < 60_000L -> "${freshMs / 1000}s ago"
        else -> "—"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DetailMetricTile(
                label = "RATE",
                value = frequencyHz.toString(),
                unit = "Hz",
                status = null,
                dotColor = if (frequencyHz >= 10) TrackTechColors.Green else TrackTechColors.Red,
                valueKind = MetricKind.Mechanical,
                modifier = Modifier.weight(1f),
            )
            DetailMetricTile(
                label = "FRESH",
                value = freshDisplayStr,
                unit = freshDisplayUnit,
                status = null,
                dotColor = if (isFresh) TrackTechColors.Green else TrackTechColors.Red,
                valueKind = MetricKind.Mechanical,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DetailMetricTile(
                label = "DROPPED",
                value = droppedStr,
                unit = "%",
                status = null,
                dotColor = if (packetLossPct < 5.0) TrackTechColors.Green else TrackTechColors.Red,
                valueKind = MetricKind.Mechanical,
                modifier = Modifier.weight(1f),
            )
            DetailMetricTile(
                label = "LAST PACKET",
                value = lastPacketStr,
                unit = null,
                status = null,
                dotColor = if (isFresh) TrackTechColors.Green else TrackTechColors.Red,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun PositionPanel(gpsData: GpsData) {
    CutCornerPanel(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        cutSize = 10.dp,
        cutCorners = cutCornersAll,
        borderColor = TrackTechColors.Purple.copy(alpha = 0.45f),
        contentPadding = 16.dp,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                PositionField(
                    label = "Latitude",
                    value = "%.5f°".format(gpsData.latitude),
                    modifier = Modifier.weight(1f),
                )
                PositionField(
                    label = "Longitude",
                    value = "%.5f°".format(gpsData.longitude),
                    modifier = Modifier.weight(1f),
                )
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                PositionField(
                    label = "Altitude",
                    value = "${gpsData.altitude.toInt()} m",
                    modifier = Modifier.weight(1f),
                )
                PositionField(
                    label = "Bearing",
                    value = "%03d°".format(gpsData.bearing.toInt()),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun PositionField(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = TrackTechTypography.UiTextSmall,
            color = TrackTechColors.TextMuted,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            style = TrackTechTypography.UiTextLabel,
            color = TrackTechColors.Cyan,
        )
    }
}

@Composable
private fun DeviceInfoPanel(connectionState: ConnectionState, isStale: Boolean) {
    val isConnected = connectionState == ConnectionState.CONNECTED
    CutCornerPanel(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        cutSize = 10.dp,
        cutCorners = cutCornersAll,
        borderColor = TrackTechColors.Purple.copy(alpha = 0.45f),
        contentPadding = 0.dp,
    ) {
        Column {
            DeviceInfoRow(
                iconContent = {
                    Icon(
                        imageVector = Icons.Filled.SignalCellularAlt,
                        contentDescription = null,
                        tint = TrackTechColors.Cyan,
                        modifier = Modifier.size(18.dp),
                    )
                },
                label = "RSSI",
                value = if (isConnected) "— dBm" else "—",
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(TrackTechColors.Border),
            )
            DeviceInfoRow(
                iconContent = {
                    Icon(
                        imageVector = Icons.Filled.GraphicEq,
                        contentDescription = null,
                        tint = TrackTechColors.Cyan,
                        modifier = Modifier.size(18.dp),
                    )
                },
                label = "Protocol",
                value = if (isConnected) "RaceChrono BLE" else "—",
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(TrackTechColors.Border),
            )
            // ble-connection-liveness：丢星时链路保持 CONNECTED，这里呈"等待卫星"而非"已断开"。
            DeviceInfoRow(
                iconContent = {
                    Icon(
                        imageVector = Icons.Filled.SignalCellularAlt,
                        contentDescription = null,
                        tint = if (isConnected && isStale) TrackTechColors.Red else TrackTechColors.Cyan,
                        modifier = Modifier.size(18.dp),
                    )
                },
                label = "Signal",
                value = when {
                    !isConnected -> "—"
                    isStale -> "等待卫星"
                    else -> "实时"
                },
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(TrackTechColors.Border),
            )
            DeviceInfoRow(
                iconContent = {
                    Icon(
                        imageVector = Icons.Filled.Autorenew,
                        contentDescription = null,
                        tint = TrackTechColors.Cyan,
                        modifier = Modifier.size(18.dp),
                    )
                },
                label = "Auto reconnect",
                value = "On",
            )
        }
    }
}

@Composable
private fun DeviceInfoRow(
    iconContent: @Composable () -> Unit,
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        iconContent()
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            style = TrackTechTypography.UiTextBody,
            color = TrackTechColors.TextSecondary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = TrackTechTypography.UiTextLabel,
            color = TrackTechColors.Cyan,
        )
    }
}

@Composable
private fun DetailMetricTile(
    label: String,
    value: String,
    unit: String?,
    status: String?,
    dotColor: Color,
    modifier: Modifier = Modifier,
    valueColor: Color = TrackTechColors.TextPrimary,
    valueKind: MetricKind = MetricKind.Score,
) {
    CutCornerPanel(
        modifier = modifier.fillMaxWidth(),
        cutSize = 8.dp,
        cutCorners = cutCornersAll,
        borderColor = TrackTechColors.Purple.copy(alpha = 0.5f),
        contentPadding = 12.dp,
    ) {
        Column {
            Text(
                text = label,
                style = TrackTechTypography.UiTextLabel,
                color = TrackTechColors.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = value,
                    style = when (valueKind) {
                        MetricKind.Mechanical -> TrackTechTypography.MechanicalMedium
                        MetricKind.Score -> TrackTechTypography.ScoreMedium
                    },
                    color = valueColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!unit.isNullOrEmpty()) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = unit,
                        style = TrackTechTypography.UiTextSmall,
                        color = TrackTechColors.TextSecondary,
                        modifier = Modifier.padding(bottom = 5.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .background(dotColor, CircleShape),
                )
                if (!status.isNullOrEmpty()) {
                    Spacer(Modifier.width(5.dp))
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
}