package com.blazepush.feature.test.usecase

import com.blazepush.feature.test.model.laptiming.CrossingEvent
import com.blazepush.feature.test.model.laptiming.CrossingReason
import com.blazepush.feature.test.model.laptiming.GpsSample
import com.blazepush.feature.test.model.laptiming.LapQualityFlag
import com.blazepush.feature.test.model.laptiming.LapSession
import com.blazepush.feature.test.model.laptiming.LapSessionStatus
import com.blazepush.feature.test.model.track.TimingGate
import com.blazepush.feature.test.model.track.TimingGateType
import com.blazepush.feature.test.repository.PresetTrackCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test


class LapTimingEngineTest {

    private val track = requireNotNull(PresetTrackCatalog().getTrack("preset-tfic-lpcc"))
    private val detector = GateCrossingDetector()
    private val engine = LapTimingEngine(detector)

    @Test
    fun processSample_onJvm_doesNotCrashWhenAcceptedCrossingTriggersDebugLogging() {
        val startFinish = crossingSamples(track.startFinishGate, 1773477876490L, 1773477876690L)
        val startedSession = engine.processSample(
            session = newSession(),
            track = track,
            previousSample = startFinish.first,
            currentSample = startFinish.second
        )

        assertEquals(LapSessionStatus.Recording, startedSession.status)
        assertEquals(1, startedSession.currentLapIndex)
        assertEquals(1, startedSession.nextExpectedGateIndex)
    }

    @Test
    fun processSample_firstStartFinishCrossing_startsLapWithoutCompletingLap() {
        val startedSession = engine.processSample(
            session = newSession(),
            track = track,
            previousSample = crossingSamples(track.startFinishGate, 1773477876490L, 1773477876690L).first,
            currentSample = crossingSamples(track.startFinishGate, 1773477876490L, 1773477876690L).second
        )

        assertEquals(LapSessionStatus.Recording, startedSession.status)
        assertEquals(1, startedSession.currentLapIndex)
        assertEquals(0, startedSession.completedLaps.size)
        assertNotNull(startedSession.activeLap)
        assertEquals(listOf("start-finish"), startedSession.activeLap!!.passedGateIds)
    }

    @Test
    fun processSample_secondStartFinishCrossing_completesLapEvenWithoutAllSectors() {
        val startedSession = engine.processSample(
            session = newSession(),
            track = track,
            previousSample = crossingSamples(track.startFinishGate, 1773477876490L, 1773477876690L).first,
            currentSample = crossingSamples(track.startFinishGate, 1773477876490L, 1773477876690L).second
        )

        val finishedSession = engine.processSample(
            session = startedSession,
            track = track,
            previousSample = crossingSamples(track.startFinishGate, 1773478143490L, 1773478143690L).first,
            currentSample = crossingSamples(track.startFinishGate, 1773478143490L, 1773478143690L).second
        )

        assertEquals(1, finishedSession.completedLaps.size)
        val lap = finishedSession.completedLaps.first()
        assertEquals(1, lap.lapIndex)
        assertEquals(267_000L, lap.durationMillis)
        assertNotNull(finishedSession.activeLap)
        assertEquals(2, finishedSession.currentLapIndex)
    }

    @Test
    fun processSample_startFinishThenOrderedSectorsThenStartFinish_completesLapWithSectorTimes() {
        val startedSession = engine.processSample(
            session = newSession(),
            track = track,
            previousSample = crossingSamples(track.startFinishGate, 1773477876490L, 1773477876690L).first,
            currentSample = crossingSamples(track.startFinishGate, 1773477876490L, 1773477876690L).second
        )

        val sectorOneSession = engine.processSample(
            session = startedSession,
            track = track,
            previousSample = crossingSamples(track.sectorGates[0], 1773478127090L, 1773478127290L).first,
            currentSample = crossingSamples(track.sectorGates[0], 1773478127090L, 1773478127290L).second
        )

        val sectorTwoSession = engine.processSample(
            session = sectorOneSession,
            track = track,
            previousSample = crossingSamples(track.sectorGates[1], 1773478135290L, 1773478135490L).first,
            currentSample = crossingSamples(track.sectorGates[1], 1773478135290L, 1773478135490L).second
        )

        val finishedSession = engine.processSample(
            session = sectorTwoSession,
            track = track,
            previousSample = crossingSamples(track.startFinishGate, 1773478143490L, 1773478143690L).first,
            currentSample = crossingSamples(track.startFinishGate, 1773478143490L, 1773478143690L).second
        )

        val lap = finishedSession.completedLaps.first()
        assertEquals(listOf(250_600L, 8_200L), lap.sectorTimes)
        // 注：本用例的 trajectory 时间戳跨度为分钟级（用于测试 sector 穿线顺序），
        // 相邻差天然超过 200ms 阈值，会触发 ProtocolDesyncGap；
        // 此处不关心该标志，只验证 sectors 完整时不带 IncompleteSectors。
        assertTrue(
            "expected no IncompleteSectors, got=${lap.qualityFlags}",
            !lap.qualityFlags.contains(LapQualityFlag.IncompleteSectors)
        )
        assertEquals(2, finishedSession.currentLapIndex)
        assertEquals(1, finishedSession.completedLaps.size)
    }

    @Test
    fun processSample_missingSectorStillCompletesLapWithIncompleteFlag() {
        val startedSession = engine.processSample(
            session = newSession(),
            track = track,
            previousSample = crossingSamples(track.startFinishGate, 1773477876490L, 1773477876690L).first,
            currentSample = crossingSamples(track.startFinishGate, 1773477876490L, 1773477876690L).second
        )

        val sectorOneSession = engine.processSample(
            session = startedSession,
            track = track,
            previousSample = crossingSamples(track.sectorGates[0], 1773478127090L, 1773478127290L).first,
            currentSample = crossingSamples(track.sectorGates[0], 1773478127090L, 1773478127290L).second
        )

        val finishedSession = engine.processSample(
            session = sectorOneSession,
            track = track,
            previousSample = crossingSamples(track.startFinishGate, 1773478143490L, 1773478143690L).first,
            currentSample = crossingSamples(track.startFinishGate, 1773478143490L, 1773478143690L).second
        )

        val lap = finishedSession.completedLaps.first()
        assertEquals(listOf(250_600L), lap.sectorTimes)
        // 本用例 trajectory ts 跨度为分钟级，同时带 IncompleteSectors 与 ProtocolDesyncGap
        assertTrue(
            "expected IncompleteSectors, got=${lap.qualityFlags}",
            lap.qualityFlags.contains(LapQualityFlag.IncompleteSectors)
        )
    }

    @Test
    fun processSample_outOfOrderSectorIsIgnoredAndLapStillClosesOnNextStartFinish() {
        val startedSession = engine.processSample(
            session = newSession(),
            track = track,
            previousSample = crossingSamples(track.startFinishGate, 1773477876490L, 1773477876690L).first,
            currentSample = crossingSamples(track.startFinishGate, 1773477876490L, 1773477876690L).second
        )

        val outOfOrderSession = engine.processSample(
            session = startedSession,
            track = track,
            previousSample = crossingSamples(track.sectorGates[1], 1773478135290L, 1773478135490L).first,
            currentSample = crossingSamples(track.sectorGates[1], 1773478135290L, 1773478135490L).second
        )

        assertEquals(0, outOfOrderSession.activeLap!!.sectorEntries.size)
        assertEquals(1, outOfOrderSession.nextExpectedGateIndex)

        val finishedSession = engine.processSample(
            session = outOfOrderSession,
            track = track,
            previousSample = crossingSamples(track.startFinishGate, 1773478143490L, 1773478143690L).first,
            currentSample = crossingSamples(track.startFinishGate, 1773478143490L, 1773478143690L).second
        )

        assertEquals(1, finishedSession.completedLaps.size)
        // 本用例 trajectory ts 跨度为分钟级，同时带 IncompleteSectors 与 ProtocolDesyncGap
        assertTrue(
            "expected IncompleteSectors, got=${finishedSession.completedLaps.first().qualityFlags}",
            finishedSession.completedLaps.first().qualityFlags.contains(LapQualityFlag.IncompleteSectors)
        )
    }

    @Test
    fun processSample_lapWithProtocolDesyncGap_isFlagged() {
        // 开圈：起终点穿线（prev=1773477876490, current=1773477876690）
        val startedSession = engine.processSample(
            session = newSession(),
            track = track,
            previousSample = crossingSamples(track.startFinishGate, 1773477876490L, 1773477876690L).first,
            currentSample = crossingSamples(track.startFinishGate, 1773477876490L, 1773477876690L).second
        )

        // 中间插入一帧：与前一帧 ts 差 = 300ms（>200ms 阈值）
        // 取起终点线的远端位置避免误触发 gate
        val gapSamplePrev = sample(
            timestampMillis = 1773477876990L, // 距离上一帧 300ms
            latitude = 0.0,
            longitude = 0.0
        )
        val gapSampleCurrent = sample(
            timestampMillis = 1773477877030L, // 40ms 正常间隔
            latitude = 0.0,
            longitude = 0.0
        )
        val afterGapSession = engine.processSample(
            session = startedSession,
            track = track,
            previousSample = gapSamplePrev,
            currentSample = gapSampleCurrent
        )

        // 闭圈：起终点再次穿线
        val finishedSession = engine.processSample(
            session = afterGapSession,
            track = track,
            previousSample = crossingSamples(track.startFinishGate, 1773478143490L, 1773478143690L).first,
            currentSample = crossingSamples(track.startFinishGate, 1773478143490L, 1773478143690L).second
        )

        assertEquals(1, finishedSession.completedLaps.size)
        val lap = finishedSession.completedLaps.first()
        assertTrue(
            "expected ProtocolDesyncGap flag, got=${lap.qualityFlags}",
            lap.qualityFlags.contains(LapQualityFlag.ProtocolDesyncGap)
        )
        // durationMillis 不扣除失联段
        assertEquals(1773478143690L - 1773477876690L, lap.durationMillis)
    }

    @Test
    fun processSample_lapWithoutGap_isNotFlagged() {
        // 开圈
        val startedSession = engine.processSample(
            session = newSession(),
            track = track,
            previousSample = crossingSamples(track.startFinishGate, 1773477876490L, 1773477876690L).first,
            currentSample = crossingSamples(track.startFinishGate, 1773477876490L, 1773477876690L).second
        )

        // 中间一帧：相邻 ts 差 = 40ms（正常）
        val normalPrev = sample(
            timestampMillis = 1773477876730L, // 距离上一帧 40ms
            latitude = 0.0,
            longitude = 0.0
        )
        val normalCurrent = sample(
            timestampMillis = 1773477876770L, // 40ms
            latitude = 0.0,
            longitude = 0.0
        )
        val afterNormalSession = engine.processSample(
            session = startedSession,
            track = track,
            previousSample = normalPrev,
            currentSample = normalCurrent
        )

        // 闭圈：起终点再次穿线，prev 与上一帧差 40ms
        val finishPrev = sample(
            timestampMillis = 1773477876810L, // 40ms
            latitude = crossingSamples(track.startFinishGate, 0L, 0L).first.latitude,
            longitude = crossingSamples(track.startFinishGate, 0L, 0L).first.longitude
        )
        val finishCurrent = sample(
            timestampMillis = 1773477876850L, // 40ms
            latitude = crossingSamples(track.startFinishGate, 0L, 0L).second.latitude,
            longitude = crossingSamples(track.startFinishGate, 0L, 0L).second.longitude
        )
        val finishedSession = engine.processSample(
            session = afterNormalSession,
            track = track,
            previousSample = finishPrev,
            currentSample = finishCurrent
        )

        assertEquals(1, finishedSession.completedLaps.size)
        val lap = finishedSession.completedLaps.first()
        assertTrue(
            "expected no ProtocolDesyncGap flag, got=${lap.qualityFlags}",
            !lap.qualityFlags.contains(LapQualityFlag.ProtocolDesyncGap)
        )
    }

    @Test
    fun processSample_lapWithCustomInterval5Hz_doesNotFlagDesyncAt200ms() {
        // A7 回归：5Hz 采样正常间隔 200ms，若阈值仍硬编码 200L，浮点抖动让相邻差偶尔 201ms
        // 会触发假阳性 ProtocolDesyncGap。把 expectedIntervalMillis = 200L 传入后，阈值变为
        // 200 × 5 = 1000ms，正常 5Hz 采样不再触发。
        val engine5Hz = LapTimingEngine(
            detector = detector,
            expectedIntervalMillis = 200L
        )

        // 开圈
        val startFinish = crossingSamples(track.startFinishGate, 0L, 200L)
        val startedSession = engine5Hz.processSample(
            session = newSession(),
            track = track,
            previousSample = startFinish.first,
            currentSample = startFinish.second
        )

        // 构造一组 trajectory：5Hz 正常间隔 200ms，间偶尔抖到 201ms（模拟浮点舍入），但远
        // 未到 1000ms 阈值
        var session = startedSession
        val offsetFromStart = track.referencePath.points.first()
        val intervals = listOf(200L, 201L, 199L, 200L, 201L, 200L)
        var ts = 200L
        var prev = startFinish.second
        for (gap in intervals) {
            ts += gap
            val next = sample(
                timestampMillis = ts,
                latitude = offsetFromStart.latitude + 1e-5,
                longitude = offsetFromStart.longitude + 1e-5
            )
            session = engine5Hz.processSample(session, track, prev, next)
            prev = next
        }

        // 闭圈
        val finish = crossingSamples(track.startFinishGate, ts + 200L, ts + 400L)
        val finishedSession = engine5Hz.processSample(
            session = session,
            track = track,
            previousSample = finish.first,
            currentSample = finish.second
        )

        assertEquals(1, finishedSession.completedLaps.size)
        val lap = finishedSession.completedLaps.first()
        assertTrue(
            "5Hz 正常采样不应触发 ProtocolDesyncGap 假阳性；阈值应为 intervalMillis(200) × 5 = 1000ms。got=${lap.qualityFlags}",
            !lap.qualityFlags.contains(LapQualityFlag.ProtocolDesyncGap)
        )
    }

    @Test
    fun processSample_unexpectedGateOrder_recordsRejectedEventAndDoesNotAdvanceSession() {
        val startedSession = engine.processSample(
            session = newSession(),
            track = track,
            previousSample = crossingSamples(track.startFinishGate, 1773477876490L, 1773477876690L).first,
            currentSample = crossingSamples(track.startFinishGate, 1773477876490L, 1773477876690L).second
        )

        val unexpectedGate = engine.processSample(
            session = startedSession,
            track = track,
            previousSample = crossingSamples(track.sectorGates[1], 1773478135290L, 1773478135490L).first,
            currentSample = crossingSamples(track.sectorGates[1], 1773478135290L, 1773478135490L).second
        )

        assertEquals(1, unexpectedGate.nextExpectedGateIndex)
        assertEquals(0, unexpectedGate.activeLap!!.sectorEntries.size)
        assertEquals(0, unexpectedGate.completedLaps.size)

        val lastEvent = unexpectedGate.crossingEvents.last()
        assertEquals(false, lastEvent.accepted)
        assertEquals(CrossingReason.UnexpectedGateOrder, lastEvent.reason)
    }

    // ==================== 战役 C engine 入口夯实 A19 / A21 回归 ====================
    //
    // Change: openspec/changes/fix-lap-timing-engine-entry-hardening
    //   Requirement 1: engine 入口 LapSessionStatus 白名单守卫（A19）
    //   Requirement 2: engine 入口 ts 单调守卫（A21 入口层）
    //   Requirement 3: crossingEvents 裁剪 filter 严格语义（A21 裁剪层）

    @Test
    fun processSample_onFinishedSession_returnsUnchanged() {
        // A19 R1 Scenario 1：Finished 状态 MUST 被白名单拦下
        val finishedSession = newSession().copy(
            status = LapSessionStatus.Finished,
            samples = List(3) { idx ->
                sample(timestampMillis = 1_000L + idx * 40L, latitude = 0.0, longitude = 0.0)
            }
        )
        val newPrev = sample(timestampMillis = 2_000L, latitude = 0.0, longitude = 0.0)
        val newCurrent = sample(timestampMillis = 2_040L, latitude = 0.0, longitude = 0.0)

        val result = engine.processSample(
            session = finishedSession,
            track = track,
            previousSample = newPrev,
            currentSample = newCurrent
        )

        assertEquals(LapSessionStatus.Finished, result.status)
        assertEquals(3, result.samples.size)
        assertEquals(finishedSession.completedLaps, result.completedLaps)
        assertEquals(finishedSession.activeLap, result.activeLap)
        assertEquals(finishedSession.crossingEvents, result.crossingEvents)
        assertEquals(finishedSession.currentLapIndex, result.currentLapIndex)
    }

    @Test
    fun processSample_onCancelledSession_returnsUnchanged() {
        // A19 R1 Scenario 2：Cancelled 状态 MUST 被白名单拦下
        val cancelledSession = newSession().copy(
            status = LapSessionStatus.Cancelled,
            samples = listOf(sample(timestampMillis = 1_000L, latitude = 0.0, longitude = 0.0))
        )

        val result = engine.processSample(
            session = cancelledSession,
            track = track,
            previousSample = sample(1_000L, 0.0, 0.0),
            currentSample = sample(1_040L, 0.0, 0.0)
        )

        assertEquals(LapSessionStatus.Cancelled, result.status)
        assertEquals(1, result.samples.size)
    }

    @Test
    fun processSample_onIdleSession_returnsUnchanged() {
        // A19 R1 Scenario 3：Idle 状态 MUST 被白名单拦下
        val idleSession = newSession().copy(status = LapSessionStatus.Idle)

        val result = engine.processSample(
            session = idleSession,
            track = track,
            previousSample = sample(1_000L, 0.0, 0.0),
            currentSample = sample(1_040L, 0.0, 0.0)
        )

        assertEquals(LapSessionStatus.Idle, result.status)
        assertEquals(0, result.samples.size)
        assertEquals(null, result.activeLap)
    }

    @Test
    fun processSample_onReadySession_acceptsSampleAndStartsLap() {
        // A19 R1 Scenario 4：Ready 白名单放行 + 首次起终点过线推进到 Recording
        val crossing = crossingSamples(track.startFinishGate, 1773477876490L, 1773477876690L)

        val result = engine.processSample(
            session = newSession(),
            track = track,
            previousSample = crossing.first,
            currentSample = crossing.second
        )

        assertEquals(LapSessionStatus.Recording, result.status)
        assertNotNull(result.activeLap)
        assertEquals(1, result.activeLap!!.lapIndex)
        assertEquals(1, result.samples.size)
    }

    @Test
    fun processSample_onRecordingSession_acceptsSampleAndAdvances() {
        // A19 R1 Scenario 5：Recording 白名单放行 + 正常 sector 推进
        val startedSession = engine.processSample(
            session = newSession(),
            track = track,
            previousSample = crossingSamples(track.startFinishGate, 1773477876490L, 1773477876690L).first,
            currentSample = crossingSamples(track.startFinishGate, 1773477876490L, 1773477876690L).second
        )
        assertEquals(LapSessionStatus.Recording, startedSession.status)

        val sectorCross = crossingSamples(track.sectorGates[0], 1773478127090L, 1773478127290L)
        val advanced = engine.processSample(
            session = startedSession,
            track = track,
            previousSample = sectorCross.first,
            currentSample = sectorCross.second
        )

        assertEquals(LapSessionStatus.Recording, advanced.status)
        assertEquals(startedSession.samples.size + 1, advanced.samples.size)
        assertEquals(1, advanced.activeLap!!.sectorEntries.size)
    }

    @Test
    fun processSample_timestampRegressionSample_returnsUnchanged() {
        // A21 R2 Scenario 1：ts 回跳样本必须被 engine 入口守卫整帧丢弃
        val startedSession = engine.processSample(
            session = newSession(),
            track = track,
            previousSample = crossingSamples(track.startFinishGate, 1773477876490L, 1773477876690L).first,
            currentSample = crossingSamples(track.startFinishGate, 1773477876490L, 1773477876690L).second
        )

        val beforeSize = startedSession.samples.size
        val beforeCrossings = startedSession.crossingEvents.size

        val regressedPrev = sample(timestampMillis = 500L, latitude = 0.0, longitude = 0.0)
        val regressedCurrent = sample(timestampMillis = 400L, latitude = 0.0, longitude = 0.0)

        val result = engine.processSample(
            session = startedSession,
            track = track,
            previousSample = regressedPrev,
            currentSample = regressedCurrent
        )

        assertEquals(beforeSize, result.samples.size)
        assertEquals(beforeCrossings, result.crossingEvents.size)
        assertEquals(startedSession.activeLap, result.activeLap)
        assertEquals(startedSession.completedLaps, result.completedLaps)
    }

    @Test
    fun processSample_firstSampleOnEmptySession_noRegressionCheckApplies() {
        // A21 R2 Scenario 2：首次起圈 session.samples 为空，previousSample 是方法参数永远非空；
        // 守卫基准是 previousSample.timestampMillis，不应因 session.samples 空而误拦。
        val emptyReady = newSession()
        assertEquals(0, emptyReady.samples.size)

        val crossing = crossingSamples(track.startFinishGate, 1773477876490L, 1773477876690L)
        // crossing.first.ts < crossing.second.ts，不触发 ts 回跳

        val result = engine.processSample(
            session = emptyReady,
            track = track,
            previousSample = crossing.first,
            currentSample = crossing.second
        )

        assertEquals(LapSessionStatus.Recording, result.status)
        assertEquals(1, result.samples.size)
    }

    @Test
    fun handleStartFinishCrossing_monotonicCrossingEvents_filterRetainsAllAboveStartedAt() {
        // A21 R3 Scenario 1：单调序列 filter 保留所有 ts >= startedAtMillis 的事件（含边界 ts == startedAt）
        // 构造 startedAtMillis = 200L + crossingEvents = [100, 200, 300, 400]，闭圈 currentSample.ts = 500
        // 期望 LapRecord.crossingEvents 的 timestampMillis 顺序 = [200, 300, 400, 500]
        //   （其中 500 是闭圈帧自己产生的 CrossingEvent）
        val opened = openLapAt(startedAtMillis = 200L)
        val withHistory = opened.copy(
            crossingEvents = listOf(
                historicalCrossing(100L),
                historicalCrossing(200L),
                historicalCrossing(300L),
                historicalCrossing(400L)
            )
        )

        val closeCrossing = crossingSamples(track.startFinishGate, 499L, 500L)
        val closed = engine.processSample(
            session = withHistory,
            track = track,
            previousSample = closeCrossing.first,
            currentSample = closeCrossing.second
        )

        assertEquals(1, closed.completedLaps.size)
        val lap = closed.completedLaps.first()
        assertEquals(
            listOf(200L, 300L, 400L, 500L),
            lap.crossingEvents.map { it.timestampMillis }
        )
    }

    @Test
    fun handleStartFinishCrossing_outOfOrderHistoricalEventHardDistinguishesFilterVsDropWhile() {
        // A21 R3 Scenario 2：非单调序列含 ts < startedAt 夹后，filter 拒收；v1 dropWhile 会漏拦。
        // crossingEvents = [100, 250, 150, 400] + startedAtMillis = 200L
        //   v1 (dropWhile): 100 drop, 250 停止 → [250, 150, 400] + 闭圈帧 → [250, 150, 400, 500]
        //   v2 (filter):    逐元素 → [250, 400] + 闭圈帧 → [250, 400, 500]
        //   断言 == [250, 400, 500] 硬区分 v1/v2
        val opened = openLapAt(startedAtMillis = 200L)
        val withOutOfOrder = opened.copy(
            crossingEvents = listOf(
                historicalCrossing(100L),
                historicalCrossing(250L),
                historicalCrossing(150L), // 历史事件夹在后面
                historicalCrossing(400L)
            )
        )

        val closeCrossing = crossingSamples(track.startFinishGate, 499L, 500L)
        val closed = engine.processSample(
            session = withOutOfOrder,
            track = track,
            previousSample = closeCrossing.first,
            currentSample = closeCrossing.second
        )

        assertEquals(1, closed.completedLaps.size)
        val lap = closed.completedLaps.first()
        assertEquals(
            listOf(250L, 400L, 500L),
            lap.crossingEvents.map { it.timestampMillis }
        )
        // 硬区分对照：若 A21 回退为 dropWhile，同一输入会得到 [250, 150, 400, 500]（ts=150 漏拦）
        assertFalse(
            "v1 dropWhile 会保留 ts=150 的历史事件，本次 filter 必须拒收",
            lap.crossingEvents.any { it.timestampMillis == 150L }
        )
    }

    @Test
    fun handleStartFinishCrossing_monotonicSequence_filterOutputEqualsDropWhileOutput() {
        // A21 R3 Scenario 3：单调序列 filter 输出与 dropWhile 输出等价（防退化回归保护）
        // startedAtMillis = 150L + crossingEvents = [100, 200, 300, 400]，闭圈 current.ts = 500
        // 两者输出都应是 [200, 300, 400, 500]
        val opened = openLapAt(startedAtMillis = 150L)
        val historicalEvents = listOf(
            historicalCrossing(100L),
            historicalCrossing(200L),
            historicalCrossing(300L),
            historicalCrossing(400L)
        )
        val withHistory = opened.copy(crossingEvents = historicalEvents)

        val closeCrossing = crossingSamples(track.startFinishGate, 499L, 500L)
        val closed = engine.processSample(
            session = withHistory,
            track = track,
            previousSample = closeCrossing.first,
            currentSample = closeCrossing.second
        )

        assertEquals(1, closed.completedLaps.size)
        val lap = closed.completedLaps.first()
        val filterOutput = lap.crossingEvents.map { it.timestampMillis }

        // 用闭圈帧同步构造等价的 updatedEvents 序列再跑一次 dropWhile 做对照
        val closingEvent = lap.crossingEvents.last() // 闭圈帧自己产生的
        val replayUpdatedEvents = historicalEvents + closingEvent
        val dropWhileOutput = replayUpdatedEvents
            .dropWhile { it.timestampMillis < 150L }
            .map { it.timestampMillis }

        assertEquals(listOf(200L, 300L, 400L, 500L), filterOutput)
        // 防退化断言：单调场景下 filter 与 dropWhile 输出逐元素相等
        assertEquals(
            "单调序列 filter 应与 dropWhile 完全等价，否则防退化契约被破坏",
            dropWhileOutput,
            filterOutput
        )
    }

    /**
     * 起圈 helper：先通过一次正常起终点过线把 session 推到 Recording，再 copy
     * 指定 startedAtMillis，便于 R3 测试在稳定的 activeLap 基础上注入历史事件。
     * 注意：copy 不会改变原有 startFinish 过线产生的 CrossingEvent（ts = startedAtMillis 附近），
     * R3 测试会在 copy 时 **整体替换** `crossingEvents` 字段，因此 helper 返回的基准
     * 不带 "起圈原始事件" 这一项，干扰。
     */
    private fun openLapAt(startedAtMillis: Long): LapSession {
        val crossing = crossingSamples(track.startFinishGate, startedAtMillis - 1L, startedAtMillis)
        val started = engine.processSample(
            session = newSession(),
            track = track,
            previousSample = crossing.first,
            currentSample = crossing.second
        )
        return started
    }

    private fun historicalCrossing(timestampMillis: Long): CrossingEvent = CrossingEvent(
        gateId = track.startFinishGate.id,
        gateType = TimingGateType.StartFinish,
        timestampMillis = timestampMillis,
        sampleIndex = 0,
        accepted = true,
        reason = CrossingReason.Accepted,
        directionalSpeedMps = 10.0,
        directionScore = 1.0
    )

    private fun newSession(): LapSession = LapSession(
        sessionId = "session-1",
        trackId = track.id,
        status = LapSessionStatus.Ready
    )

    private fun crossingSamples(gate: TimingGate, previousTimestamp: Long, currentTimestamp: Long): Pair<GpsSample, GpsSample> {
        val centerLatitude = (gate.line.start.latitude + gate.line.end.latitude) / 2.0
        val centerLongitude = (gate.line.start.longitude + gate.line.end.longitude) / 2.0
        val offsetScale = 0.25
        return sample(
            timestampMillis = previousTimestamp,
            latitude = centerLatitude - (gate.passDirection.x * offsetScale),
            longitude = centerLongitude - (gate.passDirection.y * offsetScale)
        ) to sample(
            timestampMillis = currentTimestamp,
            latitude = centerLatitude + (gate.passDirection.x * offsetScale),
            longitude = centerLongitude + (gate.passDirection.y * offsetScale)
        )
    }

    private fun sample(
        timestampMillis: Long,
        latitude: Double,
        longitude: Double
    ): GpsSample = GpsSample(
        timestampMillis = timestampMillis,
        latitude = latitude,
        longitude = longitude,
        speedKmh = 36.0
    )
}
