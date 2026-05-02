package com.blazepush.feature.test.usecase

import com.blazepush.core.domain.model.ConnectionState
import com.blazepush.core.domain.model.DataQuality
import com.blazepush.core.domain.model.GpsData
import com.blazepush.core.domain.model.QualityLevel
import com.blazepush.core.domain.model.SignalStrength
import com.blazepush.feature.test.model.laptiming.CrossingEvent
import com.blazepush.feature.test.model.laptiming.CrossingReason
import com.blazepush.feature.test.model.laptiming.LapSession
import com.blazepush.feature.test.model.laptiming.LapSessionStatus
import com.blazepush.feature.test.model.track.TimingGateType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * LapLiveStateDeriver 纯函数派生覆盖：未开始 / 第 N 圈进行 / INVALID / 异常状态优先级 / 去抖门。
 *
 * @author CC
 * @description unit tests for LapLiveStateDeriver
 * @date 2026-05-01
 */
class LapLiveStateDeriverTest {

    @Test
    fun `session null returns all null fields and lapNumber 1`() {
        val state = LapLiveStateDeriver.derive(
            session = null,
            currentDisplayTimeMs = 1_000L,
            gpsData = goodGpsData(),
            connectionState = ConnectionState.CONNECTED,
            dataQuality = goodDataQuality(),
            deltaToBestMs = null,
            deltaIsStale = false,
        )

        assertNull(state.currentLapTimerMs)
        assertNull(state.lastLapTimeMs)
        assertNull(state.bestLapTimeMs)
        assertNull(state.deltaToBestMs)
        assertEquals(1, state.currentLapNumber)
        assertNull(state.abnormalState)
    }

    @Test
    fun `before first crossing currentLapIndex 0 lapNumber 1 timer null`() {
        val session = sessionWith(
            crossings = emptyList(),
            currentLapIndex = 0,
        )

        val state = LapLiveStateDeriver.derive(
            session = session,
            currentDisplayTimeMs = 1_500L,
            gpsData = goodGpsData(),
            connectionState = ConnectionState.CONNECTED,
            dataQuality = goodDataQuality(),
            deltaToBestMs = null,
            deltaIsStale = false,
        )

        assertNull(state.currentLapTimerMs)
        assertEquals(1, state.currentLapNumber)
    }

    @Test
    fun `first lap in progress timer derives from crossing best and last null`() {
        val session = sessionWith(
            crossings = listOf(crossing(timestampMs = 1_000L, accepted = true)),
            currentLapIndex = 1,
        )

        val state = LapLiveStateDeriver.derive(
            session = session,
            currentDisplayTimeMs = 1_500L,
            gpsData = goodGpsData(),
            connectionState = ConnectionState.CONNECTED,
            dataQuality = goodDataQuality(),
            deltaToBestMs = null,
            deltaIsStale = false,
        )

        assertEquals(500L, state.currentLapTimerMs)
        assertNull(state.lastLapTimeMs)
        assertNull(state.bestLapTimeMs)
        assertNull(state.deltaToBestMs)
        assertEquals(1, state.currentLapNumber)
    }

    @Test
    fun `first lap completed best equals last delta negative`() {
        val session = sessionWith(
            crossings = listOf(
                crossing(timestampMs = 1_000L, accepted = true),
                crossing(timestampMs = 2_200L, accepted = true),
            ),
            currentLapIndex = 2,
        )

        val state = LapLiveStateDeriver.derive(
            session = session,
            currentDisplayTimeMs = 2_500L,
            gpsData = goodGpsData(),
            connectionState = ConnectionState.CONNECTED,
            dataQuality = goodDataQuality(),
            deltaToBestMs = null,
            deltaIsStale = false,
        )

        assertEquals(300L, state.currentLapTimerMs)
        assertEquals(1_200L, state.lastLapTimeMs)
        assertEquals(1_200L, state.bestLapTimeMs)
        // removed (round add-realtime-lap-delta)：baseline 错位减法 deltaToBestMs 已不再派生；
        // derive 现在仅直传入参 deltaToBestMs / deltaIsStale，专门 delta 行为单测在 RealtimeDeltaCalculatorTest
        assertEquals(2, state.currentLapNumber)
    }

    @Test
    fun `second lap faster than first updates best`() {
        val session = sessionWith(
            crossings = listOf(
                crossing(timestampMs = 1_000L, accepted = true),
                crossing(timestampMs = 2_200L, accepted = true),
                crossing(timestampMs = 3_300L, accepted = true),
            ),
            currentLapIndex = 3,
        )

        val state = LapLiveStateDeriver.derive(
            session = session,
            currentDisplayTimeMs = 3_400L,
            gpsData = goodGpsData(),
            connectionState = ConnectionState.CONNECTED,
            dataQuality = goodDataQuality(),
            deltaToBestMs = null,
            deltaIsStale = false,
        )

        assertEquals(100L, state.currentLapTimerMs)
        assertEquals(1_100L, state.lastLapTimeMs)
        assertEquals(1_100L, state.bestLapTimeMs)
        // removed (round add-realtime-lap-delta)：baseline 错位减法 deltaToBestMs 不再派生
        assertEquals(3, state.currentLapNumber)
    }

    @Test
    fun `second lap slower than first best unchanged`() {
        val session = sessionWith(
            crossings = listOf(
                crossing(timestampMs = 1_000L, accepted = true),
                crossing(timestampMs = 2_200L, accepted = true),
                crossing(timestampMs = 3_500L, accepted = true),
            ),
            currentLapIndex = 3,
        )

        val state = LapLiveStateDeriver.derive(
            session = session,
            currentDisplayTimeMs = 3_600L,
            gpsData = goodGpsData(),
            connectionState = ConnectionState.CONNECTED,
            dataQuality = goodDataQuality(),
            deltaToBestMs = null,
            deltaIsStale = false,
        )

        assertEquals(1_300L, state.lastLapTimeMs)
        assertEquals(1_200L, state.bestLapTimeMs)
        assertEquals(100L, state.currentLapTimerMs)
        // removed (round add-realtime-lap-delta)：baseline 错位减法 deltaToBestMs 不再派生
    }

    @Test
    fun `delta positive when current lap slower than best`() {
        val session = sessionWith(
            crossings = listOf(
                crossing(timestampMs = 0L, accepted = true),
                crossing(timestampMs = 1_100L, accepted = true),
            ),
            currentLapIndex = 2,
        )

        val state = LapLiveStateDeriver.derive(
            session = session,
            currentDisplayTimeMs = 2_400L,
            gpsData = goodGpsData(),
            connectionState = ConnectionState.CONNECTED,
            dataQuality = goodDataQuality(),
            deltaToBestMs = null,
            deltaIsStale = false,
        )

        assertEquals(1_300L, state.currentLapTimerMs)
        assertEquals(1_100L, state.bestLapTimeMs)
        // removed (round add-realtime-lap-delta)：baseline 错位减法 deltaToBestMs 已不再派生；
        // derive 现在仅直传入参 deltaToBestMs / deltaIsStale，专门 delta 行为单测在 RealtimeDeltaCalculatorTest
    }

    @Test
    fun `invalid lap rejected crossing is skipped from best computation but does not trigger banner alone`() {
        val session = sessionWith(
            crossings = listOf(
                crossing(timestampMs = 1_000L, accepted = true),
                crossing(timestampMs = 2_200L, accepted = true),
                crossing(
                    timestampMs = 2_900L,
                    accepted = false,
                    reason = CrossingReason.WrongDirection,
                ),
            ),
            currentLapIndex = 2,
        )

        val state = LapLiveStateDeriver.derive(
            session = session,
            currentDisplayTimeMs = 3_000L,
            gpsData = goodGpsData(),
            connectionState = ConnectionState.CONNECTED,
            dataQuality = goodDataQuality(),
            deltaToBestMs = null,
            deltaIsStale = false,
        )

        assertEquals(1_200L, state.bestLapTimeMs)
        assertEquals(1_200L, state.lastLapTimeMs)
        assertNull(state.abnormalState)  // 单帧 jitter 不触发 banner（去抖门）
    }

    @Test
    fun `three consecutive invalidating crossings trigger LAP_INVALIDATED banner`() {
        val session = sessionWith(
            crossings = listOf(
                crossing(timestampMs = 1_000L, accepted = true),
                crossing(
                    timestampMs = 2_500L,
                    accepted = false,
                    reason = CrossingReason.WrongDirection,
                ),
                crossing(
                    timestampMs = 2_540L,
                    accepted = false,
                    reason = CrossingReason.WrongDirection,
                ),
                crossing(
                    timestampMs = 2_580L,
                    accepted = false,
                    reason = CrossingReason.WrongDirection,
                ),
            ),
            currentLapIndex = 1,
        )

        val state = LapLiveStateDeriver.derive(
            session = session,
            currentDisplayTimeMs = 2_600L,
            gpsData = goodGpsData(),
            connectionState = ConnectionState.CONNECTED,
            dataQuality = goodDataQuality(),
            deltaToBestMs = null,
            deltaIsStale = false,
        )

        assertEquals(AbnormalState.LAP_INVALIDATED, state.abnormalState)
    }

    @Test
    fun `two invalidating crossings within window do not trigger banner (debounce)`() {
        val session = sessionWith(
            crossings = listOf(
                crossing(timestampMs = 1_000L, accepted = true),
                crossing(
                    timestampMs = 2_500L,
                    accepted = false,
                    reason = CrossingReason.WrongDirection,
                ),
                crossing(
                    timestampMs = 2_540L,
                    accepted = false,
                    reason = CrossingReason.WrongDirection,
                ),
            ),
            currentLapIndex = 1,
        )

        val state = LapLiveStateDeriver.derive(
            session = session,
            currentDisplayTimeMs = 2_600L,
            gpsData = goodGpsData(),
            connectionState = ConnectionState.CONNECTED,
            dataQuality = goodDataQuality(),
            deltaToBestMs = null,
            deltaIsStale = false,
        )

        assertNull(state.abnormalState)
    }

    @Test
    fun `three invalidating crossings spread beyond window do not trigger banner`() {
        // 3 个 event 但跨度 1500ms，超出 1000ms 去抖窗口 → 不触发
        val session = sessionWith(
            crossings = listOf(
                crossing(
                    timestampMs = 1_000L,
                    accepted = false,
                    reason = CrossingReason.WrongDirection,
                ),
                crossing(
                    timestampMs = 1_800L,
                    accepted = false,
                    reason = CrossingReason.WrongDirection,
                ),
                crossing(
                    timestampMs = 2_500L,
                    accepted = false,
                    reason = CrossingReason.WrongDirection,
                ),
            ),
            currentLapIndex = 1,
        )

        val state = LapLiveStateDeriver.derive(
            session = session,
            currentDisplayTimeMs = 2_600L,
            gpsData = goodGpsData(),
            connectionState = ConnectionState.CONNECTED,
            dataQuality = goodDataQuality(),
            deltaToBestMs = null,
            deltaIsStale = false,
        )

        // latest at t=2500，window [1500, 2500] 内只有 t=1800 + t=2500 = 2 个，不足 3 → 不触发
        assertNull(state.abnormalState)
    }

    @Test
    fun `ble disconnected wins highest priority abnormal state`() {
        val state = LapLiveStateDeriver.derive(
            session = null,
            currentDisplayTimeMs = 0L,
            gpsData = goodGpsData(),
            connectionState = ConnectionState.DISCONNECTED,
            dataQuality = goodDataQuality(),
            deltaToBestMs = null,
            deltaIsStale = false,
        )

        assertEquals(AbnormalState.BLE_DISCONNECTED, state.abnormalState)
    }

    @Test
    fun `gps signal lost when data age exceeds 1000ms`() {
        val state = LapLiveStateDeriver.derive(
            session = null,
            currentDisplayTimeMs = 0L,
            gpsData = goodGpsData(),
            connectionState = ConnectionState.CONNECTED,
            dataQuality = goodDataQuality().copy(dataAge = 1_500L),
            deltaToBestMs = null,
            deltaIsStale = false,
        )

        assertEquals(AbnormalState.GPS_SIGNAL_LOST, state.abnormalState)
    }

    @Test
    fun `waiting for gps lock when satellite count below 6`() {
        val state = LapLiveStateDeriver.derive(
            session = null,
            currentDisplayTimeMs = 0L,
            gpsData = goodGpsData().copy(satelliteCount = 4),
            connectionState = ConnectionState.CONNECTED,
            dataQuality = goodDataQuality(),
            deltaToBestMs = null,
            deltaIsStale = false,
        )

        assertEquals(AbnormalState.WAITING_FOR_GPS_LOCK, state.abnormalState)
    }

    @Test
    fun `normal state has null abnormal state`() {
        val state = LapLiveStateDeriver.derive(
            session = null,
            currentDisplayTimeMs = 0L,
            gpsData = goodGpsData(),
            connectionState = ConnectionState.CONNECTED,
            dataQuality = goodDataQuality(),
            deltaToBestMs = null,
            deltaIsStale = false,
        )

        assertNull(state.abnormalState)
    }

    @Test
    fun `NoIntersection rejected crossing does not trigger LAP_INVALIDATED banner`() {
        val session = sessionWith(
            crossings = listOf(
                crossing(timestampMs = 1_000L, accepted = true),
                crossing(timestampMs = 2_200L, accepted = true),
                crossing(
                    timestampMs = 2_500L,
                    accepted = false,
                    reason = CrossingReason.NoIntersection,
                ),
            ),
            currentLapIndex = 2,
        )

        val state = LapLiveStateDeriver.derive(
            session = session,
            currentDisplayTimeMs = 2_600L,
            gpsData = goodGpsData(),
            connectionState = ConnectionState.CONNECTED,
            dataQuality = goodDataQuality(),
            deltaToBestMs = null,
            deltaIsStale = false,
        )

        assertNull(state.abnormalState)
    }

    @Test
    fun `LAP_INVALIDATED banner fades after display window expires`() {
        // 给 3 个连续 event 满足去抖门，但 latest 距 currentTimeMs 已超出 5 秒显示窗
        val session = sessionWith(
            crossings = listOf(
                crossing(timestampMs = 900L, accepted = false, reason = CrossingReason.WrongDirection),
                crossing(timestampMs = 950L, accepted = false, reason = CrossingReason.WrongDirection),
                crossing(timestampMs = 1_000L, accepted = false, reason = CrossingReason.WrongDirection),
            ),
            currentLapIndex = 1,
        )

        val state = LapLiveStateDeriver.derive(
            session = session,
            currentDisplayTimeMs = 10_000L,  // 距 latest=1000 已 9 秒，超出 5 秒显示窗
            gpsData = goodGpsData(),
            connectionState = ConnectionState.CONNECTED,
            dataQuality = goodDataQuality(),
            deltaToBestMs = null,
            deltaIsStale = false,
        )

        assertNull(state.abnormalState)
    }

    @Test
    fun `lap number derived from currentLapIndex when at lap 5`() {
        val session = sessionWith(
            crossings = (1..5).map { crossing(timestampMs = it * 1_000L, accepted = true) },
            currentLapIndex = 5,
        )

        val state = LapLiveStateDeriver.derive(
            session = session,
            currentDisplayTimeMs = 5_400L,
            gpsData = goodGpsData(),
            connectionState = ConnectionState.CONNECTED,
            dataQuality = goodDataQuality(),
            deltaToBestMs = null,
            deltaIsStale = false,
        )

        assertEquals(5, state.currentLapNumber)
    }

    private fun sessionWith(
        crossings: List<CrossingEvent>,
        currentLapIndex: Int,
    ): LapSession = LapSession(
        sessionId = "test-session",
        trackId = "test-track",
        status = LapSessionStatus.Recording,
        startedAtMillis = 0L,
        currentLapIndex = currentLapIndex,
        crossingEvents = crossings,
    )

    private fun crossing(
        timestampMs: Long,
        accepted: Boolean,
        gateType: TimingGateType = TimingGateType.StartFinish,
        reason: CrossingReason = CrossingReason.Accepted,
    ): CrossingEvent = CrossingEvent(
        gateId = "gate-sf",
        gateType = gateType,
        timestampMillis = timestampMs,
        sampleIndex = 0,
        accepted = accepted,
        reason = reason,
    )

    private fun goodGpsData(): GpsData = GpsData.Empty.copy(
        satelliteCount = 8,
        hdop = 1.2,
        isConnected = true,
        isTestReady = true,
        fixQuality = 1,
        isTimeSynced = true,
    )

    private fun goodDataQuality(): DataQuality = DataQuality(
        satelliteCount = 8,
        signalStrength = SignalStrength.GOOD,
        hdop = 1.2,
        vdop = 1.5,
        dataAge = 50L,
        packetLoss = 0.0,
        frequency = 25.0,
        overall = QualityLevel.GOOD,
        overallScore = 85,
    )
}