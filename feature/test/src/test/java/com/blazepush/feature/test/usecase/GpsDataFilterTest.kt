package com.blazepush.feature.test.usecase

import com.blazepush.core.domain.model.GpsData
import com.blazepush.core.domain.usecase.GpsDataFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * `GpsDataFilter` 的分层守卫（时间 delta 计算类）测试。
 *
 * **临时位置说明**：本项目 `core:domain` 模块曾存在的 `GpsDataFilterTest` 位于
 * `app/src/test/java/com/blazepush/domain/usecase/GpsDataFilterTest.kt`，package 与 import
 * 路径均已失效（属于战役 D 的 package 迁移范围）。OpenSpec change
 * `fix-laptime-clock-source-integrity`（第 4 组任务 4.3）所需的 filter 守卫测试先临时挂在
 * `feature:test` 模块下，待战役 D 把旧文件迁回并修复后，统一合并至
 * `core/domain/src/test/java/com/blazepush/core/domain/usecase/GpsDataFilterTest.kt`。
 *
 * 对应 spec：
 * `openspec/changes/fix-laptime-clock-source-integrity/specs/laptime-clock-source/spec.md`
 *   Requirement: 分层守卫 — Scenario "GpsDataFilter 在未同步时不做时间 delta 计算"。
 */
class GpsDataFilterTest {

    /**
     * 构造测试用的未同步帧。`timestamp = Long.MIN_VALUE` 与 parser sentinel 对齐，
     * 用于验证守卫生效时不会因为对 sentinel 做减法产生溢出。
     */
    private fun unsyncedSample(
        speed: Double = 50.0,
        latitude: Double = 30.5,
        longitude: Double = 104.4,
        altitude: Double = 500.0,
        bearing: Double = 90.0,
        hdop: Double = 1.0
    ): GpsData = GpsData(
        timestamp = Long.MIN_VALUE,
        speed = speed,
        latitude = latitude,
        longitude = longitude,
        altitude = altitude,
        bearing = bearing,
        satelliteCount = 8,
        hdop = hdop,
        vdop = 1.0,
        frequency = 25.0,
        isConnected = true,
        isTestReady = false,
        errorMessage = null,
        fixQuality = 1,
        isTimeSynced = false
    )

    /**
     * 构造测试用的已同步帧。用于恢复场景验证。
     */
    private fun syncedSample(
        timestamp: Long,
        speed: Double = 50.0,
        latitude: Double = 30.5,
        longitude: Double = 104.4,
        altitude: Double = 500.0,
        bearing: Double = 90.0,
        hdop: Double = 1.0
    ): GpsData = GpsData(
        timestamp = timestamp,
        speed = speed,
        latitude = latitude,
        longitude = longitude,
        altitude = altitude,
        bearing = bearing,
        satelliteCount = 8,
        hdop = hdop,
        vdop = 1.0,
        frequency = 25.0,
        isConnected = true,
        isTestReady = false,
        errorMessage = null,
        fixQuality = 1,
        isTimeSynced = true
    )

    /**
     * 守卫生效时直接返回"零时间 delta 快照"：
     * - speed / latitude 等空间量从 raw 透传；
     * - acceleration / confidence 归零；
     * - isAnomaly / isPositionAnomaly 为 false；
     * - timestamp 保持 raw 值（sentinel）。
     */
    @Test
    fun process_notTimeSynced_returnsZeroDeltaSnapshot() {
        val filter = GpsDataFilter()
        val raw = unsyncedSample(speed = 50.0, latitude = 30.5, longitude = 104.4)

        val result = filter.process(raw)

        assertEquals(50.0, result.speed, 1e-9)
        assertEquals(30.5, result.latitude, 1e-9)
        assertEquals(104.4, result.longitude, 1e-9)
        assertEquals(0.0, result.acceleration, 1e-9)
        assertEquals(0.0, result.confidence, 1e-9)
        assertFalse(result.isAnomaly)
        assertFalse(result.isPositionAnomaly)
        assertEquals(Long.MIN_VALUE, result.timestamp)
        assertEquals(1.0, result.consistencyFactor, 1e-9)
    }

    /**
     * 守卫生效时不得更新 `previousRaw / previousPosition / 四个窗口`。
     *
     * **间接验证**：喂 10 帧未同步帧后，再喂 1 帧已同步帧；此时该同步帧对 filter 而言
     * 应是"首帧"——`calculateAcceleration` 在 `previousRaw == null` 分支会返回 0.0。
     * 如果任何一个未同步帧意外更新了内部 `previousRaw`，则第 11 帧的 dt 将对应着
     * `Long.MIN_VALUE` 到当前时间戳的溢出差值，`acceleration` 结果必然异常（非 0）。
     */
    @Test
    fun process_notTimeSynced_doesNotUpdateInternalState() {
        val filter = GpsDataFilter()

        // 10 帧未同步帧，速度从 10 递增到 100，位置偏移，制造足够"诱人"的状态变化以放大漏改风险
        repeat(10) { idx ->
            val raw = unsyncedSample(
                speed = 10.0 + idx * 10.0,
                latitude = 30.5 + idx * 0.0001,
                longitude = 104.4 + idx * 0.0001,
                bearing = (idx * 10).toDouble()
            )
            val result = filter.process(raw)
            // 每一帧都必须是零 delta 快照，侧面验证守卫没被绕过
            assertEquals(0.0, result.acceleration, 1e-9)
            assertEquals(0.0, result.confidence, 1e-9)
            assertFalse(result.isAnomaly)
            assertFalse(result.isPositionAnomaly)
        }

        // 第 11 帧切回同步。如果 filter 真的被未同步帧污染了内部状态，
        // 那它会把上一帧（`Long.MIN_VALUE`）拿来和当前 timestamp 做减法，
        // 导致 acceleration 出现溢出值或非零速度差结果。
        val resumed = syncedSample(
            timestamp = 1_000_000L,
            speed = 80.0,
            latitude = 30.6,
            longitude = 104.5
        )
        val resumedResult = filter.process(resumed)

        // 同步恢复的首帧视作首样本：acceleration = 0，isAnomaly = false
        assertEquals(0.0, resumedResult.acceleration, 1e-9)
        assertFalse(resumedResult.isAnomaly)
    }
}
