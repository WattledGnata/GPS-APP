package com.blazepush.feature.test.usecase

import com.blazepush.feature.test.model.laptiming.ActiveLap
import com.blazepush.feature.test.model.laptiming.CrossingEvent
import com.blazepush.feature.test.model.laptiming.CrossingReason
import com.blazepush.feature.test.model.laptiming.GpsSample
import com.blazepush.feature.test.model.laptiming.LapQualityFlag
import com.blazepush.feature.test.model.laptiming.LapSession
import com.blazepush.feature.test.model.laptiming.LapSessionStatus
import com.blazepush.feature.test.model.track.GeoLine
import com.blazepush.feature.test.model.track.GeoPoint
import com.blazepush.feature.test.model.track.GeoVector
import com.blazepush.feature.test.model.track.TimingGate
import com.blazepush.feature.test.model.track.TimingGateType
import com.blazepush.feature.test.model.track.Track
import com.blazepush.feature.test.model.track.TrackPath
import com.blazepush.feature.test.repository.PresetTrackCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
        // R7：A33 断言补齐 —— 两次起点过线中间完全不穿任何 sector 应产生 IncompleteSectors flag
        assertEquals(
            "R7 A33：缺 sector 闭圈 MUST 带 IncompleteSectors 标签",
            listOf(LapQualityFlag.IncompleteSectors),
            lap.qualityFlags
        )
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
    fun handleStartFinishCrossing_monotonicCrossingEvents_filterRetainsAllStrictlyGreaterThanStartedAt() {
        // A21 R3 Scenario 1（R5 修订 `>=` → `>` 严格大于，边界事件归前一圈）：
        // 单调序列 filter 保留所有 ts **严格大于** startedAtMillis 的事件
        // 构造 startedAtMillis = 200L + crossingEvents = [100, 200, 300, 400]，闭圈插值 ts = 500
        // v2 期望 LapRecord.crossingEvents = [300, 400, 500]（边界 ts=200 排除，归前一圈）
        // v1 `>=` 曾期望 [200, 300, 400, 500]（含边界），本 change MODIFIED 段修订
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
            "v2 严格 `>` 排除边界 ts=200，归前一圈",
            listOf(300L, 400L, 500L),
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
        // A21 R3 Scenario 3（R5 修订后）：单调序列 filter 严格 `>` 输出与 dropWhile `<=` 对偶输出等价（防退化回归保护）
        // startedAtMillis = 150L + crossingEvents = [100, 200, 300, 400]，闭圈插值 ts = 500
        // filter `> 150` → [200, 300, 400, 500]（无 ts=150 边界事件，不涉及 R5 边界碰撞）
        // dropWhile `<= 150` 对偶 → [200, 300, 400, 500]（两者逐元素等价）
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

        // 用闭圈帧同步构造等价的 updatedEvents 序列再跑一次 dropWhile 对偶对照
        // R5 改 filter 严格 `>` 后，dropWhile 对偶谓词应为 `<=`（与 `>` 对偶）
        val closingEvent = lap.crossingEvents.last() // 闭圈帧自己产生的
        val replayUpdatedEvents = historicalEvents + closingEvent
        val dropWhileOutput = replayUpdatedEvents
            .dropWhile { it.timestampMillis <= 150L }
            .map { it.timestampMillis }

        assertEquals(listOf(200L, 300L, 400L, 500L), filterOutput)
        // 防退化断言：单调场景下 filter `>` 与 dropWhile `<=` 对偶输出逐元素相等
        assertEquals(
            "单调序列 filter 应与 dropWhile 对偶完全等价，否则防退化契约被破坏",
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

    // ==================== openspec fix-lap-timing-closure-and-precision-contract R2 / R3 ====================
    //
    // R2 Requirement: LapTimingEngine 使用插值毫秒时刻构造 ActiveLap / LapRecord / CrossingEvent / SectorEntry
    // R3 Requirement: LapRecord.trajectory 按时间窗口 [startedAt, finishedAt) 切分（subList + filter）
    //
    // 关键观察：crossingSamples 构造对称偏移 0.25×passDirection，过线 t=0.5 精确；
    //   插值时刻 = (prevTs + currentTs) / 2；对称 fixture 下 v1/v2 数值等价仍可断言 ==

    @Test
    fun processSample_symmetricCrossing_crossingEventTimestampIsInterpolatedMillis() {
        // R2 Scenario 1：对称过线构造的 CrossingEvent.timestampMillis 精确位于 prev/current 中点
        val startedSession = engine.processSample(
            session = newSession(),
            track = track,
            previousSample = crossingSamples(track.startFinishGate, 200L, 240L).first,
            currentSample = crossingSamples(track.startFinishGate, 200L, 240L).second
        )

        val startFinishEvent = startedSession.crossingEvents.first { it.gateId == "start-finish" }
        // t=0.5 → interpolated = (200 + 240) / 2 = 220
        assertEquals("对称过线 event.timestampMillis 应等于 prev/current 中点 220L", 220L, startFinishEvent.timestampMillis)
        assertEquals("sampleIndex 应等于触发帧索引 updatedSamples.lastIndex", 0, startFinishEvent.sampleIndex)
    }

    @Test
    fun processSample_symmetricCrossing_activeLapStartedAtIsInterpolatedMillis() {
        // R2 Scenario 2：ActiveLap.startedAtMillis 是插值时刻（不等于 currentSample.ts）
        val startedSession = engine.processSample(
            session = newSession(),
            track = track,
            previousSample = crossingSamples(track.startFinishGate, 200L, 240L).first,
            currentSample = crossingSamples(track.startFinishGate, 200L, 240L).second
        )

        assertNotNull(startedSession.activeLap)
        assertEquals("activeLap.startedAtMillis 应为插值时刻 220L", 220L, startedSession.activeLap!!.startedAtMillis)
        assertNotEquals("activeLap.startedAtMillis 应不等于 currentSample.ts (240L)", 240L, startedSession.activeLap!!.startedAtMillis)
    }

    @Test
    fun processSample_symmetricBothCrossings_durationMillisEquivalentToV1FrameLevel() {
        // R2 Scenario 3：对称开圈 + 对称闭圈，durationMillis 与 v1 帧粒度差相消等价
        // 开圈 (200, 240, t=0.5) → startedAtMillis=220
        // 闭圈 (10_200, 10_240, t=0.5) → finishedAtMillis=10_220
        // durationMillis = 10_220 - 220 = 10_000L
        // v1 帧粒度: 10_240 - 240 = 10_000L，数值恰好等价
        val opened = engine.processSample(
            session = newSession(),
            track = track,
            previousSample = crossingSamples(track.startFinishGate, 200L, 240L).first,
            currentSample = crossingSamples(track.startFinishGate, 200L, 240L).second
        )
        val closed = engine.processSample(
            session = opened,
            track = track,
            previousSample = crossingSamples(track.startFinishGate, 10_200L, 10_240L).first,
            currentSample = crossingSamples(track.startFinishGate, 10_200L, 10_240L).second
        )
        assertEquals(1, closed.completedLaps.size)
        val lap = closed.completedLaps.first()
        assertEquals("对称过线 startedAtMillis=220L", 220L, lap.startedAtMillis)
        assertEquals("对称过线 finishedAtMillis=10_220L", 10_220L, lap.finishedAtMillis)
        assertEquals("对称过线 durationMillis=10_000L（与 v1 帧粒度差相消等价）", 10_000L, lap.durationMillis)
    }

    @Test
    fun processSample_asymmetricClosingCrossing_durationMillisReflectsInterpolation() {
        // R2 Scenario 4：不对称闭圈硬区分 v1 帧粒度
        // 开圈对称 (200, 240, t=0.5) → startedAtMillis=220
        // 闭圈不对称 (10_200, 10_240, t=0.25) → finishedAtMillis=10_210
        //   构造不对称：current 位于 passDirection 方向偏移 0.5 个向量长度（比 prev 远更多），
        //   使过线点更靠近 prev 侧，t=0.25
        // durationMillis = 10_210 - 220 = 9_990L（硬区分 v1 = 10_000L）
        val opened = engine.processSample(
            session = newSession(),
            track = track,
            previousSample = crossingSamples(track.startFinishGate, 200L, 240L).first,
            currentSample = crossingSamples(track.startFinishGate, 200L, 240L).second
        )
        // 不对称闭圈：prev 反向偏 0.25×passDirection，current 正向偏 0.75×passDirection（3× prev 偏移）
        val gate = track.startFinishGate
        val centerLat = (gate.line.start.latitude + gate.line.end.latitude) / 2.0
        val centerLon = (gate.line.start.longitude + gate.line.end.longitude) / 2.0
        val asymmetricPrev = sample(
            timestampMillis = 10_200L,
            latitude = centerLat - gate.passDirection.x * 0.25,
            longitude = centerLon - gate.passDirection.y * 0.25
        )
        val asymmetricCurrent = sample(
            timestampMillis = 10_240L,
            latitude = centerLat + gate.passDirection.x * 0.75,
            longitude = centerLon + gate.passDirection.y * 0.75
        )
        val closed = engine.processSample(
            session = opened,
            track = track,
            previousSample = asymmetricPrev,
            currentSample = asymmetricCurrent
        )

        assertEquals(1, closed.completedLaps.size)
        val lap = closed.completedLaps.first()
        assertEquals("开圈 startedAtMillis=220L", 220L, lap.startedAtMillis)
        // t=0.25 → finishedAtMillis = 10_200 + 0.25 × 40 = 10_210
        assertEquals("不对称闭圈 finishedAtMillis=10_210L (t=0.25)", 10_210L, lap.finishedAtMillis)
        assertEquals("不对称闭圈 durationMillis=9_990L (硬区分 v1 的 10_000L)", 9_990L, lap.durationMillis)
    }

    @Test
    fun processSample_sectorCrossing_sectorEntryCrossedAtMillisIsInterpolated() {
        // R2 Scenario 5：SectorEntry.crossedAtMillis 用插值毫秒
        val opened = engine.processSample(
            session = newSession(),
            track = track,
            previousSample = crossingSamples(track.startFinishGate, 200L, 240L).first,
            currentSample = crossingSamples(track.startFinishGate, 200L, 240L).second
        )
        // 对称 sector 过线：prev.ts=5_200, current.ts=5_240, t=0.5 → crossedAt=5_220
        val sectored = engine.processSample(
            session = opened,
            track = track,
            previousSample = crossingSamples(track.sectorGates[0], 5_200L, 5_240L).first,
            currentSample = crossingSamples(track.sectorGates[0], 5_200L, 5_240L).second
        )

        val sectorEntry = sectored.activeLap!!.sectorEntries.first()
        assertEquals("对称 sector 过线 crossedAtMillis=5_220L (插值)", 5_220L, sectorEntry.crossedAtMillis)
        assertNotEquals("crossedAtMillis 应不等于 currentSample.ts (5_240L)", 5_240L, sectorEntry.crossedAtMillis)
    }

    @Test
    fun crossingEvent_sampleIndexIsTriggeringFrameIndex_notCrossingTimestampFrame() {
        // R2 Scenario 6：CrossingEvent.sampleIndex 是触发帧索引（诊断语义，非边界场景）
        val started = engine.processSample(
            session = newSession(),
            track = track,
            previousSample = crossingSamples(track.startFinishGate, 200L, 240L).first,
            currentSample = crossingSamples(track.startFinishGate, 200L, 240L).second
        )

        val event = started.crossingEvents.first()
        val samples = started.samples
        assertEquals("sampleIndex 等于 updatedSamples.lastIndex", samples.lastIndex, event.sampleIndex)
        // samples[sampleIndex] 是 currentSample (ts=240)，event.timestampMillis 是插值 220
        assertNotEquals(
            "samples[event.sampleIndex].ts (240) 应不等于 event.timestampMillis (220) —— 诊断语义与插值时刻分离",
            samples[event.sampleIndex].timestampMillis,
            event.timestampMillis
        )
    }

    @Test
    fun handleStartFinishCrossing_closingFrame_notIncludedInClosedLapTrajectory() {
        // R3 Scenario 1：闭圈 trajectory 不含闭圈时刻对应帧
        // 开圈 (200, 240) → startedAtMillis=220
        // 喂若干中间帧
        // 闭圈 (10_200, 10_240) → finishedAtMillis=10_220
        // trajectory 应不含 ts=10_240 那帧（在闭圈插值时刻之后）
        val opened = engine.processSample(
            session = newSession(),
            track = track,
            previousSample = crossingSamples(track.startFinishGate, 200L, 240L).first,
            currentSample = crossingSamples(track.startFinishGate, 200L, 240L).second
        )
        val closed = engine.processSample(
            session = opened,
            track = track,
            previousSample = crossingSamples(track.startFinishGate, 10_200L, 10_240L).first,
            currentSample = crossingSamples(track.startFinishGate, 10_200L, 10_240L).second
        )

        val lap = closed.completedLaps.first()
        assertTrue(
            "trajectory.none { it.ts >= finishedAtMillis (10_220) } —— 闭圈帧 ts=10_240 被排除",
            lap.trajectory.none { it.timestampMillis >= lap.finishedAtMillis }
        )
        assertTrue(
            "trajectory 末帧 ts < finishedAtMillis (10_220)",
            lap.trajectory.last().timestampMillis < lap.finishedAtMillis
        )
    }

    @Test
    fun handleStartFinishCrossing_nextActiveLapSampleStartIndex_pointsToClosingFrame() {
        // R3 Scenario 2：第 N+1 圈 ActiveLap.sampleStartIndex 指向闭圈帧索引
        val opened = engine.processSample(
            session = newSession(),
            track = track,
            previousSample = crossingSamples(track.startFinishGate, 200L, 240L).first,
            currentSample = crossingSamples(track.startFinishGate, 200L, 240L).second
        )
        val closed = engine.processSample(
            session = opened,
            track = track,
            previousSample = crossingSamples(track.startFinishGate, 10_200L, 10_240L).first,
            currentSample = crossingSamples(track.startFinishGate, 10_200L, 10_240L).second
        )

        val nextActiveLap = closed.activeLap!!
        // samples.lastIndex 是刚追加的闭圈帧（currentSample）
        assertEquals(
            "下一圈 ActiveLap.sampleStartIndex 指向闭圈帧索引（= samples.lastIndex）",
            closed.samples.lastIndex,
            nextActiveLap.sampleStartIndex
        )
        assertEquals(
            "下一圈 ActiveLap.startedAtMillis 为闭圈插值时刻 10_220",
            10_220L,
            nextActiveLap.startedAtMillis
        )
    }

    @Test
    fun session_samplesSize_equalsSumOfLapTrajectoriesAndActiveLapSegment() {
        // R3 Scenario 3：session.samples.size == Σ completedLaps.trajectory.size + (samples.size - activeLap.sampleStartIndex)
        // 跑 2 个完整圈 + 第 3 圈开圈（尚未闭圈），断言严格等式成立
        var session = newSession()
        session = engine.processSample(
            session = session,
            track = track,
            previousSample = crossingSamples(track.startFinishGate, 200L, 240L).first,
            currentSample = crossingSamples(track.startFinishGate, 200L, 240L).second
        )
        // Lap 1 闭圈 = Lap 2 开圈
        session = engine.processSample(
            session = session,
            track = track,
            previousSample = crossingSamples(track.startFinishGate, 10_200L, 10_240L).first,
            currentSample = crossingSamples(track.startFinishGate, 10_200L, 10_240L).second
        )
        // Lap 2 闭圈 = Lap 3 开圈
        session = engine.processSample(
            session = session,
            track = track,
            previousSample = crossingSamples(track.startFinishGate, 20_200L, 20_240L).first,
            currentSample = crossingSamples(track.startFinishGate, 20_200L, 20_240L).second
        )

        assertEquals(2, session.completedLaps.size)
        val totalTrajectorySize = session.completedLaps.sumOf { it.trajectory.size }
        val activeSegmentSize = session.samples.size - session.activeLap!!.sampleStartIndex
        assertEquals(
            "samples.size MUST 等于所有圈 trajectory.size 之和 + 活跃段",
            session.samples.size,
            totalTrajectorySize + activeSegmentSize
        )
    }

    @Test
    fun handleStartFinishCrossing_subListStartIndexOutOfWindow_filterExcludesOutOfBoundFrames() {
        // R3 Scenario 4：filter 兜底排除 ts < startedAtMillis 的 subList 起点越界帧
        // 构造越界态：ActiveLap.sampleStartIndex 指向比 startedAtMillis 更早的帧
        // 直接构造 session 带 activeLap（绕过 engine 主流程 A38 守卫）
        val activeLap = ActiveLap(
            lapIndex = 1,
            startedAtMillis = 220L,
            passedGateIds = listOf(track.startFinishGate.id),
            sampleStartIndex = 0  // 指向 samples[0]，但 samples[0].ts 会 < startedAtMillis
        )
        val outOfBoundFrame = sample(timestampMillis = 180L, latitude = 0.0, longitude = 0.0)
        val inBoundFrame = sample(timestampMillis = 500L, latitude = 0.0, longitude = 0.0)
        val sessionWithOutOfBound = newSession().copy(
            status = LapSessionStatus.Recording,
            samples = listOf(outOfBoundFrame, inBoundFrame),
            startedAtMillis = 220L,
            currentLapIndex = 1,
            nextExpectedGateIndex = 1,
            activeLap = activeLap
        )

        // 触发闭圈
        val closed = engine.processSample(
            session = sessionWithOutOfBound,
            track = track,
            previousSample = crossingSamples(track.startFinishGate, 10_200L, 10_240L).first,
            currentSample = crossingSamples(track.startFinishGate, 10_200L, 10_240L).second
        )

        val lap = closed.completedLaps.first()
        assertTrue(
            "filter 兜底排除 ts < 220 的越界帧",
            lap.trajectory.none { it.timestampMillis < 220L }
        )
        assertTrue(
            "trajectory 首帧 ts >= 220 (startedAtMillis)",
            lap.trajectory.isEmpty() || lap.trajectory.first().timestampMillis >= 220L
        )
    }

    // ==================== R4 handleSectorCrossing 多门遍历 + state 推进分支 ====================

    @Test
    fun handleSectorCrossing_expectedGateAccepted_advancesState() {
        // R4 Scenario 1：期待门 accepted 推进 state + 记 CrossingEvent
        val opened = engine.processSample(
            session = newSession(),
            track = track,
            previousSample = crossingSamples(track.startFinishGate, 1773477876490L, 1773477876690L).first,
            currentSample = crossingSamples(track.startFinishGate, 1773477876490L, 1773477876690L).second
        )
        val initialSectorSize = opened.activeLap!!.sectorEntries.size
        val initialCrossingSize = opened.crossingEvents.size
        val initialNextExpected = opened.nextExpectedGateIndex

        val result = engine.processSample(
            session = opened,
            track = track,
            previousSample = crossingSamples(track.sectorGates[0], 1773478127090L, 1773478127290L).first,
            currentSample = crossingSamples(track.sectorGates[0], 1773478127090L, 1773478127290L).second
        )

        val resultActiveLap = result.activeLap!!
        assertEquals("sectorEntries +1", initialSectorSize + 1, resultActiveLap.sectorEntries.size)
        assertEquals(
            "最后 SectorEntry.gateId == 期待门 id",
            track.sectorGates[0].id,
            resultActiveLap.sectorEntries.last().gateId
        )
        assertEquals(
            "passedGateIds.last == 期待门 id",
            track.sectorGates[0].id,
            resultActiveLap.passedGateIds.last()
        )
        assertEquals("nextExpectedGateIndex +1", initialNextExpected + 1, result.nextExpectedGateIndex)
        assertEquals("crossingEvents +1（仅期待门 event）", initialCrossingSize + 1, result.crossingEvents.size)
        val lastEvent = result.crossingEvents.last()
        assertEquals(true, lastEvent.accepted)
        assertEquals(CrossingReason.Accepted, lastEvent.reason)
    }

    @Test
    fun handleSectorCrossing_expectedGateRejected_stateUnchanged() {
        // R4 Scenario 2：期待门 rejected → state 保持不变 + 仅记 event
        // 构造期待门 NoIntersection rejected：prev/current 同侧（不穿 gate）
        val opened = engine.processSample(
            session = newSession(),
            track = track,
            previousSample = crossingSamples(track.startFinishGate, 1773477876490L, 1773477876690L).first,
            currentSample = crossingSamples(track.startFinishGate, 1773477876490L, 1773477876690L).second
        )
        val initialSectorSize = opened.activeLap!!.sectorEntries.size
        val initialCrossingSize = opened.crossingEvents.size
        val initialNextExpected = opened.nextExpectedGateIndex

        // 构造不穿任何 sector 门的 prev/current（位置远离所有 gate）
        val farPrev = sample(timestampMillis = 1773478127090L, latitude = 0.0, longitude = 0.0)
        val farCurrent = sample(timestampMillis = 1773478127290L, latitude = 0.0, longitude = 0.0)
        val result = engine.processSample(
            session = opened,
            track = track,
            previousSample = farPrev,
            currentSample = farCurrent
        )

        // state 各字段保持不变
        val resultActiveLap = result.activeLap!!
        val openedActiveLap = opened.activeLap!!
        assertEquals("sectorEntries 不变", initialSectorSize, resultActiveLap.sectorEntries.size)
        assertEquals("passedGateIds 不变", openedActiveLap.passedGateIds, resultActiveLap.passedGateIds)
        assertEquals("nextExpectedGateIndex 不变", initialNextExpected, result.nextExpectedGateIndex)
        // crossingEvents +1（记期待门 rejected event）
        assertEquals("crossingEvents +1 仅期待门 rejected event", initialCrossingSize + 1, result.crossingEvents.size)
        assertEquals(false, result.crossingEvents.last().accepted)
    }

    @Test
    fun handleSectorCrossing_multiGateAcceptedInSingleStep_recordsAllWithOrdering() {
        // R4 Scenario 3 / P2-1：多门同帧 accepted 全部记录（期待门 + 2 非期待门 = 3 条）
        // 硬区分 v1（firstOrNull accepted 只记 1 条）vs v2（全记 + 期待门先 + 非期待门按 sequenceIndex）
        //
        // 构造测试专用 3-sector track：S1/S2/S3 水平 gate 线分别在 lat=1/2/3，passDirection 向北。
        // 喂一对 (prev=lat=0.5, current=lat=3.5) 向北大位移线段，几何上同时穿 S1/S2/S3 三门。
        val testTrack = threeSectorTrack(sectorOrder = listOf("S1", "S2", "S3"))

        // 先过 startFinish（位于 lat=0）开圈
        val openStartFinishPair = crossStartFinishOf(testTrack, prevTs = 100L, currentTs = 200L)
        val opened = engine.processSample(
            session = newSession(trackId = testTrack.id),
            track = testTrack,
            previousSample = openStartFinishPair.first,
            currentSample = openStartFinishPair.second
        )
        assertEquals(LapSessionStatus.Recording, opened.status)
        val initialCrossingSize = opened.crossingEvents.size

        // 喂一对跨 S1/S2/S3 三门的 (prev, current)：沿 lat 方向从 0.5 到 3.5
        val multiGatePrev = sample(timestampMillis = 1_000L, latitude = 0.5, longitude = 0.0)
        val multiGateCurrent = sample(timestampMillis = 2_000L, latitude = 3.5, longitude = 0.0)
        val result = engine.processSample(
            session = opened,
            track = testTrack,
            previousSample = multiGatePrev,
            currentSample = multiGateCurrent
        )

        // 硬断言：crossingEvents +3（v1 只 +1 的退化实现会 fail）
        assertEquals(
            "crossingEvents.size MUST +3（期待门 S1 + 2 非期待门 S2/S3 全记）",
            initialCrossingSize + 3,
            result.crossingEvents.size
        )
        val newEvents = result.crossingEvents.drop(initialCrossingSize)

        // 顺序：期待门 S1 先，非期待门 S2/S3 按 sequenceIndex 顺序追加
        assertEquals("追加顺序: [S1 期待门, S2 非期待门, S3 非期待门]", listOf("S1", "S2", "S3"), newEvents.map { it.gateId })

        // S1: accepted=true + reason=Accepted（期待门推进）
        assertEquals(true, newEvents[0].accepted)
        assertEquals(CrossingReason.Accepted, newEvents[0].reason)

        // S2 / S3: accepted=false + reason=UnexpectedGateOrder（非期待门即使几何 accepted 也视为拒收）
        assertEquals(false, newEvents[1].accepted)
        assertEquals(CrossingReason.UnexpectedGateOrder, newEvents[1].reason)
        assertEquals(false, newEvents[2].accepted)
        assertEquals(CrossingReason.UnexpectedGateOrder, newEvents[2].reason)

        // 期待门 S1 推进 state
        val resultActiveLap = result.activeLap!!
        assertEquals(1, resultActiveLap.sectorEntries.size)
        assertEquals("S1", resultActiveLap.sectorEntries.last().gateId)
    }

    @Test
    fun handleSectorCrossing_expectedRejectedNonExpectedAccepted_recordsRejectedAndUnexpected() {
        // R4 Scenario 4：期待门 rejected + 非期待门 accepted
        // 构造：sectorGates[1] 是 S2；期待门是 S1（首次 nextExpectedGateIndex=1 → orderedSectorGates[0]=S1）
        // (prev, current) 过 S2 但不过 S1
        val opened = engine.processSample(
            session = newSession(),
            track = track,
            previousSample = crossingSamples(track.startFinishGate, 1773477876490L, 1773477876690L).first,
            currentSample = crossingSamples(track.startFinishGate, 1773477876490L, 1773477876690L).second
        )
        val initialSectorSize = opened.activeLap!!.sectorEntries.size
        val initialCrossingSize = opened.crossingEvents.size
        val initialNextExpected = opened.nextExpectedGateIndex

        val result = engine.processSample(
            session = opened,
            track = track,
            previousSample = crossingSamples(track.sectorGates[1], 1773478135290L, 1773478135490L).first,
            currentSample = crossingSamples(track.sectorGates[1], 1773478135290L, 1773478135490L).second
        )

        // state 不变（期待门 S1 未被过线）
        assertEquals("sectorEntries 不变", initialSectorSize, result.activeLap!!.sectorEntries.size)
        assertEquals("nextExpectedGateIndex 不变", initialNextExpected, result.nextExpectedGateIndex)
        // crossingEvents +2：期待门 S1 rejected event + 非期待门 S2 UnexpectedGateOrder event
        assertEquals("crossingEvents +2", initialCrossingSize + 2, result.crossingEvents.size)
        val newEvents = result.crossingEvents.drop(initialCrossingSize)
        assertEquals(
            "顺序: [期待门 S1 rejected, 非期待门 S2 UnexpectedGateOrder]",
            listOf(track.sectorGates[0].id, track.sectorGates[1].id),
            newEvents.map { it.gateId }
        )
        assertEquals(false, newEvents[0].accepted)
        assertEquals(false, newEvents[1].accepted)
        assertEquals(CrossingReason.UnexpectedGateOrder, newEvents[1].reason)
    }

    @Test
    fun handleSectorCrossing_multipleNonExpectedAccepted_sortedBySequenceIndex() {
        // R4 Scenario 5 / P2-2：即使 track.sectorGates 数据层面反序（[S3, S2, S1]），
        // engine 内 sortedBy { sequenceIndex } 后非期待门输出仍按 [S2, S3] 顺序。
        // 硬区分 engine 排序契约与数据源顺序解耦。
        val testTrack = threeSectorTrack(sectorOrder = listOf("S3", "S2", "S1"))  // 数据层面反 sequenceIndex 顺序
        assertEquals(
            "前置：track.sectorGates 数据顺序为反序",
            listOf("S3", "S2", "S1"),
            testTrack.sectorGates.map { it.id }
        )

        // 过 startFinish 开圈
        val openPair = crossStartFinishOf(testTrack, prevTs = 100L, currentTs = 200L)
        val opened = engine.processSample(
            session = newSession(trackId = testTrack.id),
            track = testTrack,
            previousSample = openPair.first,
            currentSample = openPair.second
        )
        val initialCrossingSize = opened.crossingEvents.size

        // 同一帧跨 S1/S2/S3 三门
        val multiGatePrev = sample(timestampMillis = 1_000L, latitude = 0.5, longitude = 0.0)
        val multiGateCurrent = sample(timestampMillis = 2_000L, latitude = 3.5, longitude = 0.0)
        val result = engine.processSample(
            session = opened,
            track = testTrack,
            previousSample = multiGatePrev,
            currentSample = multiGateCurrent
        )

        // 期待门是 S1（sequenceIndex=0 排序后 orderedSectorGates.first()），
        // 输出顺序应为 [S1, S2, S3]（engine 内 sortedBy { sequenceIndex } 解耦数据源反序）
        val newEvents = result.crossingEvents.drop(initialCrossingSize)
        assertEquals(
            "orderedSectorGates 排序契约：即使 track.sectorGates=[S3,S2,S1]，" +
                "engine sortedBy { sequenceIndex } 后输出仍按 [S1 期待门, S2 非期待门, S3 非期待门]",
            listOf("S1", "S2", "S3"),
            newEvents.map { it.gateId }
        )
        assertEquals(3, newEvents.size)
        assertEquals(true, newEvents[0].accepted)  // S1 期待门
        assertEquals(false, newEvents[1].accepted)  // S2 非期待门
        assertEquals(false, newEvents[2].accepted)  // S3 非期待门
        assertEquals(CrossingReason.UnexpectedGateOrder, newEvents[1].reason)
        assertEquals(CrossingReason.UnexpectedGateOrder, newEvents[2].reason)
    }

    // ==================== R5 MODIFIED 新增 Scenario ====================

    @Test
    fun handleStartFinishCrossing_boundaryCollision_filterStrictlyGreaterExcludesEdgeEvent() {
        // R5 MODIFIED Scenario 2：边界碰撞场景 filter 严格大于让边界 event 归前一圈
        // 构造 startedAtMillis=200 + event(ts=200) 边界事件
        // v2 `>` 排除边界；v1 `>=` 保留边界
        val opened = openLapAt(startedAtMillis = 200L)
        val withBoundaryEvent = opened.copy(
            crossingEvents = listOf(
                historicalCrossing(200L)  // 边界事件：ts == startedAtMillis
            )
        )

        val closeCrossing = crossingSamples(track.startFinishGate, 499L, 500L)
        val closed = engine.processSample(
            session = withBoundaryEvent,
            track = track,
            previousSample = closeCrossing.first,
            currentSample = closeCrossing.second
        )

        val lap = closed.completedLaps.first()
        val crossingTs = lap.crossingEvents.map { it.timestampMillis }
        assertFalse(
            "v2 严格 `>`：边界 ts=200 的 event 不应在 LapRecord.crossingEvents 中（硬区分 v1 `>=`）",
            crossingTs.contains(200L)
        )
        assertTrue(
            "闭圈 event.ts=500 保留",
            crossingTs.contains(500L)
        )
    }

    @Test
    fun handleStartFinishCrossing_nonMonotonicEvents_filterStrictlyGreaterRejectsHistorical() {
        // R5 MODIFIED Scenario 3：非单调序列含 ts < startedAt 夹后，严格 `>` 拒收历史事件
        // crossingEvents = [100, 250, 150, 400] + startedAtMillis = 200L
        // 严格 `>`: 250 > 200 ✓, 150 ≯ 200 ✗, 400 > 200 ✓, 100 ≯ 200 ✗ → [250, 400, 500]
        val opened = openLapAt(startedAtMillis = 200L)
        val withNonMonotonic = opened.copy(
            crossingEvents = listOf(
                historicalCrossing(100L),
                historicalCrossing(250L),
                historicalCrossing(150L),
                historicalCrossing(400L)
            )
        )

        val closeCrossing = crossingSamples(track.startFinishGate, 499L, 500L)
        val closed = engine.processSample(
            session = withNonMonotonic,
            track = track,
            previousSample = closeCrossing.first,
            currentSample = closeCrossing.second
        )

        val lap = closed.completedLaps.first()
        assertEquals(
            "非单调 + 严格 `>`: 拒收 ts=100/150（< 200），保留 ts=250/400 + 闭圈 500",
            listOf(250L, 400L, 500L),
            lap.crossingEvents.map { it.timestampMillis }
        )
    }

    // ==================== C4.9 rejected CrossingEvent timestamp 降级测试 ====================

    @Test
    fun handleSectorCrossing_expectedGateRejected_eventTimestampFallbackToCurrentSample() {
        // 对应 spec R2 Scenario "rejected CrossingEvent.timestampMillis 降级到触发帧 ts"
        // 期待门被 NoIntersection rejected（prev/current 不穿任何 gate）时，event.timestampMillis
        // 降级为 currentSample.timestampMillis（非插值）
        val opened = engine.processSample(
            session = newSession(),
            track = track,
            previousSample = crossingSamples(track.startFinishGate, 1773477876490L, 1773477876690L).first,
            currentSample = crossingSamples(track.startFinishGate, 1773477876490L, 1773477876690L).second
        )

        // 构造不穿任何 gate 的远离位置 + prev.ts=200L current.ts=240L
        val farPrev = sample(timestampMillis = 200L, latitude = 0.0, longitude = 0.0)
        val farCurrent = sample(timestampMillis = 240L, latitude = 0.0, longitude = 0.0)

        val result = engine.processSample(
            session = opened,
            track = track,
            previousSample = farPrev,
            currentSample = farCurrent
        )

        val lastEvent = result.crossingEvents.last()
        assertEquals(false, lastEvent.accepted)
        assertEquals(
            "rejected CrossingEvent.timestampMillis 降级到 currentSample.ts (240L)，非插值",
            240L,
            lastEvent.timestampMillis
        )
    }

    @Test
    fun trajectory_emptyBoundary_openToCloseWithNoIntermediateFrames() {
        // R3 Scenario 5 / P2-3：trajectory 为空边界硬断言 `trajectory.isEmpty()`
        //
        // 要让 trajectory 为空，time window `[startedAtMillis, finishedAtMillis)` 内不能有任何 sample.ts。
        // 通过 engine 主流程构造困难（processSample 无条件把 currentSample 加到 samples）。
        // 改为绕过主流程：手动构造 session + activeLap，让 subList(sampleStartIndex) 的帧都落在窗口外。
        val farFrame = sample(timestampMillis = 450L, latitude = 0.0, longitude = 0.0)
        val openSessionWithActiveLap = newSession().copy(
            status = LapSessionStatus.Recording,
            samples = listOf(farFrame),  // 单帧 ts=450 < startedAt=500
            startedAtMillis = 500L,
            currentLapIndex = 1,
            nextExpectedGateIndex = 1,
            activeLap = ActiveLap(
                lapIndex = 1,
                startedAtMillis = 500L,
                passedGateIds = listOf(track.startFinishGate.id),
                sampleStartIndex = 0
            )
        )

        // 构造极短闭圈（finishedAt - startedAt < 一帧间距），让 [500, ~520) 内无帧
        // 闭圈 prev=(519L, 520L, t=0.5) → finishedAt=519.5 → round 520
        // trajectory 窗口 [500, 520)：samples = [ts=450, ts=520]
        //   ts=450 < 500 → filter 兜底排除（虽 subList 起点为 0 含之，filter 排除）
        //   ts=520 >= 520 → 不属（右端严格 <）
        //   → trajectory 严格为空
        val closeCrossing = crossingSamples(track.startFinishGate, 519L, 520L)
        val closed = engine.processSample(
            session = openSessionWithActiveLap,
            track = track,
            previousSample = closeCrossing.first,
            currentSample = closeCrossing.second
        )

        val lap = closed.completedLaps.first()
        assertEquals(
            "finishedAtMillis 插值 (519 + 0.5*1 = 519.5 → round 520)",
            520L,
            lap.finishedAtMillis
        )
        assertTrue(
            "trajectory.isEmpty()：时间窗口 [500, 520) 内无帧（ts=450 被 filter 拒，ts=520 右端不含）",
            lap.trajectory.isEmpty()
        )
        assertEquals(
            "durationMillis = finishedAtMillis - startedAtMillis = 20L",
            20L,
            lap.durationMillis
        )
    }

    // ==================== P2-1 / P2-2 测试专用 3-sector track helper ====================

    /**
     * 构造测试专用 3-sector track，用于 R4 Scenario 3/5（多门同帧 + 数据源顺序解耦）。
     * - startFinish gate：水平线位于 lat=0，passDirection 正北 (1, 0)
     * - S1 gate：lat=1，S2 gate：lat=2，S3 gate：lat=3
     * - 所有 gate line 经度 [-0.5, 0.5]，passDirection 正北
     * - [sectorOrder] 指定 track.sectorGates 的**数据层面**顺序（["S1","S2","S3"] 或 ["S3","S2","S1"]）
     * - sequenceIndex 固定：S1=0, S2=1, S3=2（engine 按此排序解耦数据源顺序）
     */
    private fun threeSectorTrack(sectorOrder: List<String>): Track {
        val startFinish = TimingGate(
            id = "start-finish",
            name = "Start/Finish",
            type = TimingGateType.StartFinish,
            line = GeoLine(
                start = GeoPoint(latitude = 0.0, longitude = -0.5),
                end = GeoPoint(latitude = 0.0, longitude = 0.5)
            ),
            passDirection = GeoVector(x = 1.0, y = 0.0),
            sequenceIndex = 0,
            minDirectionalSpeedMps = null
        )
        val sectorGates = mapOf(
            "S1" to TimingGate(
                id = "S1",
                name = "Sector 1",
                type = TimingGateType.Sector,
                line = GeoLine(
                    start = GeoPoint(latitude = 1.0, longitude = -0.5),
                    end = GeoPoint(latitude = 1.0, longitude = 0.5)
                ),
                passDirection = GeoVector(x = 1.0, y = 0.0),
                sequenceIndex = 0,
                minDirectionalSpeedMps = null
            ),
            "S2" to TimingGate(
                id = "S2",
                name = "Sector 2",
                type = TimingGateType.Sector,
                line = GeoLine(
                    start = GeoPoint(latitude = 2.0, longitude = -0.5),
                    end = GeoPoint(latitude = 2.0, longitude = 0.5)
                ),
                passDirection = GeoVector(x = 1.0, y = 0.0),
                sequenceIndex = 1,
                minDirectionalSpeedMps = null
            ),
            "S3" to TimingGate(
                id = "S3",
                name = "Sector 3",
                type = TimingGateType.Sector,
                line = GeoLine(
                    start = GeoPoint(latitude = 3.0, longitude = -0.5),
                    end = GeoPoint(latitude = 3.0, longitude = 0.5)
                ),
                passDirection = GeoVector(x = 1.0, y = 0.0),
                sequenceIndex = 2,
                minDirectionalSpeedMps = null
            )
        )
        return Track(
            id = "test-3-sector",
            name = "Test 3 Sector",
            referencePath = TrackPath(points = emptyList()),
            startFinishGate = startFinish,
            sectorGates = sectorOrder.map { sectorGates.getValue(it) }
        )
    }

    /** 构造跨自定义 track 的 startFinish gate 的对称 (prev, current)。 */
    private fun crossStartFinishOf(customTrack: Track, prevTs: Long, currentTs: Long): Pair<GpsSample, GpsSample> {
        val gate = customTrack.startFinishGate
        val centerLat = (gate.line.start.latitude + gate.line.end.latitude) / 2.0
        val centerLon = (gate.line.start.longitude + gate.line.end.longitude) / 2.0
        val offset = 0.25
        return sample(
            timestampMillis = prevTs,
            latitude = centerLat - gate.passDirection.x * offset,
            longitude = centerLon - gate.passDirection.y * offset
        ) to sample(
            timestampMillis = currentTs,
            latitude = centerLat + gate.passDirection.x * offset,
            longitude = centerLon + gate.passDirection.y * offset
        )
    }

    private fun newSession(trackId: String): LapSession = LapSession(
        sessionId = "session-1",
        trackId = trackId,
        status = LapSessionStatus.Ready
    )
}
