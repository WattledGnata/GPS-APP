package com.blazepush.feature.test.ui.tracktech

import com.blazepush.core.domain.model.ConnectionState
import com.blazepush.core.domain.model.DataQuality
import com.blazepush.core.domain.model.GpsData
import com.blazepush.core.domain.model.QualityLevel
import com.blazepush.core.domain.model.SignalStrength
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TabGatingPolicy 单测。
 *
 * 关键边界（与 design D14 + spec/cross-tab-device-gating "TabGatingPolicy 不检查 speed range
 * / test template / 起点条件" 锁死）：
 *
 * - 仅 4 项 device/data 条件参与 readiness 计算
 * - speed = 0 / speed = 120 不影响 canEnterTestFlow（关键回归：用户静止点 100-0
 *   不会被错误导到 Device tab）
 *
 * @author Track Tech V2
 * @description TabGatingPolicy 4 项条件 + speed 不参与回归 单测
 * @date 2026-04-28
 */
class TabGatingPolicyTest {

    private fun goodGpsData(speed: Double = 0.0): GpsData = GpsData.Empty.copy(
        speed = speed,
        satelliteCount = 8,
        hdop = 1.5,
        timestamp = 1_000L,
        fixQuality = 1,
        isTimeSynced = true,
        hasMainFrame = true,
        isConnected = true,
        consecutiveReliableMainFrames = 3,
    )

    private fun goodDataQuality(dataAge: Long = 500L): DataQuality = DataQuality(
        satelliteCount = 8,
        signalStrength = SignalStrength.GOOD,
        hdop = 1.5,
        vdop = 1.5,
        dataAge = dataAge,
        packetLoss = 0.0,
        frequency = 25.0,
        overall = QualityLevel.GOOD,
        overallScore = 90,
    )

    /**
     * 4 项条件全满足时 canEnterTestFlow 应为 true 且 unmetConditions 为空。
     */
    @Test
    fun allConditionsMet_returnsCanEnterTestFlowTrue() {
        val readiness = TabGatingPolicy.computeTabReadiness(
            connectionState = ConnectionState.CONNECTED,
            gpsData = goodGpsData(),
            dataQuality = goodDataQuality(),
        )
        assertTrue(readiness.canEnterTestFlow)
        assertTrue(readiness.unmetConditions.isEmpty())
    }

    /**
     * BLE 断开应返回 BLE_CONNECTED unmet。
     */
    @Test
    fun bleDisconnected_returnsBleConnectedUnmet() {
        val readiness = TabGatingPolicy.computeTabReadiness(
            connectionState = ConnectionState.DISCONNECTED,
            gpsData = goodGpsData(),
            dataQuality = goodDataQuality(),
        )
        assertFalse(readiness.canEnterTestFlow)
        assertTrue(readiness.unmetConditions.contains(TabReadinessCondition.BLE_CONNECTED))
    }

    /**
     * BLE 正在连接应返回 BLE_CONNECTED unmet（仅 CONNECTED 算 ready）。
     */
    @Test
    fun bleConnecting_returnsBleConnectedUnmet() {
        val readiness = TabGatingPolicy.computeTabReadiness(
            connectionState = ConnectionState.CONNECTING,
            gpsData = goodGpsData(),
            dataQuality = goodDataQuality(),
        )
        assertFalse(readiness.canEnterTestFlow)
        assertTrue(readiness.unmetConditions.contains(TabReadinessCondition.BLE_CONNECTED))
    }

    /**
     * dataAge >= 1000ms 视为陈旧，应返回 DATA_FRESH unmet。
     */
    @Test
    fun dataAgeStale_returnsDataFreshUnmet() {
        val readiness = TabGatingPolicy.computeTabReadiness(
            connectionState = ConnectionState.CONNECTED,
            gpsData = goodGpsData(),
            dataQuality = goodDataQuality(dataAge = 1500L),
        )
        assertFalse(readiness.canEnterTestFlow)
        assertTrue(readiness.unmetConditions.contains(TabReadinessCondition.DATA_FRESH))
    }

    /**
     * dataAge = 1000ms 边界（>= 阈值）视为 unmet。
     */
    @Test
    fun dataAgeAtThreshold_returnsDataFreshUnmet() {
        val readiness = TabGatingPolicy.computeTabReadiness(
            connectionState = ConnectionState.CONNECTED,
            gpsData = goodGpsData(),
            dataQuality = goodDataQuality(dataAge = 1000L),
        )
        assertFalse(readiness.canEnterTestFlow)
        assertTrue(readiness.unmetConditions.contains(TabReadinessCondition.DATA_FRESH))
    }

    @Test
    fun dataAgeUsesFrameSpecificDynamicDeadline() {
        val fresh = TabGatingPolicy.computeTabReadiness(
            ConnectionState.CONNECTED,
            goodGpsData().copy(mainFrameSilenceTimeoutMs = 400L),
            goodDataQuality(dataAge = 399L),
        )
        val expired = TabGatingPolicy.computeTabReadiness(
            ConnectionState.CONNECTED,
            goodGpsData().copy(mainFrameSilenceTimeoutMs = 400L),
            goodDataQuality(dataAge = 400L),
        )

        assertFalse(fresh.unmetConditions.contains(TabReadinessCondition.DATA_FRESH))
        assertTrue(expired.unmetConditions.contains(TabReadinessCondition.DATA_FRESH))
    }

    @Test
    fun staleOrMissingCurrentGenerationMainFrame_returnsDataFreshUnmet() {
        val stale = TabGatingPolicy.computeTabReadiness(
            ConnectionState.CONNECTED,
            goodGpsData().copy(isStale = true),
            goodDataQuality(),
        )
        val missing = TabGatingPolicy.computeTabReadiness(
            ConnectionState.CONNECTED,
            goodGpsData().copy(hasMainFrame = false),
            goodDataQuality(),
        )
        assertTrue(stale.unmetConditions.contains(TabReadinessCondition.DATA_FRESH))
        assertTrue(missing.unmetConditions.contains(TabReadinessCondition.DATA_FRESH))
    }

    @Test
    fun noFixOrUnsyncedFrame_cannotEnterTestFlow() {
        val noFix = TabGatingPolicy.computeTabReadiness(
            ConnectionState.CONNECTED,
            goodGpsData().copy(fixQuality = 0),
            goodDataQuality(),
        )
        val unsynced = TabGatingPolicy.computeTabReadiness(
            ConnectionState.CONNECTED,
            goodGpsData().copy(isTimeSynced = false, timestamp = Long.MIN_VALUE),
            goodDataQuality(),
        )
        assertTrue(noFix.unmetConditions.contains(TabReadinessCondition.GPS_FIX_VALID))
        assertTrue(unsynced.unmetConditions.contains(TabReadinessCondition.TIME_SYNCED))
    }

    @Test
    fun fewerThanThreeReliableRecoveryFrames_cannotEnterTestFlow() {
        val readiness = TabGatingPolicy.computeTabReadiness(
            ConnectionState.CONNECTED,
            goodGpsData().copy(consecutiveReliableMainFrames = 2),
            goodDataQuality(),
        )

        assertTrue(readiness.unmetConditions.contains(TabReadinessCondition.GPS_SIGNAL_STABLE))
    }

    /**
     * 卫星 < 6 应返回 SATELLITES_SUFFICIENT unmet（与 SmartTestLauncher 阈值对齐）。
     */
    @Test
    fun satelliteCountLow_returnsSatellitesSufficientUnmet() {
        val readiness = TabGatingPolicy.computeTabReadiness(
            connectionState = ConnectionState.CONNECTED,
            gpsData = goodGpsData().copy(satelliteCount = 5),
            dataQuality = goodDataQuality(),
        )
        assertFalse(readiness.canEnterTestFlow)
        assertTrue(
            readiness.unmetConditions.contains(TabReadinessCondition.SATELLITES_SUFFICIENT),
        )
    }

    /**
     * hdop = 0 视为 GPS 未 fix（与 SmartTestLauncher hdop > 0 阈值对齐）。
     */
    @Test
    fun hdopZero_returnsHdopGoodUnmet() {
        val readiness = TabGatingPolicy.computeTabReadiness(
            connectionState = ConnectionState.CONNECTED,
            gpsData = goodGpsData().copy(hdop = 0.0),
            dataQuality = goodDataQuality(),
        )
        assertFalse(readiness.canEnterTestFlow)
        assertTrue(readiness.unmetConditions.contains(TabReadinessCondition.HDOP_GOOD))
    }

    /**
     * hdop = 2.0 边界 unmet（与 SmartTestLauncher hdop < 2.0 阈值对齐）。
     */
    @Test
    fun hdopAtThreshold_returnsHdopGoodUnmet() {
        val readiness = TabGatingPolicy.computeTabReadiness(
            connectionState = ConnectionState.CONNECTED,
            gpsData = goodGpsData().copy(hdop = 2.0),
            dataQuality = goodDataQuality(),
        )
        assertFalse(readiness.canEnterTestFlow)
        assertTrue(readiness.unmetConditions.contains(TabReadinessCondition.HDOP_GOOD))
    }

    /**
     * **关键回归**：用户静止 (speed = 0) 时 readiness 应仍为 true（设备/数据正常即可），
     * 不会被错误导到 Device tab。
     *
     * 反例（不要走的路径）：SmartTestLauncher.checkLaunchConditions 在 100-0 测试调用时
     * 传 startSpeedMin/Max = 95-105，speed_at_start condition 检查 0 in 95.0..105.0 = false
     * 必然 unmet，canLaunch = false。本断言锁住 TabGatingPolicy 不复用此路径。
     */
    @Test
    fun speedZero_doesNotPreventCanEnterTestFlow() {
        val readiness = TabGatingPolicy.computeTabReadiness(
            connectionState = ConnectionState.CONNECTED,
            gpsData = goodGpsData(speed = 0.0),
            dataQuality = goodDataQuality(),
        )
        assertTrue(
            "speed = 0 时 readiness.canEnterTestFlow 应为 true（设备/数据正常）",
            readiness.canEnterTestFlow,
        )
        assertTrue(readiness.unmetConditions.isEmpty())
    }

    /**
     * speed = 120 km/h（巡航速度）也不影响 readiness（设备/数据正常即可）。
     */
    @Test
    fun speedHigh_doesNotPreventCanEnterTestFlow() {
        val readiness = TabGatingPolicy.computeTabReadiness(
            connectionState = ConnectionState.CONNECTED,
            gpsData = goodGpsData(speed = 120.0),
            dataQuality = goodDataQuality(),
        )
        assertTrue(readiness.canEnterTestFlow)
    }

    /**
     * 多项 unmet 应同时出现在 unmetConditions 中（4 项全错时 size = 4）。
     */
    @Test
    fun multipleUnmet_includesAllInList() {
        val readiness = TabGatingPolicy.computeTabReadiness(
            connectionState = ConnectionState.DISCONNECTED,
            gpsData = goodGpsData().copy(satelliteCount = 3, hdop = 0.0),
            dataQuality = goodDataQuality(dataAge = 2000L),
        )
        assertFalse(readiness.canEnterTestFlow)
        assertEquals(4, readiness.unmetConditions.size)
        assertTrue(readiness.unmetConditions.contains(TabReadinessCondition.BLE_CONNECTED))
        assertTrue(readiness.unmetConditions.contains(TabReadinessCondition.DATA_FRESH))
        assertTrue(readiness.unmetConditions.contains(TabReadinessCondition.SATELLITES_SUFFICIENT))
        assertTrue(readiness.unmetConditions.contains(TabReadinessCondition.HDOP_GOOD))
    }
}
