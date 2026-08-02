package com.blazepush.feature.test.overlay

import com.blazepush.feature.test.model.track.GeoPoint
import kotlin.math.min

enum class VideoOverlayStyle(val displayName: String, val description: String) {
    FLAT("简洁平铺", "画面优先，信息轻量悬浮"),
    RAIL("统一底栏", "集中扫读，适合完整数据展示"),
    MECHANICAL("机械仪表", "速度弧与仪表簇，风格更强"),
    ;

    companion object {
        fun fromStored(value: String?): VideoOverlayStyle =
            entries.firstOrNull { it.name == value } ?: FLAT
    }
}

data class OverlayHudFrame(
    val speedKmh: Double?,
    val latG: Double?,
    val lonG: Double?,
    val lapNumber: Int?,
    val elapsedMs: Long?,
    val deltaMs: Long?,
    val trackPoints: List<GeoPoint>,
    val currentLat: Double?,
    val currentLon: Double?,
    val maxSpeedKmh: Double,
)

data class HudRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
}

data class OverlayHudLayout(
    val speed: HudRect,
    val timing: HudRect,
    val gForce: HudRect,
    val map: HudRect,
    val container: HudRect?,
) {
    companion object {
        fun calculate(style: VideoOverlayStyle, width: Float, height: Float): OverlayHudLayout {
            require(width > 0f && height > 0f)
            val short = min(width, height)
            val margin = min(short * 0.02f, 24f)
            // 导出视频会被系统相册和第三方播放器再次覆盖控制条。统一保留底部 12%
            // 短边安全区，使预览和烧录结果都不把圈时/地图压在进度条下面。
            val bottomSafeInset = short * 0.12f
            val safeBottom = height - margin - bottomSafeInset
            return when (style) {
                VideoOverlayStyle.FLAT -> {
                    val speedWidth = short * 0.25f
                    val speedHeight = short * 0.20f
                    val timingWidth = short * 0.38f
                    val timingHeight = short * 0.21f
                    val gSize = short * 0.15f
                    val mapSize = short * 0.21f
                    OverlayHudLayout(
                        speed = HudRect(margin, margin, margin + speedWidth, margin + speedHeight),
                        timing = HudRect(margin, safeBottom - timingHeight, margin + timingWidth, safeBottom),
                        gForce = HudRect(width - margin - gSize, margin, width - margin, margin + gSize),
                        map = HudRect(width - margin - mapSize, safeBottom - mapSize, width - margin, safeBottom),
                        container = null,
                    )
                }
                VideoOverlayStyle.RAIL -> {
                    val railH = short * 0.22f
                    val rail = HudRect(margin, safeBottom - railH, width - margin, safeBottom)
                    val contentW = rail.width
                    OverlayHudLayout(
                        speed = HudRect(rail.left, rail.top, rail.left + contentW * 0.22f, rail.bottom),
                        timing = HudRect(rail.left + contentW * 0.22f, rail.top, rail.left + contentW * 0.51f, rail.bottom),
                        gForce = HudRect(rail.left + contentW * 0.66f, rail.top, rail.left + contentW * 0.82f, rail.bottom),
                        map = HudRect(rail.left + contentW * 0.82f, rail.top, rail.right, rail.bottom),
                        container = rail,
                    )
                }
                VideoOverlayStyle.MECHANICAL -> {
                    val clusterH = short * 0.25f
                    val cluster = HudRect(
                        margin,
                        safeBottom - clusterH,
                        width * 0.50f,
                        safeBottom,
                    )
                    val mapW = width * 0.23f
                    val map = HudRect(width - margin - mapW, cluster.top, width - margin, cluster.bottom)
                    OverlayHudLayout(
                        speed = HudRect(cluster.left, cluster.top, cluster.left + cluster.width * 0.43f, cluster.bottom),
                        timing = HudRect(cluster.left + cluster.width * 0.43f, cluster.top, cluster.right, cluster.top + cluster.height * 0.70f),
                        gForce = HudRect(cluster.left + cluster.width * 0.43f, cluster.top + cluster.height * 0.70f, cluster.right, cluster.bottom),
                        map = map,
                        container = HudRect(cluster.left, cluster.top, map.right, cluster.bottom),
                    )
                }
            }
        }
    }
}

internal fun mechanicalSpeedNeedleAngle(speedKmh: Double?, maxSpeedKmh: Double): Float {
    val fraction = ((speedKmh ?: 0.0) / maxSpeedKmh.coerceAtLeast(1.0))
        .coerceIn(0.0, 1.0)
    return 145f + 250f * fraction.toFloat()
}
