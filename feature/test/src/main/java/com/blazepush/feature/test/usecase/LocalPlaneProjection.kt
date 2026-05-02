package com.blazepush.feature.test.usecase

import kotlin.math.cos

/**
 * 局部平面投影：把 lat/lon 度坐标转成相对参考点 (refLat, refLon) 的本地米坐标。
 *
 * 投影策略：经度 1 度 ≈ 111_320 米 × cos(refLat)；纬度 1 度 ≈ 111_320 米。
 * 在 < 5km 范围内（典型赛道场景）误差 < 0.1m，可忽略。round add-realtime-lap-delta 用于 best 圈轨迹
 * 跟当前 GPS 点统一坐标系，方便 polyline segment 投影计算。
 *
 * @author CC
 * @description local plane projection helper for lap-delta calculation
 * @date 2026-05-02
 */
internal object LocalPlaneProjection {
    private const val DEGREE_TO_METERS = 111_320.0

    fun toMeters(refLat: Double, refLon: Double, lat: Double, lon: Double): Pair<Float, Float> {
        val cosLat = cos(Math.toRadians(refLat))
        val dx = ((lon - refLon) * DEGREE_TO_METERS * cosLat).toFloat()
        val dy = ((lat - refLat) * DEGREE_TO_METERS).toFloat()
        return dx to dy
    }
}
