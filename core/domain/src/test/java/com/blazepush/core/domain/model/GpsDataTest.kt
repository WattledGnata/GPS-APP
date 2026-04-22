package com.blazepush.core.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 锁定 GpsData 模型的基础契约。
 *
 * 对应 OpenSpec change fix-laptime-clock-source-integrity tasks.md 2.6。
 */
class GpsDataTest {

    @Test
    fun Empty_isTimeSyncedIsFalse() {
        assertFalse(
            "GpsData.Empty.isTimeSynced 必须是 false — 冷启动时圈速链路应当从未同步状态起跳",
            GpsData.Empty.isTimeSynced
        )
    }

    @Test
    fun isTimeSynced_defaultsToFalse_whenNotExplicitlySet() {
        val data = GpsData(
            timestamp = 0L,
            speed = 0.0,
            latitude = 0.0,
            longitude = 0.0,
            altitude = 0.0,
            bearing = 0.0,
            satelliteCount = 0,
            hdop = 0.0,
            vdop = 0.0,
            frequency = 0.0,
            isConnected = false,
            isTestReady = false,
            errorMessage = null
        )
        assertFalse(
            "GpsData 新构造时 isTimeSynced 必须默认 false，避免历史调用点在未显式设置时污染圈速链路",
            data.isTimeSynced
        )
    }

    @Test
    fun copy_preservesIsTimeSynced_whenNotOverridden() {
        val synced = GpsData.Empty.copy(isTimeSynced = true)
        val copied = synced.copy(speed = 42.0)
        assertEquals(
            "copy 未显式改 isTimeSynced 时必须保持原值",
            true,
            copied.isTimeSynced
        )
    }
}
