package com.blazepush.feature.test.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

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

        Spacer(modifier = Modifier.weight(1f))

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
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(text = "起终线计时", fontWeight = FontWeight.Bold)
            Text(text = state.statusLabel)
            Text(
                text = state.currentLapElapsedLabel,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(text = "上一圈: ${state.lastLapElapsedLabel}")
            Text(text = "当前圈距离: ${state.currentLapDistanceLabel}")
            Text(text = "最近起终线: ${state.lastStartFinishTimeLabel}")
        }
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
            currentLapElapsedLabel = "0.000 s",
            currentLapDistanceLabel = "0.0 m",
            lastStartFinishTimeLabel = "--",
            statusLabel = "等待起点"
        )

    val previousAcceptedCrossing = acceptedStartFinishCrossings.dropLast(1).lastOrNull()
    val latestSampleTimestamp = lapSession?.samples?.lastOrNull()?.timestampMillis ?: latestAcceptedCrossing.timestampMillis

    return StartFinishTimingCardState(
        lastLapElapsedLabel = previousAcceptedCrossing
            ?.let { formatElapsedMillis(latestAcceptedCrossing.timestampMillis - it.timestampMillis) }
            ?: "--",
        currentLapElapsedLabel = formatElapsedMillis(latestSampleTimestamp - latestAcceptedCrossing.timestampMillis),
        currentLapDistanceLabel = formatDistanceMeters(
            calculateDistanceSince(
                samples = lapSession?.samples.orEmpty(),
                sinceTimestampMillis = latestAcceptedCrossing.timestampMillis
            )
        ),
        lastStartFinishTimeLabel = formatTimeOfDay(latestAcceptedCrossing.timestampMillis),
        statusLabel = "当前圈进行中"
    )
}

internal fun formatTelemetryValue(value: Double, unit: String): String =
    String.format("%.2f %s", value, unit)

internal fun formatElapsedMillis(value: Long): String = String.format("%.3f s", value / 1000.0)

internal fun calculateDistanceSince(samples: List<GpsSample>, sinceTimestampMillis: Long): Double {
    val relevantSamples = samples.filter { it.timestampMillis >= sinceTimestampMillis }
    if (relevantSamples.size < 2) return 0.0

    var totalMeters = 0.0
    for (index in 1 until relevantSamples.size) {
        totalMeters += haversineDistanceMeters(relevantSamples[index - 1], relevantSamples[index])
    }
    return totalMeters
}

private fun haversineDistanceMeters(start: GpsSample, end: GpsSample): Double {
    val earthRadiusMeters = 6_371_000.0
    val lat1 = Math.toRadians(start.latitude)
    val lat2 = Math.toRadians(end.latitude)
    val deltaLat = Math.toRadians(end.latitude - start.latitude)
    val deltaLon = Math.toRadians(end.longitude - start.longitude)

    val a = kotlin.math.sin(deltaLat / 2) * kotlin.math.sin(deltaLat / 2) +
        kotlin.math.cos(lat1) * kotlin.math.cos(lat2) *
        kotlin.math.sin(deltaLon / 2) * kotlin.math.sin(deltaLon / 2)
    val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
    return earthRadiusMeters * c
}

internal fun formatDistanceMeters(value: Double): String = String.format("%.1f m", value)

internal fun formatTimeOfDay(timestampMillis: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestampMillis))
