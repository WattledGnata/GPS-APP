package com.blazepush.feature.test.usecase

import com.blazepush.feature.test.FileLogger
import com.blazepush.feature.test.model.laptiming.ActiveLap
import com.blazepush.feature.test.model.laptiming.CrossingEvent
import com.blazepush.feature.test.model.laptiming.CrossingReason
import com.blazepush.feature.test.model.laptiming.GpsSample
import com.blazepush.feature.test.model.laptiming.LapQualityFlag
import com.blazepush.feature.test.model.laptiming.LapRecord
import com.blazepush.feature.test.model.laptiming.LapSession
import com.blazepush.feature.test.model.laptiming.LapSessionStatus
import com.blazepush.feature.test.model.laptiming.SectorEntry
import com.blazepush.feature.test.model.track.TimingGate
import com.blazepush.feature.test.model.track.Track

/**
 * @param detector gate crossing detector
 * @param expectedIntervalMillis 预期 GPS 采样间隔（毫秒）。默认 40 = 25Hz（ESP32 标配）。
 *        低频模式（例如 5Hz replay = 200ms）需要显式传入 200，否则
 *        `ProtocolDesyncGap` 阈值会在正常采样间隔附近抖动造成假阳性。
 *        阈值 = `expectedIntervalMillis × DESYNC_GAP_FACTOR`（5 倍采样间隔）。
 *        对抗 review C.2 / backlog A7。
 */
class LapTimingEngine(
    private val detector: GateCrossingDetector = GateCrossingDetector(),
    private val expectedIntervalMillis: Long = DEFAULT_EXPECTED_INTERVAL_MILLIS
) {

    /**
     * `ProtocolDesyncGap` 标记阈值：相邻 trajectory 样本 ts 差超过此值视为一次失联。
     * 取 5 倍采样间隔 = 允许丢最多 4 帧的抖动容忍；第 5 帧仍缺失才 flag 本圈。
     */
    private val desyncGapThresholdMillis: Long = expectedIntervalMillis * DESYNC_GAP_FACTOR

    companion object {
        private const val TAG = "LapTimingEngine"
        /** 25Hz 采样 = 40ms 间隔。ESP32 / RaceChrono 标准。 */
        const val DEFAULT_EXPECTED_INTERVAL_MILLIS: Long = 40L
        /** 连续丢 N 帧视为失联。5 = 允许 4 帧抖动 + 第 5 帧起 flag ProtocolDesyncGap。 */
        const val DESYNC_GAP_FACTOR: Long = 5L
    }

    fun processSample(
        session: LapSession,
        track: Track,
        previousSample: GpsSample,
        currentSample: GpsSample
    ): LapSession {
        // A19 入口守卫：白名单语义，仅放行 Ready / Recording 两个应接受样本的状态。
        // 未来新增 LapSessionStatus 枚举值（Paused / Interrupted / ...）默认被拦，
        // 除非显式决定接受 —— 防御"开放默认不安全"反模式。
        // 详见 openspec/changes/fix-lap-timing-engine-entry-hardening Requirement 1。
        if (session.status !in setOf(LapSessionStatus.Ready, LapSessionStatus.Recording)) {
            return session
        }

        // A21 深度防御：bridge 层若被绕过或重构，engine 兜底拦 ts 回跳。
        // 对比基准用 previousSample（方法参数永远非空），与 A38 语义对称。
        // 详见 openspec/changes/fix-lap-timing-engine-entry-hardening Requirement 2。
        if (currentSample.timestampMillis < previousSample.timestampMillis) {
            FileLogger.d(
                TAG,
                "processSample: ts regression, drop prevTs=${previousSample.timestampMillis} curTs=${currentSample.timestampMillis}"
            )
            return session
        }

        val updatedSamples = session.samples + currentSample
        val startFinishDetection = detector.detect(previous = previousSample, current = currentSample, gate = track.startFinishGate)
        FileLogger.d(
            TAG,
            "targetGate=${track.startFinishGate.id}, prev=(${previousSample.latitude},${previousSample.longitude},ts=${previousSample.timestampMillis}), current=(${currentSample.latitude},${currentSample.longitude},ts=${currentSample.timestampMillis}), accepted=${startFinishDetection.accepted}, reason=${startFinishDetection.reason}, directionScore=${startFinishDetection.directionScore}, directionalSpeed=${startFinishDetection.directionalSpeedMps}"
        )
        if (startFinishDetection.accepted) {
            return handleStartFinishCrossing(
                session = session,
                track = track,
                updatedSamples = updatedSamples,
                currentSample = currentSample,
                detection = startFinishDetection
            )
        }

        val targetGate = expectedGate(track, session.nextExpectedGateIndex) ?: return session.copy(samples = updatedSamples)

        return handleSectorCrossing(
            session = session,
            track = track,
            previousSample = previousSample,
            currentSample = currentSample,
            updatedSamples = updatedSamples,
            targetGate = targetGate
        )
    }

    private fun handleStartFinishCrossing(
        session: LapSession,
        track: Track,
        updatedSamples: List<GpsSample>,
        currentSample: GpsSample,
        detection: GateCrossingDetection
    ): LapSession {
        val crossingEvent = CrossingEvent(
            gateId = track.startFinishGate.id,
            gateType = track.startFinishGate.type,
            timestampMillis = currentSample.timestampMillis,
            sampleIndex = updatedSamples.lastIndex,
            accepted = detection.accepted,
            reason = detection.reason,
            directionalSpeedMps = detection.directionalSpeedMps,
            directionScore = detection.directionScore
        )
        val updatedEvents = session.crossingEvents + crossingEvent

        if (session.activeLap == null) {
            return session.copy(
                status = LapSessionStatus.Recording,
                startedAtMillis = session.startedAtMillis ?: currentSample.timestampMillis,
                samples = updatedSamples,
                currentLapIndex = 1,
                nextExpectedGateIndex = 1,
                crossingEvents = updatedEvents,
                activeLap = ActiveLap(
                    lapIndex = 1,
                    startedAtMillis = currentSample.timestampMillis,
                    passedGateIds = listOf(track.startFinishGate.id),
                    sampleStartIndex = updatedSamples.lastIndex
                )
            )
        }

        val activeLap = session.activeLap
        val trajectory = updatedSamples.drop(activeLap.sampleStartIndex)
        val hasDesyncGap = trajectory.zipWithNext().any { (a, b) ->
            (b.timestampMillis - a.timestampMillis) > desyncGapThresholdMillis
        }
        val qualityFlags = buildList {
            if (activeLap.sectorEntries.size != track.sectorGates.size) {
                add(LapQualityFlag.IncompleteSectors)
            }
            if (hasDesyncGap) {
                add(LapQualityFlag.ProtocolDesyncGap)
            }
        }
        val lapRecord = LapRecord(
            recordId = "${session.sessionId}-lap-${activeLap.lapIndex}",
            sessionId = session.sessionId,
            trackId = session.trackId,
            lapIndex = activeLap.lapIndex,
            startedAtMillis = activeLap.startedAtMillis,
            finishedAtMillis = currentSample.timestampMillis,
            durationMillis = currentSample.timestampMillis - activeLap.startedAtMillis,
            sectorTimes = activeLap.sectorEntries.toSectorTimes(activeLap.startedAtMillis),
            trajectory = trajectory,
            // A21 裁剪层：逐元素 filter 严格语义，不依赖时间戳单调假设。
            // `dropWhile` 只在前缀首个不满足处停止，非单调序列会漏拦夹在后面的历史事件。
            crossingEvents = updatedEvents.filter { it.timestampMillis >= activeLap.startedAtMillis },
            qualityFlags = qualityFlags
        )
        val nextLapIndex = activeLap.lapIndex + 1
        return session.copy(
            status = LapSessionStatus.Recording,
            samples = updatedSamples,
            currentLapIndex = nextLapIndex,
            nextExpectedGateIndex = 1,
            crossingEvents = updatedEvents,
            completedLaps = session.completedLaps + lapRecord,
            activeLap = ActiveLap(
                lapIndex = nextLapIndex,
                startedAtMillis = currentSample.timestampMillis,
                passedGateIds = listOf(track.startFinishGate.id),
                sampleStartIndex = updatedSamples.lastIndex
            )
        )
    }

    private fun handleSectorCrossing(
        session: LapSession,
        track: Track,
        previousSample: GpsSample,
        currentSample: GpsSample,
        updatedSamples: List<GpsSample>,
        targetGate: TimingGate
    ): LapSession {
        val activeLap = session.activeLap ?: return session.copy(samples = updatedSamples)
        val orderedSectorGates = track.sectorGates.sortedBy { it.sequenceIndex }

        val unexpectedGate = orderedSectorGates
            .asSequence()
            .filter { it.id != targetGate.id }
            .map { gate -> gate to detector.detect(previous = previousSample, current = currentSample, gate = gate) }
            .firstOrNull { (_, detection) -> detection.accepted }

        if (unexpectedGate != null) {
            val (gate, detection) = unexpectedGate
            val crossingEvent = CrossingEvent(
                gateId = gate.id,
                gateType = gate.type,
                timestampMillis = currentSample.timestampMillis,
                sampleIndex = updatedSamples.lastIndex,
                accepted = false,
                reason = CrossingReason.UnexpectedGateOrder,
                directionalSpeedMps = detection.directionalSpeedMps,
                directionScore = detection.directionScore
            )
            return session.copy(
                samples = updatedSamples,
                crossingEvents = session.crossingEvents + crossingEvent
            )
        }

        val detection = detector.detect(previous = previousSample, current = currentSample, gate = targetGate)
        FileLogger.d(
            TAG,
            "targetGate=${targetGate.id}, prev=(${previousSample.latitude},${previousSample.longitude},ts=${previousSample.timestampMillis}), current=(${currentSample.latitude},${currentSample.longitude},ts=${currentSample.timestampMillis}), accepted=${detection.accepted}, reason=${detection.reason}, directionScore=${detection.directionScore}, directionalSpeed=${detection.directionalSpeedMps}"
        )

        val crossingEvent = CrossingEvent(
            gateId = targetGate.id,
            gateType = targetGate.type,
            timestampMillis = currentSample.timestampMillis,
            sampleIndex = updatedSamples.lastIndex,
            accepted = detection.accepted,
            reason = detection.reason,
            directionalSpeedMps = detection.directionalSpeedMps,
            directionScore = detection.directionScore
        )

        val updatedEvents = session.crossingEvents + crossingEvent

        if (!detection.accepted) {
            return session.copy(samples = updatedSamples, crossingEvents = updatedEvents)
        }

        return session.copy(
            samples = updatedSamples,
            nextExpectedGateIndex = session.nextExpectedGateIndex + 1,
            crossingEvents = updatedEvents,
            activeLap = activeLap.copy(
                passedGateIds = activeLap.passedGateIds + targetGate.id,
                sectorEntries = activeLap.sectorEntries + SectorEntry(
                    gateId = targetGate.id,
                    crossedAtMillis = currentSample.timestampMillis
                )
            )
        )
    }

    private fun expectedGate(track: Track, nextExpectedGateIndex: Int): TimingGate? =
        track.sectorGates.sortedBy { it.sequenceIndex }.getOrNull(nextExpectedGateIndex - 1)

    private fun List<SectorEntry>.toSectorTimes(startedAtMillis: Long): List<Long> {
        var previousTimestamp = startedAtMillis
        return map { entry ->
            val duration = entry.crossedAtMillis - previousTimestamp
            previousTimestamp = entry.crossedAtMillis
            duration
        }
    }
}
