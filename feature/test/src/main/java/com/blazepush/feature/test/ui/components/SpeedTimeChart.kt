// @IgnoreFormatCheck
package com.blazepush.feature.test.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import com.blazepush.feature.test.FileLogger
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

/**
 * IQR Tukey 抗离群 Y 轴范围纯函数（road-test-first round robust-chart-yaxis-scaling）。
 *
 * 算法：sorted → Q1=sorted[n/4], Q3=sorted[3n/4], IQR=Q3-Q1,
 *   lower=Q1-1.5·IQR, upper=Q3+1.5·IQR，再与真实 min/max 取交（不超出真实数据范围）。
 * fallback：values.size < 4 时退化 raw min/max（IQR 无意义）；空 list 返回哨兵 (0.0, 1.0)。
 * 无 Android 依赖，纯 JVM 可测。
 */
internal fun robustRange(values: List<Double>): Pair<Double, Double> {
    if (values.isEmpty()) return Pair(0.0, 1.0)
    val rawMin = values.min()
    val rawMax = values.max()
    if (values.size < 4) return Pair(rawMin, rawMax)
    val sorted = values.sorted()
    val n = sorted.size
    val q1 = sorted[n / 4]
    val q3 = sorted[3 * n / 4]
    val iqr = q3 - q1
    val lower = (q1 - 1.5 * iqr).coerceAtLeast(rawMin)
    val upper = (q3 + 1.5 * iqr).coerceAtMost(rawMax)
    return Pair(lower, upper)
}

internal fun computeChartBounds(samples: List<LapTelemetrySample>, axis: ChartAxis): ChartBounds {
    val values = when (axis) {
        ChartAxis.SPEED -> samples.map { it.speedKmh }
        ChartAxis.ACCEL -> samples.mapNotNull { it.accelerationG }
    }
    if (values.isEmpty()) return ChartBounds(0.0, 1.0, 1L)
    val (minVal, maxVal) = robustRange(values)
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
            (canvasSize.height - ((rawVal - bounds.minVal) / valRange * canvasSize.height).toFloat())
                .coerceIn(0f, canvasSize.height)
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
    // L1 R1 P0-1 / L1 R2 P0-R2-1 修订：扩展 isEmpty 守卫到 size <= 1，
    // 一次性 cover 三条路径 — chart line drawPath / cursor line drawLine / 触摸 callback。
    // n=1 时 lapDurationMs=1L → coords[0].x = elapsedMsInLap × canvasWidth / 1L 远超 canvas，
    // 走占位分支避免 silent canvas 外渲染（与 isEmpty 同语义合并）。
    if (samples.isEmpty() || samples.size == 1) {
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
                    // round fix-lap-detail-ux-three-touch-issues：水平方向锁定，
                    // 垂直方向不消费 → 父级 LazyColumn 接管上下滚动。
                    detectHorizontalDragGestures(
                        onDragStart = { FileLogger.v("Chart", "horizDrag start chart=Speed") },
                    ) { change, _ ->
                        // L1 R2 P0-R2-1 保险层：触摸 detector 内 size <= 1 守卫
                        if (samples.size <= 1) return@detectHorizontalDragGestures
                        change.consume()
                        val touchX = change.position.x
                        val lapDurationMs =
                            samples.last().elapsedMsInLap - samples.first().elapsedMsInLap
                        val touchElapsedMs = (touchX / size.width * lapDurationMs).toLong()
                            .coerceIn(0, lapDurationMs)
                        val idx = findNearestSampleIndex(samples, touchElapsedMs)
                        if (idx >= 0) onCursorChange(samples[idx].absoluteTsMs)
                    }
                }
                .pointerInput(samples) {
                    detectTapGestures { offset ->
                        if (samples.size <= 1) return@detectTapGestures
                        val touchX = offset.x
                        val lapDurationMs =
                            samples.last().elapsedMsInLap - samples.first().elapsedMsInLap
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
