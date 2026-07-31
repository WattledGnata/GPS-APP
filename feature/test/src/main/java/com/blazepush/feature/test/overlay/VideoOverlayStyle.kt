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
            // HUD is an edge overlay, not a centered card.  The previous 4% inset
            // was very visible on the wide landscape device (over 100 px), which
            // made every gauge look like it floated in the middle of the frame.
            val margin = min(short * 0.016f, 24f)
            return when (style) {
                VideoOverlayStyle.FLAT -> {
                    val gSize = short * 0.20f
                    val mapSize = short * 0.28f
                    OverlayHudLayout(
                        speed = HudRect(margin, margin, width * 0.27f, height * 0.32f),
                        timing = HudRect(margin, height - margin - short * 0.30f, width * 0.34f, height - margin),
                        gForce = HudRect(width - margin - gSize, margin, width - margin, margin + gSize),
                        map = HudRect(width - margin - mapSize, height - margin - mapSize, width - margin, height - margin),
                        container = null,
                    )
                }
                VideoOverlayStyle.RAIL -> {
                    // 回放画面通常很宽；25% 高度会把信息压成底部一条细带，留下过大的空黑区。
                    // 提高到 30% 仍避开画面中心，同时让圈时/地图具备可读尺寸。
                    val railH = short * 0.30f
                    val rail = HudRect(margin, height - margin - railH, width - margin, height - margin)
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
                    // 与 RAIL 保持同一视觉重量，避免切换风格时 HUD 突然缩成底部小条。
                    val clusterH = short * 0.33f
                    val cluster = HudRect(
                        margin,
                        height - margin - clusterH,
                        width * 0.58f,
                        height - margin,
                    )
                    val mapW = width * 0.30f
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
