// @IgnoreFormatCheck
package com.blazepush.feature.test.ui.tracktech

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.blazepush.feature.test.usecase.GaugeMath
import kotlin.math.sqrt

/**
 * G 球摩擦圆（friction circle / G-ball），替换 overlay 原 G 值数字角标。
 *
 * round redo-video-overlay-visual-gauges：Canvas 绘外圈（±[GaugeMath.GBALL_MAX_G] 映射半径边界）+
 * 十字 xy 轴（横轴=横向 G/过弯、纵轴=纵向 G/加速制动）+ 同心圈刻度（0.5/1.0/1.5G）+ 动点。
 * 坐标映射纯函数 [GaugeMath.gForceToBallOffset] 单测覆盖，纵向 G 朝向惯例：**加速点向上、制动向下**。
 *
 * @param latG     横向 G（过弯）
 * @param lonG     纵向 G（加速正 / 制动负）
 * @param diameter 摩擦圆控件直径（默认 120dp）
 */
@Composable
fun GForceBall(
    latG: Double?,
    lonG: Double?,
    modifier: Modifier = Modifier,
    diameter: Dp = 120.dp,
) {
    val outerColor = TrackTechColors.Border
    val ringColor = TrackTechColors.BorderAlpha60
    val axisColor = TrackTechColors.TextMuted
    val dialColor = TrackTechColors.Surface.copy(alpha = 0.55f)
    // 合成 G 越大动点越偏红（接近抓地极限警示）
    val lat = latG ?: 0.0
    val lon = lonG ?: 0.0
    val gMag = sqrt(lat * lat + lon * lon)
    val dotColor = if (gMag >= GaugeMath.GBALL_MAX_G * 0.8) TrackTechColors.Red else TrackTechColors.Cyan

    Box(
        modifier = modifier.size(diameter),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(diameter)) {
            val w = size.width
            val h = size.height
            val cx = w / 2f
            val cy = h / 2f
            val radius = minOf(w, h) / 2f - 2f
            val center = Offset(cx, cy)

            // 表盘底
            drawCircle(color = dialColor, radius = radius, center = center)

            // 同心刻度圈：0.5G / 1.0G / 1.5G（1.5G = 外圈边界）
            val ringFractions = listOf(0.5 / GaugeMath.GBALL_MAX_G, 1.0 / GaugeMath.GBALL_MAX_G, 1.0)
            ringFractions.forEachIndexed { idx, f ->
                drawCircle(
                    color = if (idx == ringFractions.lastIndex) outerColor else ringColor,
                    radius = (radius * f).toFloat(),
                    center = center,
                    style = Stroke(width = if (idx == ringFractions.lastIndex) 2f else 1f),
                )
            }

            // 十字轴
            drawLine(
                color = axisColor,
                start = Offset(cx - radius, cy),
                end = Offset(cx + radius, cy),
                strokeWidth = 1f,
            )
            drawLine(
                color = axisColor,
                start = Offset(cx, cy - radius),
                end = Offset(cx, cy + radius),
                strokeWidth = 1f,
            )

            // 动点：纯函数映射归一化偏移 → 像素
            val (nx, ny) = GaugeMath.gForceToBallOffset(lat, lon)
            val dot = Offset(cx + nx.toFloat() * radius, cy + ny.toFloat() * radius)
            // 动点光晕 + 实心点
            drawCircle(color = dotColor.copy(alpha = 0.3f), radius = radius * 0.16f, center = dot)
            drawCircle(color = dotColor, radius = radius * 0.08f, center = dot)
        }

        // 合成 G 数值小字（底部，纯数字读数 + 单位 G）
        Box(
            modifier = Modifier.size(diameter),
            contentAlignment = Alignment.BottomCenter,
        ) {
            val display = if (latG == null && lonG == null) "--" else "%.1f".format(gMag)
            Text(
                text = "$display G",
                style = TrackTechTypography.UiTextSmall,
                color = dotColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
