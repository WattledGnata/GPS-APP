package com.blazepush.feature.test.usecase

import com.blazepush.feature.test.model.laptiming.CrossingReason
import com.blazepush.feature.test.model.laptiming.GpsSample
import com.blazepush.feature.test.model.laptiming.LapQualityFlag
import com.blazepush.feature.test.model.laptiming.LapSession
import com.blazepush.feature.test.model.laptiming.LapSessionStatus
import com.blazepush.feature.test.model.track.TimingGate
import com.blazepush.feature.test.repository.PresetTrackCatalog
import org.junit.Assert.assertEquals
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
