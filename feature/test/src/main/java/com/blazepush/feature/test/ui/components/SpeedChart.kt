// @IgnoreFormatCheck
package com.blazepush.feature.test.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blazepush.core.domain.model.GpsDataPoint
import com.blazepush.core.domain.usecase.AccelerationSmoother
import com.blazepush.core.domain.usecase.GRAVITY_MS2
import com.blazepush.core.domain.usecase.TimedSpeedSample

/**
 * 速度曲线图
 * 显示测试过程中速度随时间的变化
 *
 * @param wrapInCard 默认 true 保持向下兼容（现有调用方零改动），渲染 Material `Card { Column {...} }`；
 * 当 V2 详情页等外层已用 `CutCornerPanel` 时传 false，避免双层卡。stroke / grid / axis 颜色不动。
 */
@Composable
fun SpeedChart(
    dataPoints: List<GpsDataPoint>,
    modifier: Modifier = Modifier,
    lineColor: Color = Color(0xFF00F0FF),
    wrapInCard: Boolean = true,
    templateMaxSpeedKmh: Double? = null,
) {
    if (dataPoints.isEmpty()) {
        SpeedChartShell(modifier = modifier, wrapInCard = wrapInCard) {
            Box(
                modifier = Modifier.fillMaxWidth().height(200.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("无数据", color = Color.Gray)
            }
        }
        return
    }

    val maxTime = (dataPoints.maxOf { it.elapsedTime } * 1000).toInt().coerceAtLeast(1)
    val speeds = dataPoints.map { it.speed }
    val scale = calculateSpeedChartScale(speeds, templateMaxSpeedKmh)
    val maxSpeed = scale.axisMaxKmh.toFloat().coerceAtLeast(1f)

    SpeedChartShell(modifier = modifier, wrapInCard = wrapInCard) {
        Column(modifier = if (wrapInCard) Modifier.padding(16.dp) else Modifier) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("速度曲线", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(
                    "最高 %.0f km/h".format(scale.actualMaxKmh),
                    fontSize = 12.sp,
                    color = Color.Gray
                )
            }

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .padding(top = 8.dp)
            ) {
                val width = size.width
                val height = size.height
                val padding = 40f

                val chartWidth = width - padding * 2
                val chartHeight = height - padding * 2

                // 绘制网格线
                for (i in 0..4) {
                    val y = padding + chartHeight * i / 4
                    drawLine(
                        color = Color.Gray.copy(alpha = 0.2f),
                        start = Offset(padding, y),
                        end = Offset(width - padding, y),
                        strokeWidth = 1f
                    )
                }

                // 绘制速度曲线
                if (dataPoints.size > 1) {
                    val path = Path()

                    dataPoints.forEachIndexed { index, point ->
                        val x = padding + (point.elapsedTime * 1000f / maxTime).toFloat() * chartWidth
                        val y = padding + chartHeight - (point.speed.toFloat() / maxSpeed) * chartHeight

                        if (index == 0) {
                            path.moveTo(x, y)
                        } else {
                            path.lineTo(x, y)
                        }
                    }

                    // 绘制填充区域
                    val fillPath = Path().apply {
                        addPath(path)
                        lineTo(padding + chartWidth, padding + chartHeight)
                        lineTo(padding, padding + chartHeight)
                        close()
                    }
                    drawPath(
                        path = fillPath,
                        color = lineColor.copy(alpha = 0.15f)
                    )

                    // 绘制线条
                    drawPath(
                        path = path,
                        color = lineColor,
                        style = Stroke(width = 2.5f)
                    )
                }
            }

            // 时间轴标签
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 40.dp, end = 40.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("0s", fontSize = 10.sp, color = Color.Gray)
                Text("%.1fs".format(maxTime / 1000f), fontSize = 10.sp, color = Color.Gray)
            }
        }
    }
}

internal data class SpeedChartScale(
    val actualMaxKmh: Double,
    val axisMaxKmh: Double,
)

internal fun calculateSpeedChartScale(
    speeds: List<Double>,
    templateMaxSpeedKmh: Double? = null,
): SpeedChartScale {
    val actual = speeds.filter { it.isFinite() }.maxOrNull()?.coerceAtLeast(0.0) ?: 0.0
    val axis = maxOf(actual, templateMaxSpeedKmh?.coerceAtLeast(0.0) ?: 0.0)
    return SpeedChartScale(actualMaxKmh = actual, axisMaxKmh = axis)
}

@Composable
private fun SpeedChartShell(
    modifier: Modifier,
    wrapInCard: Boolean,
    content: @Composable () -> Unit,
) {
    if (wrapInCard) {
        Card(modifier = modifier) { content() }
    } else {
        Box(modifier = modifier) { content() }
    }
}

/**
 * G值曲线图
 * 显示测试过程中加速度随时间的变化
 *
 * @param wrapInCard 默认 true 保持向下兼容；V2 详情页传 false 由外层 `CutCornerPanel` 承担容器。
 */
@Composable
fun GForceChart(
    dataPoints: List<GpsDataPoint>,
    maxAcceleration: Double = 0.0,
    modifier: Modifier = Modifier,
    lineColor: Color = Color(0xFF00FF78),
    wrapInCard: Boolean = true,
    mode: GForceChartMode = GForceChartMode.BIDIRECTIONAL,
) {
    if (dataPoints.isEmpty()) {
        SpeedChartShell(modifier = modifier, wrapInCard = wrapInCard) {
            Box(
                modifier = Modifier.fillMaxWidth().height(200.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("无数据", color = Color.Gray)
            }
        }
        return
    }

    // G 值序列：调 AccelerationSmoother 5 点 SG（与 CalculateResultUseCase 共用入口，
    // spec.md `Requirement: 离线 G 值统计与 UI 曲线 MUST 共用 AccelerationSmoother` 锁定）。
    // |G| > 3 时 clip 到 ±3 而非 drop（spec.md `GForceChart 边界值 MUST clip 而非 drop`），
    // 折线在边界处呈水平段（明显是"超出显示范围"的视觉信号），避免 drop 后相邻点直连产生 V 字断点。
    // remember(dataPoints) 包裹避免 Compose 重组重算（性能）。
    val gForcePoints = remember(
        dataPoints.size,
        dataPoints.firstOrNull()?.elapsedTime,
        dataPoints.lastOrNull()?.elapsedTime,
    ) {
        val samples = dataPoints.map { p ->
            TimedSpeedSample(
                // Math.round 避免浮点截断（IEEE 754 下 8.04 * 1000.0 = 8039.999... → toLong=8039 漂 -1ms）
                timestamp = Math.round(p.elapsedTime * 1000.0),
                speedKmh = p.speed,
            )
        }
        val accelMs2 = AccelerationSmoother.compute(samples)
        accelMs2.mapIndexed { i, a ->
            val g = (a / GRAVITY_MS2).coerceIn(-3.0, 3.0)
            Pair(dataPoints[i].elapsedTime, g)
        }
    }

    val maxTime = (dataPoints.maxOf { it.elapsedTime } * 1000).toInt()
    // 使用关键指标中传入的maxAcceleration作为显示值，保持与关键指标一致
    // fallback 改用 robustRange abs-G 上界：抗离群尖刺撑满 G 轴（robust-chart-yaxis-scaling round）
    val scale = calculateGForceChartScale(gForcePoints.map { it.second }, maxAcceleration, mode)
    val maxG = maxOf(kotlin.math.abs(scale.minG), kotlin.math.abs(scale.maxG)).toFloat()

    SpeedChartShell(modifier = modifier, wrapInCard = wrapInCard) {
        Column(modifier = if (wrapInCard) Modifier.padding(16.dp) else Modifier) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("G值曲线", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(
                    "最大 %.2f G".format(maxG),
                    fontSize = 12.sp,
                    color = Color.Gray
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 48.dp, end = 12.dp, top = 10.dp, bottom = 10.dp)
                ) {
                    val chartWidth = size.width
                    val chartHeight = size.height
                    val range = (scale.maxG - scale.minG).coerceAtLeast(0.01)

                    for (ratio in listOf(0f, 0.5f, 1f)) {
                        val y = chartHeight * ratio
                        drawLine(
                            color = Color.Gray.copy(alpha = 0.16f),
                            start = Offset(0f, y),
                            end = Offset(chartWidth, y),
                            strokeWidth = 1f,
                        )
                    }
                    val zeroY = ((scale.maxG / range) * chartHeight).toFloat().coerceIn(0f, chartHeight)
                    drawLine(
                        color = Color.Gray.copy(alpha = 0.38f),
                        start = Offset(0f, zeroY),
                        end = Offset(chartWidth, zeroY),
                        strokeWidth = 1.5f,
                    )

                    if (gForcePoints.size > 1) {
                        val path = Path()
                        gForcePoints.forEachIndexed { index, pair ->
                            val x = (pair.first * 1000f / maxTime).toFloat() * chartWidth
                            val value = pair.second.coerceIn(scale.minG, scale.maxG)
                            val y = ((scale.maxG - value) / range * chartHeight).toFloat()
                            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        drawPath(path = path, color = lineColor, style = Stroke(width = 2f))
                    }
                }
                GAxisLabel("%.2f G".format(scale.maxG), Modifier.align(Alignment.TopStart))
                GAxisLabel("%.2f G".format((scale.maxG + scale.minG) / 2.0), Modifier.align(Alignment.CenterStart))
                GAxisLabel("%.2f G".format(scale.minG), Modifier.align(Alignment.BottomStart))
            }

            // 时间轴标签
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 40.dp, end = 40.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("0s", fontSize = 10.sp, color = Color.Gray)
                Text("%.1fs".format(maxTime / 1000f), fontSize = 10.sp, color = Color.Gray)
            }
        }
    }
}

enum class GForceChartMode { ACCELERATION, BRAKING, BIDIRECTIONAL }

internal data class GForceChartScale(val minG: Double, val maxG: Double)

internal fun calculateGForceChartScale(
    values: List<Double>,
    highlightedPeakG: Double,
    mode: GForceChartMode,
): GForceChartScale {
    val finite = values.filter { it.isFinite() }
    val positivePeak = finite.maxOrNull()?.coerceAtLeast(0.0) ?: 0.0
    val negativePeak = finite.minOrNull()?.let { kotlin.math.abs(minOf(it, 0.0)) } ?: 0.0
    val peak = maxOf(highlightedPeakG, positivePeak, negativePeak, 0.1)
    return when (mode) {
        GForceChartMode.ACCELERATION -> GForceChartScale(0.0, peak)
        GForceChartMode.BRAKING -> GForceChartScale(-peak, 0.0)
        GForceChartMode.BIDIRECTIONAL -> GForceChartScale(-peak, peak)
    }
}

@Composable
private fun GAxisLabel(text: String, modifier: Modifier) {
    Text(text = text, modifier = modifier, fontSize = 9.sp, color = Color.Gray)
}
