package com.blazepush.feature.test.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blazepush.feature.test.model.LapRunConfig
import com.blazepush.feature.test.model.laptiming.CrossingEvent
import com.blazepush.feature.test.model.laptiming.GpsSample
import com.blazepush.feature.test.model.laptiming.LapSession
import com.blazepush.feature.test.model.track.TimingGateType
import com.blazepush.feature.test.model.track.Track
import com.blazepush.feature.test.ui.components.LapDebugMapPlaceholder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LapDebugExecutionScreen(
    track: Track,
    lapRunConfig: LapRunConfig?,
    lapSession: LapSession?,
    latestCrossing: CrossingEvent?,
    telemetry: LapDebugTelemetry,
    onStop: () -> Unit
) {
    val orderedGates = listOf(track.startFinishGate) + track.sectorGates.sortedBy { it.sequenceIndex }
    val nextGate = lapSession?.nextExpectedGateIndex?.let { index -> orderedGates.getOrNull(index) }
    val currentLap = (lapSession?.currentLapIndex ?: 0) + 1
    val trajectory = lapSession?.samples ?: emptyList()
    val timingCardState = rememberStartFinishTimingCardState(lapSession)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "圈速调试执行中",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(text = "Track: ${track.name}", fontWeight = FontWeight.Bold)
                Text(text = "当前圈: Lap $currentLap")
                Text(text = "下一 Gate: ${nextGate?.name ?: "无"}")
                Text(text = "会话状态: ${lapSession?.status?.name ?: "Idle"}")
                lapRunConfig?.let {
                    Text(text = "配置 Track ID: ${it.trackId}")
                }
            }
        }

        TelemetryCard(telemetry = telemetry)
        StartFinishTimingCard(state = timingCardState)

        LapDebugMapPlaceholder(
            track = track,
            trajectory = trajectory,
            latestCrossing = latestCrossing,
            title = "Track / Gate / Crossing 概览"
        )

        OutlinedButton(
            onClick = onStop,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("停止圈速记录")
        }
    }
}

@Composable
private fun TelemetryCard(telemetry: LapDebugTelemetry) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(text = "实时遥测", fontWeight = FontWeight.Bold)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TelemetryMetric(label = "Speed", value = formatTelemetryValue(telemetry.speedKmh, "km/h"))
                TelemetryMetric(label = "Bearing", value = formatTelemetryValue(telemetry.bearingDegrees, "°"))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TelemetryMetric(label = "Forward G", value = formatTelemetryValue(telemetry.forwardG, "G"))
                TelemetryMetric(label = "Lateral G", value = formatTelemetryValue(telemetry.lateralG, "G"))
            }
        }
    }
}

@Composable
private fun TelemetryMetric(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = label, style = MaterialTheme.typography.labelMedium)
        Text(text = value, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun StartFinishTimingCard(state: StartFinishTimingCardState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(text = "起终点计时", fontWeight = FontWeight.Bold)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TimingSummaryField(
                    modifier = Modifier.weight(1f),
                    label = "上一圈",
                    value = state.lastLapElapsedLabel
                )
                TimingSummaryField(
                    modifier = Modifier.weight(1f),
                    label = "当前圈",
                    value = state.currentLapElapsedLabel
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TimingSummaryField(
                    modifier = Modifier.weight(1f),
                    label = "当前圈路程",
                    value = state.currentLapDistanceLabel
                )
                TimingSummaryField(
                    modifier = Modifier.weight(1f),
                    label = "最近起点穿线",
                    value = state.lastStartFinishTimeLabel
                )
            }
            TimingSummaryField(
                modifier = Modifier.fillMaxWidth(),
                label = "状态",
                value = state.statusLabel
            )
        }
    }
}

@Composable
private fun TimingSummaryField(modifier: Modifier, label: String, value: String) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(text = label, style = MaterialTheme.typography.labelMedium)
        Text(text = value, fontWeight = FontWeight.SemiBold)
    }
}

data class StartFinishTimingCardState(
    val lastLapElapsedLabel: String,
    val currentLapElapsedLabel: String,
    val currentLapDistanceLabel: String,
    val lastStartFinishTimeLabel: String,
    val statusLabel: String
)

internal fun rememberStartFinishTimingCardState(lapSession: LapSession?): StartFinishTimingCardState {
    val acceptedStartFinishCrossings = lapSession
        ?.crossingEvents
        ?.filter { it.accepted && it.gateType == TimingGateType.StartFinish }
        .orEmpty()

    val latestAcceptedCrossing = acceptedStartFinishCrossings.lastOrNull()
        ?: return StartFinishTimingCardState(
            lastLapElapsedLabel = "--",
            currentLapElapsedLabel = formatElapsedMillis(0L),
            currentLapDistanceLabel = formatDistanceMeters(0.0),
            lastStartFinishTimeLabel = "--",
            statusLabel = "等待起点"
        )

    val previousAcceptedCrossing = acceptedStartFinishCrossings.dropLast(1).lastOrNull()
    val latestSampleTimestampMillis = lapSession?.samples?.lastOrNull()?.timestampMillis ?: latestAcceptedCrossing.timestampMillis
    val currentLapElapsedMillis = latestSampleTimestampMillis - latestAcceptedCrossing.timestampMillis

    return StartFinishTimingCardState(
        lastLapElapsedLabel = previousAcceptedCrossing
            ?.let { formatElapsedMillis(latestAcceptedCrossing.timestampMillis - it.timestampMillis) }
            ?: "--",
        currentLapElapsedLabel = formatElapsedMillis(currentLapElapsedMillis),
        currentLapDistanceLabel = formatDistanceMeters(
            calculateDistanceSince(lapSession?.samples.orEmpty(), latestAcceptedCrossing.timestampMillis)
        ),
        lastStartFinishTimeLabel = formatTimeOfDay(latestAcceptedCrossing.timestampMillis),
        statusLabel = "当前圈进行中"
    )
}

private fun calculateDistanceSince(samples: List<GpsSample>, crossingTimestampMillis: Long): Double {
    val samplesSinceCrossing = samples.filter { it.timestampMillis >= crossingTimestampMillis }
    if (samplesSinceCrossing.size < 2) return 0.0

    var totalMeters = 0.0
    for (index in 1 until samplesSinceCrossing.size) {
        val previous = samplesSinceCrossing[index - 1]
        val current = samplesSinceCrossing[index]
        totalMeters += haversineDistanceMeters(
            previous.latitude,
            previous.longitude,
            current.latitude,
            current.longitude
        )
    }
    return totalMeters
}

private fun haversineDistanceMeters(
    startLatitude: Double,
    startLongitude: Double,
    endLatitude: Double,
    endLongitude: Double
): Double {
    val earthRadiusMeters = 6_371_000.0
    val latitudeDelta = Math.toRadians(endLatitude - startLatitude)
    val longitudeDelta = Math.toRadians(endLongitude - startLongitude)
    val startLatitudeRadians = Math.toRadians(startLatitude)
    val endLatitudeRadians = Math.toRadians(endLatitude)

    val a = kotlin.math.sin(latitudeDelta / 2).let { it * it } +
        kotlin.math.cos(startLatitudeRadians) * kotlin.math.cos(endLatitudeRadians) *
        kotlin.math.sin(longitudeDelta / 2).let { it * it }
    val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
    return earthRadiusMeters * c
}

private fun formatDistanceMeters(distanceMeters: Double): String =
    String.format(Locale.US, "%.1f m", distanceMeters)

private fun formatTimeOfDay(timestampMillis: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestampMillis))

internal fun formatTelemetryValue(value: Double, unit: String): String =
    String.format("%.2f %s", value, unit)

internal fun formatElapsedMillis(value: Long): String =
    String.format(Locale.US, "%.3f s", value / 1000.0)
