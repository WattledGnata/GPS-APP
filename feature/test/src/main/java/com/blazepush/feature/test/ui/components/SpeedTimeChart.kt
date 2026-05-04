package com.blazepush.feature.test.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.blazepush.core.domain.model.LapTelemetrySample
import com.blazepush.feature.test.ui.tracktech.MetricKind
import com.blazepush.feature.test.ui.tracktech.MetricNumber
import com.blazepush.feature.test.ui.tracktech.MetricSize
import com.blazepush.feature.test.ui.tracktech.TrackTechColors
import com.blazepush.feature.test.ui.tracktech.TrackTechSemantic
import com.blazepush.feature.test.ui.tracktech.TrackTechTypography
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal enum class ChartAxis { SPEED, ACCEL }

internal data class ChartBounds(
    val minVal: Double,
    val maxVal: Double,
    val lapDurationMs: Long,
)

internal fun computeChartBounds(samples: List<LapTelemetrySample>, axis: ChartAxis): ChartBounds {
    val values = when (axis) {
        ChartAxis.SPEED -> samples.map { it.speedKmh }
        ChartAxis.ACCEL -> samples.mapNotNull { it.accelerationG }
    }
    if (values.isEmpty()) return ChartBounds(0.0, 1.0, 1L)
    val minVal = values.min()
    val maxVal = values.max()
    val range = max(maxVal - minVal, 1.0)
    val lapDurationMs = if (samples.size >= 2) {
        samples.last().elapsedMsInLap - samples.first().elapsedMsInLap
    } else 1L
    return ChartBounds(minVal - range * 0.05, maxVal + range * 0.05, max(lapDurationMs, 1L))
}

internal fun computeChartCoordinates(
    samples: List<LapTelemetrySample>,
    canvasSize: Size,
    axis: ChartAxis,
): List<Offset> {
    if (samples.isEmpty()) return emptyList()
    val bounds = computeChartBounds(samples, axis)
    val valRange = bounds.maxVal - bounds.minVal
    return samples.map { sample ->
        val x = if (bounds.lapDurationMs > 0) {
            (sample.elapsedMsInLap.toFloat() / bounds.lapDurationMs) * canvasSize.width
        } else 0f
        val rawVal = when (axis) {
            ChartAxis.SPEED -> sample.speedKmh
            ChartAxis.ACCEL -> sample.accelerationG ?: 0.0
        }
        val y = if (valRange > 0) {
            canvasSize.height - ((rawVal - bounds.minVal) / valRange * canvasSize.height).toFloat()
        } else canvasSize.height / 2f
        Offset(x, y)
    }
}

internal fun findNearestSampleIndex(samples: List<LapTelemetrySample>, targetElapsedMs: Long): Int {
    if (samples.isEmpty()) return -1
    if (samples.size == 1) return 0
    var lo = 0
    var hi = samples.size - 1
    while (lo < hi) {
        val mid = (lo + hi) / 2
        if (samples[mid].elapsedMsInLap < targetElapsedMs) lo = mid + 1 else hi = mid
    }
    if (lo > 0 && abs(samples[lo].elapsedMsInLap - targetElapsedMs) > abs(samples[lo - 1].elapsedMsInLap - targetElapsedMs)) {
        return lo - 1
    }
    return lo
}

@Composable
fun SpeedTimeChart(
    samples: List<LapTelemetrySample>,
    cursorAbsoluteTs: Long?,
    onCursorChange: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (samples.isEmpty()) {
        Box(modifier) {
            Text(
                "NO DATA",
                style = TrackTechTypography.ScoreSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = TrackTechColors.TextMuted,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        return
    }

    val cursorSample = remember(cursorAbsoluteTs, samples) {
        if (cursorAbsoluteTs != null) samples.find { it.absoluteTsMs == cursorAbsoluteTs } else null
    }

    Box(modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(samples) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        val touchX = change.position.x
                        val lapDurationMs = if (samples.size >= 2) {
                            samples.last().elapsedMsInLap - samples.first().elapsedMsInLap
                        } else 1L
                        val touchElapsedMs = (touchX / size.width * lapDurationMs).toLong()
                            .coerceIn(0, lapDurationMs)
                        val idx = findNearestSampleIndex(samples, touchElapsedMs)
                        if (idx >= 0) onCursorChange(samples[idx].absoluteTsMs)
                    }
                }
                .pointerInput(samples) {
                    detectTapGestures { offset ->
                        val touchX = offset.x
                        val lapDurationMs = if (samples.size >= 2) {
                            samples.last().elapsedMsInLap - samples.first().elapsedMsInLap
                        } else 1L
                        val touchElapsedMs = (touchX / size.width * lapDurationMs).toLong()
                            .coerceIn(0, lapDurationMs)
                        val idx = findNearestSampleIndex(samples, touchElapsedMs)
                        if (idx >= 0) onCursorChange(samples[idx].absoluteTsMs)
                    }
                }
        ) {
            val coords = computeChartCoordinates(samples, size, ChartAxis.SPEED)
            if (coords.size >= 2) {
                val path = Path().apply {
                    moveTo(coords[0].x, coords[0].y)
                    for (i in 1 until coords.size) {
                        lineTo(coords[i].x, coords[i].y)
                    }
                }
                drawPath(path, TrackTechSemantic.TelemetryLine, style = Stroke(width = 2f))
            }

            if (cursorAbsoluteTs != null) {
                val cursorIdx = samples.indexOfFirst { it.absoluteTsMs == cursorAbsoluteTs }
                if (cursorIdx >= 0 && cursorIdx < coords.size) {
                    val cursorX = coords[cursorIdx].x
                    drawLine(TrackTechColors.Purple, Offset(cursorX, 0f), Offset(cursorX, size.height), strokeWidth = 1f)
                }
            }
        }

        if (cursorSample != null) {
            MetricNumber(
                value = "${cursorSample.speedKmh.toInt()}",
                kind = MetricKind.Mechanical,
                size = MetricSize.Small,
                unit = "km/h",
            )
        }
    }
}
