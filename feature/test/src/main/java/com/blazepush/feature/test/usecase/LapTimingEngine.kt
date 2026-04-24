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
                previousSample = previousSample,
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
        previousSample: GpsSample,
        currentSample: GpsSample,
        detection: GateCrossingDetection
    ): LapSession {
        // R2：所有圈时字段 MUST 用过线插值毫秒时刻（不用帧 ts）。
        // detection.crossingProgress 在 accepted 分支（processSample 已守卫）必非 null。
        val crossingMillis = interpolatedMillis(previousSample, currentSample, detection.crossingProgress!!)

        val crossingEvent = CrossingEvent(
            gateId = track.startFinishGate.id,
            gateType = track.startFinishGate.type,
            timestampMillis = crossingMillis,
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
                startedAtMillis = session.startedAtMillis ?: crossingMillis,
                samples = updatedSamples,
                currentLapIndex = 1,
                nextExpectedGateIndex = 1,
                crossingEvents = updatedEvents,
                activeLap = ActiveLap(
                    lapIndex = 1,
                    startedAtMillis = crossingMillis,
                    passedGateIds = listOf(track.startFinishGate.id),
                    sampleStartIndex = updatedSamples.lastIndex
                )
            )
        }

        val activeLap = session.activeLap
        val finishedMillis = crossingMillis
        // R3：trajectory 两段式切分 —— subList(sampleStartIndex) 作性能起点 + filter 时间窗口裁剪归属。
        //   sampleStartIndex 作为搜索跳过前缀的性能索引（语义降级为非归属判定依据）；
        //   归属判定严格按时间窗口 [startedAt, finishedAt)，闭圈帧归下一圈首帧。
        val trajectory = updatedSamples
            .subList(activeLap.sampleStartIndex, updatedSamples.size)
            .filter { sample ->
                sample.timestampMillis >= activeLap.startedAtMillis &&
                    sample.timestampMillis < finishedMillis
            }
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
            finishedAtMillis = finishedMillis,
            durationMillis = finishedMillis - activeLap.startedAtMillis,
            sectorTimes = activeLap.sectorEntries.toSectorTimes(activeLap.startedAtMillis),
            trajectory = trajectory,
            // A21 裁剪层（R5 修订）：严格 `>` 大于，边界事件归前一圈。
            //   v1 (engine-entry-hardening) 用 `>=` 含边界：`CrossingEvent.timestampMillis` 升级为插值毫秒后，
            //   闭圈 event.ts 与下圈 startedAtMillis 精确相等（同一过线瞬间），`>=` 会让 Lap N 闭圈 event 被
            //   Lap N+1 filter 同时捞走造成跨圈重复。改严格 `>` 让边界 event 归前圈，彻底消除碰撞。
            //   本 change spec 通过 `## MODIFIED Requirements` 段覆盖 engine-entry-hardening R3 Scenario 1。
            crossingEvents = updatedEvents.filter { it.timestampMillis > activeLap.startedAtMillis },
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
                startedAtMillis = crossingMillis,
                passedGateIds = listOf(track.startFinishGate.id),
                sampleStartIndex = updatedSamples.lastIndex
            )
        )
    }

    /**
     * 过线插值毫秒时刻（R2）：从 detector 的 `crossingProgress` 线性插值到两帧 ts 之间。
     *
     * 公式：`prev.ts + t × (current.ts - prev.ts)`，四舍五入到整数毫秒。
     *
     * 插值模型选型：**帧间线性（匀速）插值**。详见 proposal 决策 5 与 spec 头部的"插值模型约束"段：
     * 本 change 范围内 MUST NOT 引入匀加速 / 朝向几何等升级代码路径（防止扰乱 ±5ms 合成契约
     * 前置量级假设）。1Hz 弱定位设备升级路径留给未来战役。
     */
    private fun interpolatedMillis(
        previousSample: GpsSample,
        currentSample: GpsSample,
        crossingProgress: Double
    ): Long = Math.round(
        previousSample.timestampMillis + crossingProgress * (currentSample.timestampMillis - previousSample.timestampMillis)
    )

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

        // R4：遍历所有 sector 门 detect（替代 v1 的 firstOrNull accepted）
        val allDetections = orderedSectorGates.map { gate ->
            gate to detector.detect(previous = previousSample, current = currentSample, gate = gate)
        }
        val expectedPair = allDetections.first { (gate, _) -> gate.id == targetGate.id }
        val expectedGateDetection = expectedPair.second
        val unexpectedAccepted = allDetections.filter { (gate, d) -> gate.id != targetGate.id && d.accepted }

        FileLogger.d(
            TAG,
            "targetGate=${targetGate.id}, prev=(${previousSample.latitude},${previousSample.longitude},ts=${previousSample.timestampMillis}), current=(${currentSample.latitude},${currentSample.longitude},ts=${currentSample.timestampMillis}), accepted=${expectedGateDetection.accepted}, reason=${expectedGateDetection.reason}, directionScore=${expectedGateDetection.directionScore}, directionalSpeed=${expectedGateDetection.directionalSpeedMps}, unexpectedAcceptedCount=${unexpectedAccepted.size}"
        )

        // 期待门 CrossingEvent：accepted 用插值时刻，rejected 降级到 currentSample.ts（R2 B2 契约）
        val expectedCrossingMillis = if (expectedGateDetection.accepted) {
            interpolatedMillis(previousSample, currentSample, expectedGateDetection.crossingProgress!!)
        } else {
            currentSample.timestampMillis
        }
        val expectedEvent = CrossingEvent(
            gateId = targetGate.id,
            gateType = targetGate.type,
            timestampMillis = expectedCrossingMillis,
            sampleIndex = updatedSamples.lastIndex,
            accepted = expectedGateDetection.accepted,
            reason = expectedGateDetection.reason,
            directionalSpeedMps = expectedGateDetection.directionalSpeedMps,
            directionScore = expectedGateDetection.directionScore
        )

        // R4：非期待门 accepted 的 CrossingEvent 全部记录为 UnexpectedGateOrder（accepted=false）
        // 顺序：按 orderedSectorGates.sequenceIndex 从小到大（orderedSectorGates 已排序，filter 保持顺序）
        val unexpectedEvents = unexpectedAccepted.map { (gate, detection) ->
            CrossingEvent(
                gateId = gate.id,
                gateType = gate.type,
                timestampMillis = interpolatedMillis(previousSample, currentSample, detection.crossingProgress!!),
                sampleIndex = updatedSamples.lastIndex,
                accepted = false,
                reason = CrossingReason.UnexpectedGateOrder,
                directionalSpeedMps = detection.directionalSpeedMps,
                directionScore = detection.directionScore
            )
        }

        // R4 追加顺序：期待门先 + 所有非期待门按 orderedSectorGates 顺序
        val allNewEvents = listOf(expectedEvent) + unexpectedEvents
        val updatedEvents = session.crossingEvents + allNewEvents

        // 期待门 rejected：state 保持不变，仅记 event（R4 B5 两档分支契约）
        if (!expectedGateDetection.accepted) {
            return session.copy(samples = updatedSamples, crossingEvents = updatedEvents)
        }

        // 期待门 accepted：推进 nextExpectedGateIndex + SectorEntry + passedGateIds
        return session.copy(
            samples = updatedSamples,
            nextExpectedGateIndex = session.nextExpectedGateIndex + 1,
            crossingEvents = updatedEvents,
            activeLap = activeLap.copy(
                passedGateIds = activeLap.passedGateIds + targetGate.id,
                sectorEntries = activeLap.sectorEntries + SectorEntry(
                    gateId = targetGate.id,
                    crossedAtMillis = expectedCrossingMillis
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
