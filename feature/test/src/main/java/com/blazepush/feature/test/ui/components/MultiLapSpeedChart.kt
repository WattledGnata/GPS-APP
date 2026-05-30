// @IgnoreFormatCheck
package com.blazepush.feature.test.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import com.blazepush.core.domain.model.LapTelemetrySample
import com.blazepush.feature.test.ui.tracktech.TrackTechColors
import com.blazepush.feature.test.ui.tracktech.TrackTechTypography
import kotlin.math.abs
import kotlin.math.max

/**
 * 多圈 speed 叠加组件（M3 lap-comparison-screen-with-cursor，time-axis 第一刀）。
 *
 * Decision New-Component：**新建**独立 Composable，**MUST NOT** 改单圈 SpeedTimeChart /
 * AccelTimeChart / SectorBar / TrackPolylineMap 的任何签名（避免 M2 详情屏回归 + v3 #16）。
 *
 * Decision X-Axis：X 轴 = 圈内流逝时间 `elapsedMsInLap`（time-axis），各圈按各自圈内流逝时间叠加；
 * **MUST NOT** 引入距离对齐 use case / 距离 grid 映射 / 距离重采样（距离轴留 follow-up round）。
 *
 * Decision Cursor：游标 identity = 一个 `cursorElapsedMs: Long`（跨圈共享标量，**非 absoluteTsMs**
 * —— 跨圈 absoluteTsMs 不同域永远 miss）；每个 LapSeries 用其自己 samples 求 `elapsedMsInLap`
 * 最近邻 sample 高亮。
 *
 * Decision X-Y-Scale：统一 X 尺度（maxElapsedMs = 最长圈）+ 统一 Y 尺度（全局 speed min/max + 5% padding），
 * 各圈在同一尺度下可比。
 *
 * @author CC
 * @description multi-lap speed overlay chart with shared elapsed-time cursor
 * @date 2026-05-30
 */
internal data class LapSeries(
    val lapNumber: Int,
    val color: Color,
    val samples: List<LapTelemetrySample>,
)

internal data class MultiLapBounds(
    val maxElapsedMs: Long,
    val speedMin: Double,
    val speedMax: Double,
)

/**
 * 纯函数：跨所有 series 算统一 X/Y 尺度（无 androidx 依赖，可 JVM 单测）。
 *
 * - maxElapsedMs = 所有 series 所有 sample 的 `elapsedMsInLap` 全局 max（短圈曲线右端提前结束）；
 *   全空 / 全 0 退化 1L（避免除零）。
 * - speedMin/speedMax = 所有 series 所有 sample 的 `speedKmh` 全局 min/max + 5% padding
 *   （mirror SpeedTimeChart.computeChartBounds 思路，但跨 series 全局）；无样本退化 [0.0, 1.0]。
 */
internal fun computeMultiLapBounds(series: List<LapSeries>): MultiLapBounds {
    val allSamples = series.flatMap { it.samples }
    if (allSamples.isEmpty()) return MultiLapBounds(1L, 0.0, 1.0)
    val maxElapsedMs = max(allSamples.maxOf { it.elapsedMsInLap }, 1L)
    val speeds = allSamples.map { it.speedKmh }
    val minVal = speeds.min()
    val maxVal = speeds.max()
    val range = max(maxVal - minVal, 1.0)
    return MultiLapBounds(
        maxElapsedMs = maxElapsedMs,
        speedMin = minVal - range * 0.05,
        speedMax = maxVal + range * 0.05,
    )
}

/**
 * 纯函数：对某圈 samples（按 elapsedMsInLap 升序）求 targetElapsedMs 的最近邻 sample。
 *
 * mirror SpeedTimeChart.findNearestSampleIndex 二分思路，但返回 sample 而非 index。
 * - 空 samples → null
 * - 单 sample → 该 sample
 * - target 超出范围 → clamp 端点（二分自然返回最近端点）
 *
 * 每圈各自最近邻（Decision Cursor）：不同圈在同一 cursorElapsedMs 下取各自圈内最近那一帧。
 */
internal fun nearestSampleByElapsed(
    samples: List<LapTelemetrySample>,
    targetElapsedMs: Long,
): LapTelemetrySample? {
    if (samples.isEmpty()) return null
    if (samples.size == 1) return samples[0]
    var lo = 0
    var hi = samples.size - 1
    while (lo < hi) {
        val mid = (lo + hi) / 2
        if (samples[mid].elapsedMsInLap < targetElapsedMs) lo = mid + 1 else hi = mid
    }
    if (lo > 0 &&
        abs(samples[lo].elapsedMsInLap - targetElapsedMs) >
        abs(samples[lo - 1].elapsedMsInLap - targetElapsedMs)
    ) {
        return samples[lo - 1]
    }
    return samples[lo]
}

/**
 * 单 series 的 canvas 坐标（X 用 elapsedMsInLap / maxElapsedMs，Y 用 speedKmh 在 [speedMin, speedMax] 归一）。
 * 抽出便于游标高亮点复用同一映射（与曲线点严格对齐）。
 */
private fun seriesCoordinates(
    samples: List<LapTelemetrySample>,
    bounds: MultiLapBounds,
    canvasSize: Size,
): List<Offset> {
    if (samples.isEmpty()) return emptyList()
    val valRange = bounds.speedMax - bounds.speedMin
    return samples.map { sample ->
        val x = if (bounds.maxElapsedMs > 0) {
            (sample.elapsedMsInLap.toFloat() / bounds.maxElapsedMs) * canvasSize.width
        } else 0f
        val y = if (valRange > 0) {
            canvasSize.height - ((sample.speedKmh - bounds.speedMin) / valRange * canvasSize.height).toFloat()
        } else canvasSize.height / 2f
        Offset(x, y)
    }
}

@Composable
internal fun MultiLapSpeedChart(
    series: List<LapSeries>,
    cursorElapsedMs: Long?,
    onCursorChange: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    // series 空 / 全空 samples → 占位（与单圈 SpeedTimeChart 同语义，避免空 canvas / 除零）。
    val hasData = series.any { it.samples.isNotEmpty() }
    if (!hasData) {
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

    val bounds = computeMultiLapBounds(series)

    Box(modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(series) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        val touchX = change.position.x
                        val touchElapsedMs = (touchX / size.width * bounds.maxElapsedMs).toLong()
                            .coerceIn(0, bounds.maxElapsedMs)
                        onCursorChange(touchElapsedMs)
                    }
                }
                .pointerInput(series) {
                    detectTapGestures { offset ->
                        val touchX = offset.x
                        val touchElapsedMs = (touchX / size.width * bounds.maxElapsedMs).toLong()
                            .coerceIn(0, bounds.maxElapsedMs)
                        onCursorChange(touchElapsedMs)
                    }
                }
        ) {
            // 每 series 画一条 polyline，用 series.color（各圈一色）。
            series.forEach { lap ->
                val coords = seriesCoordinates(lap.samples, bounds, size)
                if (coords.size >= 2) {
                    val path = Path().apply {
                        moveTo(coords[0].x, coords[0].y)
                        for (i in 1 until coords.size) {
                            lineTo(coords[i].x, coords[i].y)
                        }
                    }
                    drawPath(path, lap.color, style = Stroke(width = 2f))
                }
            }

            // 游标竖线（中性紫）+ 每圈在最近邻 sample 处画高亮点（各圈各自最近邻）。
            if (cursorElapsedMs != null) {
                val cursorX = (cursorElapsedMs.toFloat() / bounds.maxElapsedMs) * size.width
                drawLine(
                    TrackTechColors.Purple,
                    Offset(cursorX, 0f),
                    Offset(cursorX, size.height),
                    strokeWidth = 1f,
                )
                series.forEach { lap ->
                    val nearest = nearestSampleByElapsed(lap.samples, cursorElapsedMs)
                    if (nearest != null) {
                        val valRange = bounds.speedMax - bounds.speedMin
                        val px = (nearest.elapsedMsInLap.toFloat() / bounds.maxElapsedMs) * size.width
                        val py = if (valRange > 0) {
                            size.height -
                                ((nearest.speedKmh - bounds.speedMin) / valRange * size.height).toFloat()
                        } else size.height / 2f
                        drawCircle(lap.color, radius = 4f, center = Offset(px, py))
                    }
                }
            }
        }
    }
}
