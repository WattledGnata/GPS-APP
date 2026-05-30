// @IgnoreFormatCheck
package com.blazepush.feature.test.ui.tracktech

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.blazepush.feature.test.model.track.GeoPoint
import kotlin.math.cos
import kotlin.math.max

/**
 * 赛道小地图：预置 Track 轮廓 polyline 等距矩形投影到 Compose Canvas + 当前位置高亮点。
 *
 * round video-overlay-realtime-playback Decision 3：几何就绪（Track.referencePath.points）+
 * 投影简单（等距矩形 + 等比缩放保比例，正北朝上）→ 一期做。MUST NOT 依赖地图库/底图瓦片/联网。
 *
 * 投影逻辑（[TrackMiniMapProjection]）是纯函数可单测；Composable 只负责 drawPath / drawCircle。
 *
 * @author CC
 * @description preset track outline minimap + current position dot for video overlay
 * @date 2026-05-31
 */
object TrackMiniMapProjection {

    /**
     * 投影结果：polyline 各点画布坐标 + 当前点画布坐标。
     *
     * @property polyline 赛道轮廓各点的画布 (x, y)
     * @property current  当前位置的画布 (x, y)（null = 当前点经纬度缺失，仅画轮廓不画点）
     */
    data class Projected(
        val polyline: List<Offset>,
        val current: Offset?,
    )

    /**
     * 把赛道 polyline + 当前点经纬度等距矩形投影到画布。
     *
     * 投影：x = (lon - lon0) * cos(lat0) * R，y = (lat - lat0) * R（R 度→米常量约掉，仅做相对投影）；
     * 等比缩放保比例（取 x/y 跨度最大者作分母，不拉伸失真）；正北朝上（y 向下翻转：纬度大在上）。
     * padding 留边。
     *
     * 降级（design / spec 反例）：points 少于 2 个返回 null（调用方隐藏小地图，不崩）。
     * 单点 polyline（所有点重合，跨度为 0）也安全：除零用 1f 兜底，所有点投影到画布中心。
     *
     * @param points       赛道轮廓（List<GeoPoint>）
     * @param currentLat   当前帧纬度（null 时不画当前点）
     * @param currentLon   当前帧经度
     * @param canvasWidth  画布宽（px）
     * @param canvasHeight 画布高（px）
     * @param padding      四周留边（px）
     * @return 投影结果；points < 2 返回 null
     */
    fun project(
        points: List<GeoPoint>,
        currentLat: Double?,
        currentLon: Double?,
        canvasWidth: Float,
        canvasHeight: Float,
        padding: Float,
    ): Projected? {
        if (points.size < 2) return null
        if (canvasWidth <= 0f || canvasHeight <= 0f) return null

        val lat0 = points.first().latitude
        val lon0 = points.first().longitude
        val cosLat0 = cos(Math.toRadians(lat0))

        // 等距矩形相对投影（米常量约掉，只保留相对比例）
        fun toLocal(lat: Double, lon: Double): Pair<Float, Float> {
            val x = ((lon - lon0) * cosLat0).toFloat()
            val y = ((lat - lat0)).toFloat()
            return x to y
        }

        val locals = points.map { toLocal(it.latitude, it.longitude) }
        val minX = locals.minOf { it.first }
        val maxX = locals.maxOf { it.first }
        val minY = locals.minOf { it.second }
        val maxY = locals.maxOf { it.second }

        val spanX = maxX - minX
        val spanY = maxY - minY
        // 等比缩放：取跨度最大者作分母（保比例不拉伸）；跨度 0（点重合）用 1f 兜底避免除零
        val span = max(max(spanX, spanY), 1e-9f)

        val availW = (canvasWidth - 2 * padding).coerceAtLeast(0f)
        val availH = (canvasHeight - 2 * padding).coerceAtLeast(0f)
        val scale = max(min2(availW, availH) / span, 0f)

        // 居中：投影后内容宽高
        val contentW = spanX * scale
        val contentH = spanY * scale
        val offsetX = padding + (availW - contentW) / 2f
        val offsetY = padding + (availH - contentH) / 2f

        // y 翻转：纬度大（北）在画布上方
        fun toCanvas(localX: Float, localY: Float): Offset {
            val cx = offsetX + (localX - minX) * scale
            val cy = offsetY + (maxY - localY) * scale
            return Offset(cx, cy)
        }

        val polyline = locals.map { toCanvas(it.first, it.second) }
        val current = if (currentLat != null && currentLon != null) {
            val (lx, ly) = toLocal(currentLat, currentLon)
            toCanvas(lx, ly)
        } else {
            null
        }
        return Projected(polyline = polyline, current = current)
    }

    private fun min2(a: Float, b: Float): Float = if (a < b) a else b
}

/**
 * 赛道小地图 Composable：画轮廓 polyline + 当前位置高亮点。
 * points < 2 时调用方应隐藏本组件（这里若仍渲染则不画任何内容，不崩）。
 *
 * @param points     赛道轮廓
 * @param currentLat 当前帧纬度（null 不画点）
 * @param currentLon 当前帧经度
 */
@Composable
fun TrackMiniMap(
    points: List<GeoPoint>,
    currentLat: Double?,
    currentLon: Double?,
    modifier: Modifier = Modifier,
) {
    val lineColor = TrackTechColors.BorderAlpha60
    val dotColor = TrackTechColors.Cyan
    Canvas(modifier = modifier) {
        val projected = TrackMiniMapProjection.project(
            points = points,
            currentLat = currentLat,
            currentLon = currentLon,
            canvasWidth = size.width,
            canvasHeight = size.height,
            padding = 6.dp.toPx(),
        ) ?: return@Canvas

        // 赛道轮廓 polyline
        if (projected.polyline.size >= 2) {
            val path = Path().apply {
                moveTo(projected.polyline.first().x, projected.polyline.first().y)
                projected.polyline.drop(1).forEach { lineTo(it.x, it.y) }
                // 赛道闭环：首尾相连
                lineTo(projected.polyline.first().x, projected.polyline.first().y)
            }
            drawPath(path = path, color = lineColor, style = Stroke(width = 2f))
        }

        // 当前位置高亮点
        projected.current?.let { c ->
            drawCircle(color = dotColor, radius = 5f, center = c)
        }
    }
}
