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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.blazepush.feature.test.usecase.GaugeMath
import kotlin.math.cos
import kotlin.math.sin

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

    Box(
        modifier = modifier.size(diameter),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(diameter)) {
            val w = size.width
            val h = size.height
            val cx = w / 2f
            val cy = h / 2f
            val radius = minOf(w, h) / 2f
            val center = Offset(cx, cy)

            // 表盘底（半透明圆）
            drawCircle(color = dialColor, radius = radius, center = center)
            // 外环
            drawCircle(
                color = TrackTechColors.Border,
                radius = radius,
                center = center,
                style = Stroke(width = 2f),
            )

            // 刻度：主刻度每 20km/h、次刻度每 10km/h
            val majorStep = 20.0
            val minorStep = 10.0
            val tickOuter = radius - 4f
            val tickMajorLen = radius * 0.16f
            val tickMinorLen = radius * 0.08f
            var v = 0.0
            while (v <= maxSpeedKmh + 1e-6) {
                val isMajor = (v % majorStep) < 1e-6
                val angleDeg = GaugeMath.speedToNeedleAngle(v, maxKmh = maxSpeedKmh)
                val rad = Math.toRadians(angleDeg)
                val cosA = cos(rad).toFloat()
                val sinA = sin(rad).toFloat()
                val len = if (isMajor) tickMajorLen else tickMinorLen
                val pOuter = Offset(cx + cosA * tickOuter, cy + sinA * tickOuter)
                val pInner = Offset(cx + cosA * (tickOuter - len), cy + sinA * (tickOuter - len))
                drawLine(
                    color = if (isMajor) tickMajorColor else tickMinorColor,
                    start = pInner,
                    end = pOuter,
                    strokeWidth = if (isMajor) 2.5f else 1.5f,
                )
                // 主刻度数字标注（往内一点）
                if (isMajor) {
                    val labelR = tickOuter - tickMajorLen - radius * 0.12f
                    val lx = cx + cosA * labelR
                    val ly = cy + sinA * labelR
                    drawContext.canvas.nativeCanvas.apply {
                        val paint = android.graphics.Paint().apply {
                            color = labelColor.toArgbInt()
                            textSize = radius * 0.16f
                            textAlign = android.graphics.Paint.Align.CENTER
                            isAntiAlias = true
                        }
                        // 垂直居中：baseline 偏移
                        drawText(
                            (v.toInt()).toString(),
                            lx,
                            ly + paint.textSize / 3f,
                            paint,
                        )
                    }
                }
                v += minorStep
            }

            // 指针
            val needleAngleDeg = GaugeMath.speedToNeedleAngle(speed, maxKmh = maxSpeedKmh)
            val needleRad = Math.toRadians(needleAngleDeg)
            val needleLen = radius * 0.72f
            val tip = Offset(
                cx + cos(needleRad).toFloat() * needleLen,
                cy + sin(needleRad).toFloat() * needleLen,
            )
            // 尾翼（指针反向短段，平衡视觉）
            val tailLen = radius * 0.18f
            val tail = Offset(
                cx - cos(needleRad).toFloat() * tailLen,
                cy - sin(needleRad).toFloat() * tailLen,
            )
            drawLine(
                color = needleColor,
                start = tail,
                end = tip,
                strokeWidth = 4f,
                cap = StrokeCap.Round,
            )
            // 中心轴
            drawCircle(color = hubColor, radius = radius * 0.06f, center = center)
            drawCircle(color = needleColor, radius = radius * 0.03f, center = center)
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

/** Compose Color → Android argb int（nativeCanvas drawText 用）。 */
private fun Color.toArgbInt(): Int {
    val a = (alpha * 255f + 0.5f).toInt() shl 24
    val r = (red * 255f + 0.5f).toInt() shl 16
    val g = (green * 255f + 0.5f).toInt() shl 8
    val b = (blue * 255f + 0.5f).toInt()
    return a or r or g or b
}
