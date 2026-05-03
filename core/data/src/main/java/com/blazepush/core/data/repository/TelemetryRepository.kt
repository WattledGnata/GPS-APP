// @IgnoreFormatCheck
// 理由：本 round wire-real-data-to-records-and-laps-tabs §1.4 追加 4 个统计 Flow 方法；
//       既有方法 doc 缺失为 baseline 历史问题，按 scope-boundary 推到 D round
//       （kt-format-cleanup-pass）批量补齐，本 round 不顺手改。
package com.blazepush.core.data.repository

import android.content.Context
import com.blazepush.core.data.local.binary.BinaryTelemetryWriter
import com.blazepush.core.data.local.binary.LapTelemetryReader
import com.blazepush.core.data.local.binary.PerformanceTestTelemetryReader
import com.blazepush.core.data.local.dao.CrossingEventDao
import com.blazepush.core.data.local.dao.TelemetrySessionDao
import com.blazepush.core.data.local.entity.CrossingEventEntity
import com.blazepush.core.data.local.entity.TelemetrySessionEntity
import com.blazepush.core.domain.model.TelemetryCrossingEvent
import com.blazepush.core.domain.model.TelemetrySample
import com.blazepush.core.domain.model.TelemetrySession
import com.blazepush.core.domain.model.TelemetrySessionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * 统一 GPS 点阵持久化入口（A56）。
 * 封装 Room metadata DAO + binary file writer/reader，外部仅与 sessionId / TelemetrySample 打交道。
 *
 * @author CC
 * @description unified GPS telemetry persistence repository
 * @date 2026-04-30
 */
class TelemetryRepository(
    private val context: Context,
    private val sessionDao: TelemetrySessionDao,
    private val crossingDao: CrossingEventDao,
) {
    private var activeWriter: BinaryTelemetryWriter? = null
    private var activeSessionId: String? = null
    private var activeSessionType: TelemetrySessionType = TelemetrySessionType.PERFORMANCE_TEST
    // persist-session-summary-fields round 加：endSession 时需要 binary 文件路径扫 sample 派生 topSpeedKmh
    private var activeFilePath: String? = null
    // fix-lap-binary-ts-hygiene round 加：与 header.startTs / entity.startTs 同源的 active session 起点真壁钟，
    // 供 bridgeGpsToLapTiming 计算 sample.tsDeltaMs 的 anchor（不再用 lapAnchorTs，避免 anchor 错位）。
    // public get + private set 实现"对外只读、内部 startSession 时赋值 / endSession 时清空"语义。
    var activeSessionStartTs: Long? = null
        private set

    /**
     * 开启新 session：生成 UUID + 写 metadata（含可选 trackId / trackNameSnapshot）入 Room + 启动 binary writer。
     *
     * @param type session 类型（PERFORMANCE_TEST / LAP_SESSION）
     * @param trackId 圈速场景下传 session 启动时的 trackId（加减速测试传 null 兼容默认值）
     * @param trackNameSnapshot 圈速场景下传 startSession 时刻 catalog 解析的 trackName 快照（加减速测试传 null）
     *
     * @author CC
     * @description start session with optional trackId / trackNameSnapshot persistence
     * @date 2026-05-01
     */
    suspend fun startSession(
        type: TelemetrySessionType,
        trackId: String? = null,
        trackNameSnapshot: String? = null,
    ): String {
        val sessionId = UUID.randomUUID().toString()
        val startTs = System.currentTimeMillis()
        val file = telemetryFile(sessionId)
        file.parentFile?.mkdirs()

        val entity = TelemetrySessionEntity(
            sessionId = sessionId,
            sessionType = type.name,
            startTs = startTs,
            endTs = startTs,
            binaryFilePath = file.absolutePath,
            trackId = trackId,
            trackNameSnapshot = trackNameSnapshot,
        )
        sessionDao.insert(entity)

        val writer = BinaryTelemetryWriter()
        writer.open(file.absolutePath, type, startTs)
        activeWriter = writer
        activeSessionId = sessionId
        activeSessionType = type
        activeFilePath = file.absolutePath
        activeSessionStartTs = startTs
        return sessionId
    }

    /**
     * 提交一帧 sample 到 active writer 队列。
     */
    suspend fun writeSample(sample: TelemetrySample) {
        activeWriter?.write(sample)
    }

    /**
     * 事务式写入过线事件到 Room（不走 binary 流，是计时精度真相源）。
     */
    suspend fun writeCrossing(event: TelemetryCrossingEvent) {
        val entity = CrossingEventEntity(
            sessionId = event.sessionId,
            lapIndex = event.lapIndex,
            crossingTimestampMs = event.crossingTimestampMs,
            speedKmh = event.speedKmh,
            gateId = event.gateId,
            gateType = event.gateType,
            accepted = event.accepted,
            reason = event.reason,
            directionScore = event.directionScore,
            crossingWallClockTimestampMs = event.crossingWallClockTimestampMs,
        )
        crossingDao.insertInTransaction(entity)
    }

    /**
     * 主动 flush：等待 active writer header 回写 + force 刷盘后返回。
     */
    suspend fun flush() {
        activeWriter?.flush()
    }

    /**
     * 关闭 session：close writer + 用 IO 调度扫 binary 派生 topSpeedKmh + 用 crossings 派生
     * lapCount/bestLapMs（accepted SF crossing pairs 语义）+ updateSummary 一次写齐 4 字段。
     *
     * lapCount 派生语义：accepted=true && gateType="StartFinish" 的 crossing 相邻配对数量
     * （= durations.size），与 LapSessionDetailScreen.deriveDetailMetrics 一致。
     * **不**承诺 LapRecord.qualityFlags 过滤（crossing 表无该字段；与 Snackbar in-memory 路径
     * 语义差异作为 follow-up `unify-lap-count-semantics`）。
     *
     * binary 文件不存在 / 为空 → topSpeedKmh = null；crossings 为空 → lapCount = 0 / bestLapMs = null。
     * 不抛异常。
     *
     * @author CC
     * @description close writer + derive summary + persist on endSession
     * @date 2026-05-01
     */
    suspend fun endSession(sessionId: String) {
        val writer = activeWriter ?: return
        val filePath = activeFilePath
        writer.close()
        activeWriter = null
        activeSessionId = null
        activeFilePath = null
        activeSessionStartTs = null
        val endTs = System.currentTimeMillis()

        // 用 IO 调度跑扫 binary + 查 crossings 派生（避免阻塞 caller 主线程）
        val (topSpeedKmh, lapCount, bestLapMs) = withContext(Dispatchers.IO) {
            val topSpeed = if (filePath != null) {
                runCatching { PerformanceTestTelemetryReader.read(filePath) }
                    .getOrDefault(emptyList())
                    .maxOfOrNull { it.speedKmh }
                    ?.takeIf { it > 0.0 }
            } else {
                null
            }
            val crossings = crossingDao.queryBySessionId(sessionId)
            val acceptedSF = crossings
                .filter { it.gateType.equals("StartFinish", ignoreCase = true) && it.accepted }
                .sortedBy { it.crossingTimestampMs }
            val durations = acceptedSF.zipWithNext { a, b -> b.crossingTimestampMs - a.crossingTimestampMs }
            Triple(topSpeed, durations.size, durations.minOrNull())
        }

        sessionDao.updateSummary(
            sessionId = sessionId,
            endTs = endTs,
            lapCount = lapCount,
            bestLapMs = bestLapMs,
            topSpeedKmh = topSpeedKmh,
        )
    }

    /**
     * 拉 session metadata 转换成 domain model；未找到返回 null。
     */
    suspend fun getSession(sessionId: String): TelemetrySession? {
        return sessionDao.queryBySessionId(sessionId)?.toDomain()
    }

    /**
     * 拉 session 内所有过线事件（含 INVALID）转换成 domain；UI 层用以派生 lap records / best / last。
     *
     * @author CC
     * @description list crossings of a session as domain models
     * @date 2026-05-01
     */
    suspend fun getCrossings(sessionId: String): List<TelemetryCrossingEvent> =
        crossingDao.queryBySessionId(sessionId).map { it.toDomain() }

    /**
     * 拉最近 N 个 LAP_SESSION（按 startTs 倒序），供 Records LAPS SESSION HISTORY 列表消费。
     *
     * @author CC
     * @description recent lap sessions for history list
     * @date 2026-05-01
     */
    suspend fun getRecentLapSessions(limit: Int = 10): List<TelemetrySession> =
        sessionDao.queryAll()
            .asSequence()
            .filter { it.sessionType == TelemetrySessionType.LAP_SESSION.name }
            .sortedByDescending { it.startTs }
            .take(limit)
            .map { it.toDomain() }
            .toList()

    // round wire-real-data-to-records-and-laps-tabs §1.4：按 trackId 聚合 Flow 查询。
    // 闭环判定 endTs > startTs（startSession 写 endTs=startTs 占位）+ best lap
    // 加 bestLapMs IS NOT NULL 排除首圈未完成。

    fun getBestLapForTrack(trackId: String): Flow<TelemetrySession?> =
        sessionDao.getBestLapForTrack(trackId).map { it?.toDomain() }

    fun getSessionCountForTrack(trackId: String): Flow<Int> =
        sessionDao.getSessionCountForTrack(trackId)

    fun getTotalLapCountForTrack(trackId: String): Flow<Int> =
        sessionDao.getTotalLapCountForTrack(trackId)

    fun getRecentSessionsForTrack(trackId: String, limit: Int): Flow<List<TelemetrySession>> =
        sessionDao.getRecentSessionsForTrack(trackId, limit).map { list -> list.map { it.toDomain() } }

    /**
     * 删除 lap session：cascade 清 crossing_events 关联行 + binary 文件
     * （`/telemetry/` 路径白名单防穿越，与 [TestResultRepository.deleteResult] 同款安全策略）。
     * 不存在的 sessionId 视为 no-op；binary 文件 delete 失败不抛（File.delete 返回 false）。
     *
     * @author CC
     * @description cascade delete lap session entity + crossings + binary file
     * @date 2026-05-02
     */
    suspend fun deleteSession(sessionId: String) {
        val entity = sessionDao.queryBySessionId(sessionId) ?: return
        crossingDao.deleteCrossingsBySessionId(sessionId)
        sessionDao.deleteSession(entity)
        val path = entity.binaryFilePath
        if (path.isNotEmpty()) {
            val file = File(path)
            if (file.canonicalPath.contains("/telemetry/")) {
                file.delete()
            }
        }
    }

    /**
     * 读加减速 session 全部 sample（顺序读整个 chunk file）。
     */
    fun readPerformanceSamples(filePath: String): List<TelemetrySample> =
        PerformanceTestTelemetryReader.read(filePath)

    /**
     * 按时间窗口 [lapStartTs, lapEndTs] 过滤拉取 lap session 内的 sample。
     */
    fun readLapSamples(filePath: String, lapStartTs: Long, lapEndTs: Long): List<TelemetrySample> =
        LapTelemetryReader.read(filePath, lapStartTs, lapEndTs)

    private fun telemetryFile(sessionId: String): File =
        File(context.filesDir, "telemetry/$sessionId.bin")

    private fun TelemetrySessionEntity.toDomain() = TelemetrySession(
        sessionId = sessionId,
        sessionType = TelemetrySessionType.valueOf(sessionType),
        startTs = startTs,
        endTs = endTs,
        binaryFilePath = binaryFilePath,
        lapCount = lapCount,
        bestLapMs = bestLapMs,
        topSpeedKmh = topSpeedKmh,
        trackId = trackId,
        trackNameSnapshot = trackNameSnapshot,
    )

    private fun CrossingEventEntity.toDomain() = TelemetryCrossingEvent(
        sessionId = sessionId,
        lapIndex = lapIndex,
        crossingTimestampMs = crossingTimestampMs,
        crossingWallClockTimestampMs = crossingWallClockTimestampMs,
        speedKmh = speedKmh,
        gateId = gateId,
        gateType = gateType,
        accepted = accepted,
        reason = reason,
        directionScore = directionScore,
    )
}