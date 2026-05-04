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
import androidx.compose.ui.text.style.TextOverflow
import com.blazepush.core.domain.model.LapTelemetrySample
import com.blazepush.feature.test.ui.tracktech.MetricKind
import com.blazepush.feature.test.ui.tracktech.MetricNumber
import com.blazepush.feature.test.ui.tracktech.MetricSize
import com.blazepush.feature.test.ui.tracktech.TrackTechColors
import com.blazepush.feature.test.ui.tracktech.TrackTechSemantic
import com.blazepush.feature.test.ui.tracktech.TrackTechTypography
import kotlin.math.abs

@Composable
fun AccelTimeChart(
    samples: List<LapTelemetrySample>,
    cursorAbsoluteTs: Long?,
    onCursorChange: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (samples.isEmpty() || samples.all { it.accelerationG == null }) {
        Box(modifier) {
            Text(
                "NO ACCEL DATA",
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
            val coords = computeChartCoordinates(samples, size, ChartAxis.ACCEL)

            // Draw segments, breaking at null accelerationG samples
            var segmentStart = -1
            for (i in samples.indices) {
                if (samples[i].accelerationG != null) {
                    if (segmentStart < 0) segmentStart = i
                } else {
                    if (segmentStart >= 0) {
                        drawSegment(coords, segmentStart, i - 1)
                        segmentStart = -1
                    }
                }
            }
            if (segmentStart >= 0) {
                drawSegment(coords, segmentStart, samples.size - 1)
            }

            if (cursorAbsoluteTs != null) {
                val cursorIdx = samples.indexOfFirst { it.absoluteTsMs == cursorAbsoluteTs }
                if (cursorIdx >= 0 && cursorIdx < coords.size && samples[cursorIdx].accelerationG != null) {
                    val cursorX = coords[cursorIdx].x
                    drawLine(TrackTechColors.Purple, Offset(cursorX, 0f), Offset(cursorX, size.height), strokeWidth = 1f)
                }
            }
        }

        if (cursorSample?.accelerationG != null) {
            MetricNumber(
                value = cursorSample.accelerationG.toString(),
                kind = MetricKind.Mechanical,
                size = MetricSize.Small,
                unit = "G",
            )
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSegment(
    coords: List<Offset>,
    from: Int,
    to: Int,
) {
    if (from >= to || from < 0 || to >= coords.size) return
    val path = Path().apply {
        moveTo(coords[from].x, coords[from].y)
        for (i in (from + 1)..to) {
            lineTo(coords[i].x, coords[i].y)
        }
    }
    drawPath(path, TrackTechSemantic.TelemetryLine, style = Stroke(width = 2f))
}
