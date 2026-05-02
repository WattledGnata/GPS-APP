package com.blazepush.feature.test.usecase

import com.blazepush.feature.test.model.laptiming.LapRecord
import kotlin.math.sqrt

/**
 * Best 圈轨迹的预计算索引：紧凑 FloatArray / LongArray 存储，供 RealtimeDeltaCalculator
 * 在每帧 GPS data 来到时做 polyline segment 投影。
 *
 * 关键约束：
 * - refLat/refLon 是投影原点，调用方通过 [toLocalMeters] 把当前帧 GPS 点转换到与 reference 同一坐标系
 * - lapStartTsMs 用 [LapRecord.startedAtMillis]（开圈 crossing 时间），**不**是 trajectory 首点 ts；
 *   后者通常晚一个采样间隔，会引入固定秒差偏移
 * - elapsedMs[i] = `trajectory[i].timestampMillis - bestLap.startedAtMillis`，首点 ≥ 0 反映滞后
 *
 * @author CC
 * @description preprocessed best-lap trajectory index for realtime delta projection
 * @date 2026-05-02
 */
internal data class ReferenceLapIndex(
    val refLat: Double,
    val refLon: Double,
    val xs: FloatArray,
    val ys: FloatArray,
    val cumDistanceM: FloatArray,
    val elapsedMs: LongArray,
    val lapStartTsMs: Long,
    val lapDurationMs: Long,
) {
    /** 把任意 GPS lat/lon 转成与 reference 同一坐标系的本地米坐标。 */
    fun toLocalMeters(lat: Double, lon: Double): Pair<Float, Float> =
        LocalPlaneProjection.toMeters(refLat, refLon, lat, lon)
}

/**
 * 从 best [LapRecord] 构建 [ReferenceLapIndex]。trajectory 少于 2 点返回 null（无法做 segment 投影）。
 */
internal fun buildReferenceLapIndex(bestLap: LapRecord): ReferenceLapIndex? {
    val trajectory = bestLap.trajectory
    if (trajectory.size < 2) return null

    val first = trajectory.first()
    val refLat = first.latitude
    val refLon = first.longitude
    val lapStartTsMs = bestLap.startedAtMillis

    val n = trajectory.size
    val xs = FloatArray(n)
    val ys = FloatArray(n)
    val cumDistanceM = FloatArray(n)
    val elapsedMs = LongArray(n)

    for (i in 0 until n) {
        val sample = trajectory[i]
        val (x, y) = LocalPlaneProjection.toMeters(refLat, refLon, sample.latitude, sample.longitude)
        xs[i] = x
        ys[i] = y
        elapsedMs[i] = sample.timestampMillis - lapStartTsMs
        cumDistanceM[i] = if (i == 0) {
            0f
        } else {
            val dx = xs[i] - xs[i - 1]
            val dy = ys[i] - ys[i - 1]
            cumDistanceM[i - 1] + sqrt(dx * dx + dy * dy)
        }
    }

    return ReferenceLapIndex(
        refLat = refLat,
        refLon = refLon,
        xs = xs,
        ys = ys,
        cumDistanceM = cumDistanceM,
        elapsedMs = elapsedMs,
        lapStartTsMs = lapStartTsMs,
        lapDurationMs = bestLap.durationMillis,
    )
}
