// @IgnoreFormatCheck
package com.blazepush.feature.test.ui.tracktech

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.blazepush.feature.test.overlay.OverlayCanvasPainter
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

    // 共享绘制层颜色容器（真相源仍是 TrackTechColors）。
    // round video-export-burned-overlay Round A：Canvas 块下沉到 OverlayCanvasPainter，回放端薄壳。
    val paints = remember(dotColor) {
        OverlayCanvasPainter.GForcePaints(
            dialColor = dialColor.toArgb(),
            outerColor = outerColor.toArgb(),
            ringColor = ringColor.toArgb(),
            axisColor = axisColor.toArgb(),
            dotColor = dotColor.toArgb(),
        )
    }

    Box(
        modifier = modifier.size(diameter),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(diameter)) {
            val w = size.width
            val h = size.height
            drawIntoCanvas { c ->
                OverlayCanvasPainter.drawGForceBall(
                    canvas = c.nativeCanvas,
                    cx = w / 2f,
                    cy = h / 2f,
                    radius = minOf(w, h) / 2f - 2f,
                    latG = lat,
                    lonG = lon,
                    paints = paints,
                )
            }
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
