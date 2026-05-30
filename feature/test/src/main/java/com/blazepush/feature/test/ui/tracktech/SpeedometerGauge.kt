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

/**
 * 老式圆形指针速度表（赛车/汽车风格），替换 overlay 原 DSEG7 速度数字角标。
 *
 * round redo-video-overlay-visual-gauges：Canvas 绘表盘 + 主/次刻度 + 数字标注 + 指针随速度转动 +
 * 中心轴；量程/起始角/扫掠角全走 [GaugeMath] 常量（便于真机后调）。角度映射纯函数
 * [GaugeMath.speedToNeedleAngle] 单测覆盖。
 *
 * 中心读数（纯数字瞬时仪表读数）按 V2 视觉约束允许 DSEG7（MechanicalSmall）；单位 km/h 用小字标注。
 *
 * @param speedKmh      当前速度（km/h，null → 指针停在量程起点 + 读数 "--"）
 * @param maxSpeedKmh   量程上界（km/h，默认 [GaugeMath.SPEEDO_MAX_KMH]）；
 *                      建议用 [GaugeMath.speedGaugeMax] 按最高尾速动态计算后传入。
 * @param diameter      表盘直径（默认 120dp，别太大挡画面）
 */
@Composable
fun SpeedometerGauge(
    speedKmh: Double?,
    modifier: Modifier = Modifier,
    maxSpeedKmh: Double = GaugeMath.SPEEDO_MAX_KMH,
    diameter: Dp = 120.dp,
) {
    val speed = speedKmh ?: 0.0
    val dialColor = TrackTechColors.Surface.copy(alpha = 0.55f)
    val tickMajorColor = TrackTechColors.TextSecondary
    val tickMinorColor = TrackTechColors.TextMuted
    val labelColor = TrackTechColors.TextMuted
    // 指针：高速段（>=量程 70%）红色警示，否则 cyan
    val needleColor = if (speed >= maxSpeedKmh * 0.7) TrackTechColors.Red else TrackTechColors.Cyan
    val hubColor = TrackTechColors.TextPrimary

    // 共享绘制层颜色容器（从 V2 色号转 ARGB int，真相源仍是 TrackTechColors）。
    // round video-export-burned-overlay Round A：Canvas 块下沉到 OverlayCanvasPainter，回放端薄壳。
    val paints = remember(dialColor, needleColor) {
        OverlayCanvasPainter.SpeedometerPaints(
            dialColor = dialColor.toArgb(),
            borderColor = TrackTechColors.Border.toArgb(),
            tickMajorColor = tickMajorColor.toArgb(),
            tickMinorColor = tickMinorColor.toArgb(),
            labelColor = labelColor.toArgb(),
            needleColor = needleColor.toArgb(),
            hubColor = hubColor.toArgb(),
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
                OverlayCanvasPainter.drawSpeedometer(
                    canvas = c.nativeCanvas,
                    cx = w / 2f,
                    cy = h / 2f,
                    radius = minOf(w, h) / 2f,
                    speedKmh = speed,
                    maxSpeedKmh = maxSpeedKmh,
                    paints = paints,
                )
            }
        }

        // 中心/下方读数（纯数字仪表瞬时读数 → V2 允许 DSEG7）。放表盘下半部，不被指针轴遮挡。
        Box(
            modifier = Modifier.size(diameter),
            contentAlignment = Alignment.BottomCenter,
        ) {
            val display = speedKmh?.let { "%.0f".format(it) } ?: "--"
            Text(
                text = "$display km/h",
                style = TrackTechTypography.UiTextSmall,
                color = needleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
