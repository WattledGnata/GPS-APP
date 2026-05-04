package com.blazepush.core.domain.model

/**
 * 单帧 telemetry 样本（接收侧真壁钟域）。
 * 同时复用为 LapTelemetry 和 PerformanceTelemetry 的 sample 类型。
 * elapsedMsInLap 在 LAP_SESSION 场景 = absoluteTsMs - lapStartWallClock；
 * 在 PERFORMANCE_TEST 场景 = tsDeltaMs（测试中累计耗时）。
 *
 * @author CC
 * @description single telemetry sample in wall-clock domain
 * @date 2026-05-04
 */
data class LapTelemetrySample(
    val absoluteTsMs: Long,
    val elapsedMsInLap: Long,
    val lat: Double,
    val lon: Double,
    val speedKmh: Double,
    val bearingDeg: Double?,
    val accelerationG: Double? = null,
    val flags: Int = 0,
)

/**
 * 单圈完整 telemetry 切片。
 * samples 按 absoluteTsMs 升序；sectorBoundaries 首项 == lapStartWallClock。
 *
 * @author CC
 * @description single-lap complete telemetry slice
 * @date 2026-05-04
 */
data class LapTelemetry(
    val sessionId: String,
    val lapIndex: Int,
    val lapStartWallClock: Long,
    val lapEndWallClock: Long,
    val lapDurationMs: Long,
    val samples: List<LapTelemetrySample>,
    val sectorBoundaries: List<Long>,
    val trackId: String?,
    val trackNameSnapshot: String?,
)

/**
 * PERFORMANCE_TEST 完整 dataPoints 切片（0-100 / 100-0 加速测试）。
 * 复用 LapTelemetrySample 类型，按 absoluteTsMs 升序。
 *
 * @author CC
 * @description performance test complete data-points slice
 * @date 2026-05-04
 */
data class PerformanceTelemetry(
    val testId: String,
    val testStartWallClock: Long,
    val testEndWallClock: Long,
    val samples: List<LapTelemetrySample>,
)
