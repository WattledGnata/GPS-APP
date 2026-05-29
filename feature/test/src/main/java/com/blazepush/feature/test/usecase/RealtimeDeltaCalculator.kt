package com.blazepush.feature.test.usecase

import kotlin.math.sqrt

/**
 * @author CC
 * @description polyline projection result for a single GPS frame against best-lap reference
 * @date 2026-05-02
 */
internal data class DeltaProjection(
    val deltaMs: Long,
    val matchedIdx: Int,
    val projDistanceM: Float,
)

/**
 * 当前 GPS 点投影到 [reference] best 圈 polyline，找最近 segment + 投影比例 t，
 * 用 t 在 segment 两端 elapsedMs 之间线性插值得到 best 圈"同进度位置"的累计时间，
 * 与当前圈 elapsedMs 相减得到实时秒差。
 *
 * 设计依据见 design.md Decision 1（polyline projection vs 最近点 index）+
 * Alt B stateless 全量 O(n) 投影（round redesign-realtime-delta-projection-search 重设计，
 * 根除 prevMatchedIdx 连续性假设）+ Decision 3（失效阈值）。
 *
 * @param reference best 圈预计算索引
 * @param currentLapElapsedMs 当前圈已用毫秒（**必须**用 GPS sample ts 域：`gpsData.timestamp - lastAcceptedCrossing.timestampMillis`，
 * 不混 wall clock，避免链路延迟污染）
 * @param currentX 当前 GPS 点投影到 reference 坐标系的本地米 x
 * @param currentY 同上 y
 * @param failoverDistanceM 投影距离阈值（米），超过视为失效返回 null，默认 50f
 * @return 投影成功返回 [DeltaProjection]；reference 不足 2 点 / 投影距离超阈值返回 null
 *
 * @author CC
 * @description polyline-projection-based realtime lap delta calculation
 * @date 2026-05-02
 */
internal fun projectDelta(
    reference: ReferenceLapIndex,
    currentLapElapsedMs: Long,
    currentX: Float,
    currentY: Float,
    failoverDistanceM: Float = 50f,
): DeltaProjection? {
    val size = reference.xs.size
    if (size < 2) return null

    var bestSegIdx = -1
    var bestT = 0f
    var bestDistSq = Float.MAX_VALUE

    for (i in 0..(size - 2)) {
        val segDx = reference.xs[i + 1] - reference.xs[i]
        val segDy = reference.ys[i + 1] - reference.ys[i]
        val segLenSq = segDx * segDx + segDy * segDy
        val tRaw = if (segLenSq < 1e-6f) {
            0f
        } else {
            ((currentX - reference.xs[i]) * segDx + (currentY - reference.ys[i]) * segDy) / segLenSq
        }
        val t = tRaw.coerceIn(0f, 1f)
        val projX = reference.xs[i] + t * segDx
        val projY = reference.ys[i] + t * segDy
        val ddx = currentX - projX
        val ddy = currentY - projY
        val distSq = ddx * ddx + ddy * ddy
        if (distSq < bestDistSq) {
            bestDistSq = distSq
            bestSegIdx = i
            bestT = t
        }
    }
    if (bestSegIdx < 0) return null

    val projDistanceM = sqrt(bestDistSq)
    if (projDistanceM > failoverDistanceM) return null

    val elapsedDelta = reference.elapsedMs[bestSegIdx + 1] - reference.elapsedMs[bestSegIdx]
    val bestElapsed = reference.elapsedMs[bestSegIdx] + (bestT * elapsedDelta).toLong()
    val deltaMs = currentLapElapsedMs - bestElapsed
    return DeltaProjection(
        deltaMs = deltaMs,
        matchedIdx = bestSegIdx,
        projDistanceM = projDistanceM,
    )
}