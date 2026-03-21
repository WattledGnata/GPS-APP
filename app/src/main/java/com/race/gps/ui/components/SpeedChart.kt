package com.race.gps.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.race.gps.domain.model.GpsDataPoint
import kotlin.math.abs

/**
 * 速度曲线图
 * 显示测试过程中速度随时间的变化
 */
@Composable
fun SpeedChart(
    dataPoints: List<GpsDataPoint>,
    modifier: Modifier = Modifier,
    lineColor: Color = Color(0xFF00F0FF)
) {
    if (dataPoints.isEmpty()) {
        Card(modifier = modifier) {
            Box(
                modifier = Modifier.fillMaxWidth().height(200.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("无数据", color = Color.Gray)
            }
        }
        return
    }

    val maxTime = (dataPoints.maxOf { it.elapsedTime } * 1000).toInt()
    val maxSpeed = dataPoints.maxOf { it.speed }.toFloat()

    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("速度曲线", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(
                    "最高 %.0f km/h".format(maxSpeed),
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

/**
 * G值曲线图
 * 显示测试过程中加速度随时间的变化
 */
@Composable
fun GForceChart(
    dataPoints: List<GpsDataPoint>,
    modifier: Modifier = Modifier,
    lineColor: Color = Color(0xFF00FF78)
) {
    if (dataPoints.isEmpty()) {
        Card(modifier = modifier) {
            Box(
                modifier = Modifier.fillMaxWidth().height(200.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("无数据", color = Color.Gray)
            }
        }
        return
    }

    // 计算G值
    val gForcePoints = dataPoints.mapIndexed { index, point ->
        val gForce = if (index > 0) {
            val prev = dataPoints[index - 1]
            val timeDiff = point.elapsedTime - prev.elapsedTime
            if (timeDiff > 0) {
                (point.speed - prev.speed) / (timeDiff * 9.8)
            } else 0.0
        } else 0.0
        Pair(point.elapsedTime, gForce)
    }

    val maxTime = (dataPoints.maxOf { it.elapsedTime } * 1000).toInt()
    val maxG = gForcePoints.map { abs(it.second) }.max().toFloat().coerceAtLeast(0.5f)

    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp)) {
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
                val centerY = height / 2

                // 绘制中心线 (G=0)
                drawLine(
                    color = Color.Gray.copy(alpha = 0.3f),
                    start = Offset(padding, centerY),
                    end = Offset(width - padding, centerY),
                    strokeWidth = 1f
                )

                // 绘制网格
                for (ratio in listOf(0.25f, 0.5f, 0.75f)) {
                    drawLine(
                        color = Color.Gray.copy(alpha = 0.1f),
                        start = Offset(padding, centerY - chartHeight * ratio / 2),
                        end = Offset(width - padding, centerY - chartHeight * ratio / 2),
                        strokeWidth = 1f
                    )
                    drawLine(
                        color = Color.Gray.copy(alpha = 0.1f),
                        start = Offset(padding, centerY + chartHeight * ratio / 2),
                        end = Offset(width - padding, centerY + chartHeight * ratio / 2),
                        strokeWidth = 1f
                    )
                }

                // 绘制G值曲线
                if (gForcePoints.size > 1) {
                    val path = Path()

                    gForcePoints.forEachIndexed { index, pair ->
                        val x = padding + (pair.first * 1000f / maxTime).toFloat() * chartWidth
                        val y = centerY - (pair.second.toFloat() / (maxG * 2)) * chartHeight

                        if (index == 0) {
                            path.moveTo(x, y)
                        } else {
                            path.lineTo(x, y)
                        }
                    }

                    drawPath(
                        path = path,
                        color = lineColor,
                        style = Stroke(width = 2f)
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
