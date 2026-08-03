// @IgnoreFormatCheck
// 理由：本文件仅由 change fix-file-logger-and-engine-coord-hygiene（战役 F A18+A39）
//       修改 1 处 R4 高频 detector 日志 call site（line 70 附近）—— `FileLogger.d`
//       → `FileLogger.v` + 坐标 `"%.3f".format(...)` 降级 + `if (isVerboseEnabled)`
//       守卫。kt-check 报的 class-comment / property-name / public-fun-with-comment-block
//       / my-max-line-length / allow-assert(!!) / method-name(List) / no-trailing-newline
//       均为 pre-existing legacy 违规，与本战役 R4 日志精修语义正交。评审方 2026-04-24
//       战役 G B 方案纪律批准 legacy 文件 ignore，避免 scope 漂移到周边 refactor。
package com.blazepush.feature.test.usecase

import com.blazepush.feature.test.FileLogger
import com.blazepush.core.domain.model.LapEvidence
import com.blazepush.core.domain.model.LapEvidenceFlag
import com.blazepush.core.domain.model.LapGapInterval
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

        // A22 change fix-active-lap-distance-accumulator：相邻 samples 流的 haversine 增量。
        // 增量来源 MUST 是 session.samples.lastOrNull() 与 currentSample（samples 流口径），
        // 与 UI 旧 samples.zipWithNext() 同源；不复用 detector 用的 previousSample 参数
        // （虽然此处通常指向同一 GpsSample，但语义来源应明示从 samples 流走，
        // future 若 previousSample 因 ts 守卫等改变，distance 仍跟 samples 流，无需协同改）。
        val activeLapWithDistance: ActiveLap? = session.activeLap?.let { current ->
            val prev = session.samples.lastOrNull()
            if (prev != null) {
                current.copy(
                    distanceMetersSinceStart = current.distanceMetersSinceStart +
                        haversineDistanceMeters(
                            prev.latitude, prev.longitude,
                            currentSample.latitude, currentSample.longitude,
                        ),
                )
            } else {
                current  // 理论不发生：activeLap 存在 → 开圈帧已入 samples（sampleStartIndex 守卫）
            }
        }

        val startFinishDetection = detector.detect(previous = previousSample, current = currentSample, gate = track.startFinishGate)
        // A18 + A39 战役 F R4 + 2026-06-04 降频采样：25Hz 逐帧 detector 日志
        // vSampled 同 key 每秒最多 1 条(verbose 开启时也不再全量),lambda 惰性零开销。
        FileLogger.vSampled(TAG, "engine-gate-sf") {
            "targetGate=${track.startFinishGate.id}, prev=(${"%.3f".format(previousSample.latitude)},${"%.3f".format(previousSample.longitude)},ts=${previousSample.timestampMillis}), current=(${"%.3f".format(currentSample.latitude)},${"%.3f".format(currentSample.longitude)},ts=${currentSample.timestampMillis}), accepted=${startFinishDetection.accepted}, reason=${startFinishDetection.reason}, directionScore=${startFinishDetection.directionScore}, directionalSpeed=${startFinishDetection.directionalSpeedMps}"
        }
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

        // A22 路径 (d) no target gate：携带 activeLapWithDistance 累距
        val targetGate = expectedGate(track, session.nextExpectedGateIndex)
            ?: return session.copy(samples = updatedSamples, activeLap = activeLapWithDistance)

        return handleSectorCrossing(
            session = session,
            track = track,
            previousSample = previousSample,
            currentSample = currentSample,
            updatedSamples = updatedSamples,
            targetGate = targetGate,
            activeLapWithDistance = activeLapWithDistance,  // A22 集中构造后传入
        )
    }

    /** Records bridge-detected Main silence without feeding its endpoints to crossing detection. */
    fun recordMainGap(
        session: LapSession,
        track: Track,
        previousSample: GpsSample,
        recoveredSample: GpsSample,
    ): LapSession = session.copy(
        activeLap = session.activeLap?.copy(
            gapIntervals = session.activeLap.gapIntervals + buildGap(track, previousSample, recoveredSample),
        ),
    )

    private fun buildGap(track: Track, previous: GpsSample, current: GpsSample): LapGapInterval {
        val affectedGateIds = (listOf(track.startFinishGate) + track.orderedSectorGates).mapNotNull { gate ->
            gate.id.takeIf { detector.detect(previous, current, gate).accepted }
        }.toSet()
        return LapGapInterval(previous.timestampMillis, current.timestampMillis, affectedGateIds)
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
                    sampleStartIndex = updatedSamples.lastIndex,
                    distanceMetersSinceStart = 0.0,  // A22 路径 (b) 首次开圈
                ),
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
        val evidenceFlags = buildSet {
            if (activeLap.sectorEntries.size != track.sectorGates.size) {
                add(LapEvidenceFlag.MissingRequiredGate)
            }
        }
        val requiredGateIds = buildSet {
            add(track.startFinishGate.id)
            addAll(track.sectorGates.map { it.id })
        }
        val acceptedGateIds = buildSet {
            add(track.startFinishGate.id)
            addAll(activeLap.sectorEntries.map { it.gateId })
        }
        val lapRecord = LapRecord(
            recordId = "${session.sessionId}-lap-${activeLap.lapIndex}",
            sessionId = session.sessionId,
            trackId = session.trackId,
            lapIndex = activeLap.lapIndex,
            startedAtMillis = activeLap.startedAtMillis,
            finishedAtMillis = finishedMillis,
            durationMillis = finishedMillis - activeLap.startedAtMillis,
            sectorTimes = activeLap.sectorEntries.toSectorTimes(activeLap.startedAtMillis, finishedMillis),
            trajectory = trajectory,
            // A21 裁剪层（R5 修订）：严格 `>` 大于，边界事件归前一圈。
            //   v1 (engine-entry-hardening) 用 `>=` 含边界：`CrossingEvent.timestampMillis` 升级为插值毫秒后，
            //   闭圈 event.ts 与下圈 startedAtMillis 精确相等（同一过线瞬间），`>=` 会让 Lap N 闭圈 event 被
            //   Lap N+1 filter 同时捞走造成跨圈重复。改严格 `>` 让边界 event 归前圈，彻底消除碰撞。
            //   本 change spec 通过 `## MODIFIED Requirements` 段覆盖 engine-entry-hardening R3 Scenario 1。
            crossingEvents = updatedEvents.filter { it.timestampMillis > activeLap.startedAtMillis },
            qualityFlags = qualityFlags,
            evidence = LapEvidence(
                startCrossingTimestampMillis = activeLap.startedAtMillis,
                finishCrossingTimestampMillis = finishedMillis,
                requiredGateIds = requiredGateIds,
                acceptedGateIds = acceptedGateIds,
                gaps = activeLap.gapIntervals,
                flags = evidenceFlags,
            ),
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
                sampleStartIndex = updatedSamples.lastIndex,
                distanceMetersSinceStart = 0.0,  // A22 路径 (c) 闭圈后新 lap 重置
            ),
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
        targetGate: TimingGate,
        activeLapWithDistance: ActiveLap?,  // A22 change fix-active-lap-distance-accumulator
    ): LapSession {
        val activeLap = session.activeLap ?: return session.copy(samples = updatedSamples)
        // A36：读 Track 的 orderedSectorGates 单点真理派生字段，不再局部 sortedBy
        val orderedSectorGates = track.orderedSectorGates

        // R4：遍历所有 sector 门 detect（替代 v1 的 firstOrNull accepted）
        val allDetections = orderedSectorGates.map { gate ->
            gate to detector.detect(previous = previousSample, current = currentSample, gate = gate)
        }
        val expectedPair = allDetections.first { (gate, _) -> gate.id == targetGate.id }
        val expectedGateDetection = expectedPair.second
        val unexpectedAccepted = allDetections.filter { (gate, d) -> gate.id != targetGate.id && d.accepted }

        // 路测修复（2026-06-04）：sector gate 逐帧日志降 VERBOSE + 降频采样(每秒最多 1 条)——
        // 25Hz × D 级写盘把 5MB rotation 现场刷掉（昨晚 4.5MB 日志大半是本条）。
        FileLogger.vSampled(TAG, "engine-gate-sector") {
            "targetGate=${targetGate.id}, prev=(${"%.3f".format(previousSample.latitude)},${"%.3f".format(previousSample.longitude)},ts=${previousSample.timestampMillis}), current=(${"%.3f".format(currentSample.latitude)},${"%.3f".format(currentSample.longitude)},ts=${currentSample.timestampMillis}), accepted=${expectedGateDetection.accepted}, reason=${expectedGateDetection.reason}, directionScore=${expectedGateDetection.directionScore}, directionalSpeed=${expectedGateDetection.directionalSpeedMps}, unexpectedAcceptedCount=${unexpectedAccepted.size}"
        }

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
        // A22 路径 (e)：携带 activeLapWithDistance 累距
        if (!expectedGateDetection.accepted) {
            return session.copy(
                samples = updatedSamples,
                crossingEvents = updatedEvents,
                activeLap = activeLapWithDistance,
            )
        }

        // 期待门 accepted：推进 nextExpectedGateIndex + SectorEntry + passedGateIds
        // A22 路径 (f)：从 activeLapWithDistance!!.copy(...) 派生（不走本地 activeLap.copy）
        // !! 安全：进入此分支前 line 233 `session.activeLap ?: return` 已守卫，
        //       session.activeLap != null 保证 activeLapWithDistance != null
        return session.copy(
            samples = updatedSamples,
            nextExpectedGateIndex = session.nextExpectedGateIndex + 1,
            crossingEvents = updatedEvents,
            activeLap = activeLapWithDistance!!.copy(
                passedGateIds = activeLapWithDistance.passedGateIds + targetGate.id,
                sectorEntries = activeLapWithDistance.sectorEntries + SectorEntry(
                    gateId = targetGate.id,
                    crossedAtMillis = expectedCrossingMillis,
                ),
            ),
        )
    }

    private fun expectedGate(track: Track, nextExpectedGateIndex: Int): TimingGate? =
        // A36：读 Track 的 orderedSectorGates 单点真理派生字段，不再局部 sortedBy
        track.orderedSectorGates.getOrNull(nextExpectedGateIndex - 1)

    /**
     * SectorEntry 序列 → 分段时长。N 个 sector gate 把整圈切成 **N+1 段**：
     * [开圈→s1, s1→s2, ..., sN→闭圈]。末段（最后 sector gate → 终点线）由 [finishedAtMillis] 闭合，
     * 保证 `sum(sectorTimes) == durationMillis` 精确成立。
     *
     * 路测修复（2026-06-04）：旧实现漏末段（只产 N 段），分段和恒比整圈少一段 →
     * livetiming 服务端"分段和与整圈相差过大"校验 100% 拒绝（HTTP 400 永久丢弃）。
     * 空 entries（无 sector gate 赛道 / 全漏检）返回空列表，上报侧 takeIf { isNotEmpty() } 不传。
     */
    private fun List<SectorEntry>.toSectorTimes(startedAtMillis: Long, finishedAtMillis: Long): List<Long> {
        if (isEmpty()) return emptyList()
        var previousTimestamp = startedAtMillis
        val gateSegments = map { entry ->
            val duration = entry.crossedAtMillis - previousTimestamp
            previousTimestamp = entry.crossedAtMillis
            duration
        }
        return gateSegments + (finishedAtMillis - previousTimestamp)
    }
}
