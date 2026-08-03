// @IgnoreFormatCheck
// 理由：本 round wire-real-data-to-records-and-laps-tabs §1.4 追加 4 个统计 Flow 方法；
//       既有方法 doc 缺失为 baseline 历史问题，按 scope-boundary 推到 D round
//       （kt-format-cleanup-pass）批量补齐，本 round 不顺手改。
package com.blazepush.core.data.repository

import android.content.Context
import android.util.Log
import com.blazepush.core.data.local.binary.BinaryTelemetryWriter
import com.blazepush.core.data.local.binary.BinaryTelemetryRecovery
import com.blazepush.core.data.local.binary.LapTelemetryReader
import com.blazepush.core.data.local.binary.PerformanceTestTelemetryReader
import com.blazepush.core.data.local.dao.CrossingEventDao
import com.blazepush.core.data.local.dao.LapEvidenceDao
import com.blazepush.core.data.local.dao.TelemetrySessionDao
import com.blazepush.core.data.local.dao.VideoSegmentDao
import com.blazepush.core.data.local.entity.CrossingEventEntity
import com.blazepush.core.data.local.entity.LapEvidenceEntity
import com.blazepush.core.data.local.entity.TelemetrySessionEntity
import com.blazepush.core.data.local.entity.VideoSegmentEntity
import com.blazepush.core.domain.model.LapTelemetry
import com.blazepush.core.domain.model.LapEvidence
import com.blazepush.core.domain.model.LapConfidencePolicy
import com.blazepush.core.domain.model.LapEvidenceFlag
import com.blazepush.core.domain.model.LapGapInterval
import com.blazepush.core.domain.model.LapReviewProvenance
import com.blazepush.core.domain.model.LapTelemetrySample
import com.blazepush.core.domain.model.TelemetryCrossingEvent
import com.blazepush.core.domain.model.TelemetrySample
import com.blazepush.core.domain.model.TelemetrySession
import com.blazepush.core.domain.model.SessionVideoStats
import com.blazepush.core.domain.model.TelemetrySessionType
import com.blazepush.core.domain.model.VideoSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID
import com.google.gson.Gson

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
    // video-segment-schema round ②a：视频段一对多 DAO（attach append + 全段 cascade）
    private val videoSegmentDao: VideoSegmentDao,
    private val telemetryIoDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val flushWriter: suspend (BinaryTelemetryWriter) -> Unit = { writer -> writer.flush() },
    private val lapEvidenceDao: LapEvidenceDao? = null,
) {
    private val gson = Gson()
    enum class FlushResult { FLUSHED, ALREADY_DURABLE, SESSION_CHANGED, NO_ACTIVE_SESSION }
    data class RecoveredLapSession(
        val sessionId: String,
        val lapCount: Int,
        val bestLapMs: Long?,
        val endTs: Long,
        val videoSegmentCount: Int,
    )

    data class FailedLapSessionRecovery(
        val sessionId: String,
        val errorType: String,
    )

    data class LapSessionRecoveryReport(
        val candidates: Int,
        val recovered: List<RecoveredLapSession>,
        val failed: List<FailedLapSessionRecovery>,
    )

    private data class PersistedLapEvidence(
        val lapCount: Int,
        val bestLapMs: Long?,
        val topSpeedKmh: Double?,
        val lastCrossingWallClock: Long?,
        val lastSampleDeltaMs: Long?,
    )

    private var activeWriter: BinaryTelemetryWriter? = null
    private var activeSessionId: String? = null
    private var activeSessionType: TelemetrySessionType = TelemetrySessionType.PERFORMANCE_TEST
    // persist-session-summary-fields round 加：endSession 时需要 binary 文件路径扫 sample 派生 topSpeedKmh
    private var activeFilePath: String? = null
    private val flushMutex = Mutex()
    private var activeWriteSequence = 0L
    private var durableWriteSequence = 0L
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
        val published = flushMutex.withLock {
            if (activeWriter != null) {
                false
            } else {
                activeWriter = writer
                activeSessionId = sessionId
                activeSessionType = type
                activeFilePath = file.absolutePath
                activeSessionStartTs = startTs
                activeWriteSequence = 0L
                // Header creation has not crossed a force() boundary yet.
                durableWriteSequence = -1L
                true
            }
        }
        if (!published) {
            // Existing callers end the active session before starting another. Fail closed instead
            // of silently replacing/leaking its writer if that contract is violated concurrently.
            withContext(telemetryIoDispatcher) { writer.close() }
            sessionDao.deleteSession(entity)
            file.delete()
            error("Cannot replace an active telemetry session")
        }
        return sessionId
    }

    /**
     * 提交一帧 sample 到 active writer 队列。
     */
    suspend fun writeSample(sample: TelemetrySample) {
        flushMutex.withLock {
            activeWriter?.write(sample) ?: return@withLock
            activeWriteSequence++
        }
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
    suspend fun flush(expectedSessionId: String? = null): FlushResult {
        return withContext(telemetryIoDispatcher) {
            flushMutex.withLock {
                val sessionId = expectedSessionId ?: activeSessionId
                    ?: return@withLock FlushResult.NO_ACTIVE_SESSION
                if (activeSessionId != sessionId) return@withLock FlushResult.SESSION_CHANGED
                val writer = activeWriter ?: return@withLock FlushResult.NO_ACTIVE_SESSION
                val targetSequence = activeWriteSequence
                if (durableWriteSequence >= targetSequence) {
                    return@withLock FlushResult.ALREADY_DURABLE
                }
                flushWriter(writer)
                if (activeSessionId != sessionId || activeWriter !== writer) {
                    return@withLock FlushResult.SESSION_CHANGED
                }
                durableWriteSequence = targetSequence
                FlushResult.FLUSHED
            }
        }
    }

    /**
     * 关闭 session：close writer + 用 IO 调度扫 binary 派生 topSpeedKmh + 用 crossings 派生
     * lapCount/bestLapMs（accepted SF crossing pairs 语义）+ updateSummary 一次写齐 4 字段。
     *
     * lapCount 派生语义：accepted=true && gateType="StartFinish" 的 crossing 相邻配对数量
     * （= durations.size），与 LapSessionDetailScreen.deriveDetailMetrics（站点 B）/ getLapTelemetry
     * （站点 C）严格同源。配对键 unify-lap-count-pairing-semantics round 已统一为
     * `crossingWallClockTimestampMs ?: Long.MAX_VALUE` 升序（与 getLapTelemetry 同款排序键，
     * 接收侧真壁钟域，避免 GPS 协议时钟 mod 3,600,000 跨整点回绕导致的负 duration）。
     * duration 仅对"起止两 crossing wallClock 均非空"的相邻对计算；任一端 null 的相邻对不计有效圈
     * （与 getLapTelemetry 对 null wallClock 圈返回 null = "该圈不可读" 收敛）。
     * **不**承诺 LapRecord.qualityFlags 过滤（crossing 表无该字段；与 Snackbar in-memory 路径计数
     * 差异由 unify-lap-count-pairing-semantics spec ADDED requirement「Snackbar 实时计数归口为
     * display-only」normative 锁定为有意保留的设计，非 bug）。
     *
     * binary 文件不存在 / 为空 → topSpeedKmh = null；crossings 为空 → lapCount = 0 / bestLapMs = null；
     * 全部 accepted SF crossing 的 wallClock 均为 null（§8.3 migration 之前历史 session）→ lapCount = 0。
     * 不抛异常。
     *
     * @author CC
     * @description close writer + derive summary + persist on endSession
     * @date 2026-05-01
     */
    suspend fun endSession(sessionId: String) {
        val filePath = flushMutex.withLock {
            if (activeSessionId != sessionId) return
            val writer = activeWriter ?: return
            val path = activeFilePath
            withContext(telemetryIoDispatcher) { writer.close() }
            activeWriter = null
            activeSessionId = null
            activeFilePath = null
            activeSessionStartTs = null
            activeWriteSequence = 0L
            durableWriteSequence = 0L
            path
        }
        val endTs = System.currentTimeMillis()

        // 正常结束与冷启动恢复共享 persisted evidence 口径，防两个入口的圈数/最佳圈漂移。
        val evidence = derivePersistedLapEvidence(sessionId, filePath)

        sessionDao.updateSummary(
            sessionId = sessionId,
            endTs = endTs,
            lapCount = evidence.lapCount,
            bestLapMs = evidence.bestLapMs,
            topSpeedKmh = evidence.topSpeedKmh,
        )
    }

    /**
     * 冷启动恢复上个进程未正常结束的圈速 session。
     *
     * 仅修复本地 summary；不接触 Livetiming、pending upload、crossing、binary 或视频文件。
     * 每条候选独立失败，成功写回后因 endTs > startTs 天然幂等。
     */
    suspend fun recoverIncompleteLapSessions(
        processStartedAtMs: Long,
        recoveryNowMs: Long = System.currentTimeMillis(),
    ): LapSessionRecoveryReport = withContext(Dispatchers.IO) {
        val candidates = sessionDao.queryIncompleteLapSessions(processStartedAtMs)
        val recovered = mutableListOf<RecoveredLapSession>()
        val failed = mutableListOf<FailedLapSessionRecovery>()

        for (candidate in candidates) {
            runCatching {
                val current = sessionDao.queryBySessionId(candidate.sessionId)
                    ?: error("session disappeared")
                // 查询候选后可能被其他正常收尾路径闭环；写前二次确认保证幂等。
                if (current.endTs > current.startTs) return@runCatching null

                current.binaryFilePath.takeIf { it.isNotBlank() }?.let {
                    BinaryTelemetryRecovery.repair(it)
                }

                val evidence = derivePersistedLapEvidence(
                    sessionId = current.sessionId,
                    filePath = current.binaryFilePath,
                )
                val segments = videoSegmentDao.queryBySessionId(current.sessionId)
                val endTs = deriveRecoveredEndTs(
                    session = current,
                    evidence = evidence,
                    segments = segments,
                    recoveryNowMs = recoveryNowMs,
                )

                val latest = sessionDao.queryBySessionId(current.sessionId)
                    ?: error("session disappeared before summary update")
                if (latest.endTs > latest.startTs) return@runCatching null

                sessionDao.updateSummary(
                    sessionId = current.sessionId,
                    endTs = endTs,
                    lapCount = evidence.lapCount,
                    bestLapMs = evidence.bestLapMs,
                    topSpeedKmh = evidence.topSpeedKmh,
                )
                RecoveredLapSession(
                    sessionId = current.sessionId,
                    lapCount = evidence.lapCount,
                    bestLapMs = evidence.bestLapMs,
                    endTs = endTs,
                    videoSegmentCount = segments.size,
                )
            }.onSuccess { result ->
                if (result != null) recovered += result
            }.onFailure { error ->
                failed += FailedLapSessionRecovery(
                    sessionId = candidate.sessionId,
                    errorType = error::class.simpleName ?: "UnknownError",
                )
            }
        }

        LapSessionRecoveryReport(
            candidates = candidates.size,
            recovered = recovered,
            failed = failed,
        )
    }

    private suspend fun derivePersistedLapEvidence(
        sessionId: String,
        filePath: String?,
    ): PersistedLapEvidence = withContext(Dispatchers.IO) {
        val samples = filePath
            ?.let { runCatching { PerformanceTestTelemetryReader.read(it) }.getOrDefault(emptyList()) }
            .orEmpty()
        val crossings = crossingDao.queryBySessionId(sessionId)
        val acceptedStartFinish = crossings
            .filter { it.gateType.equals("StartFinish", ignoreCase = true) && it.accepted }
            .sortedBy { it.crossingWallClockTimestampMs ?: Long.MAX_VALUE }
        val durations = acceptedStartFinish.zipWithNext { a, b -> a to b }
            .mapNotNull { (a, b) ->
                val start = a.crossingWallClockTimestampMs
                val end = b.crossingWallClockTimestampMs
                if (start != null && end != null) end - start else null
            }
        val evidenceByLap = lapEvidenceDao?.findBySession(sessionId).orEmpty()
            .associate { it.lapIndex to it.toDomainEvidence() }
        // A null DAO exists only in legacy JVM fakes. Production DI always supplies it.
        val decisions = if (lapEvidenceDao == null) emptyMap() else durations.indices.associateWith { lapIndex ->
            LapConfidencePolicy.evaluate(evidenceByLap[lapIndex + 1])
        }
        val comparableDurations = if (lapEvidenceDao == null) durations.withIndex().toList() else {
            durations.withIndex().filter { decisions.getValue(it.index).eligibility.comparison }
        }
        val pbDurations = if (lapEvidenceDao == null) durations else durations.withIndex()
            .filter { decisions.getValue(it.index).eligibility.personalBest }
            .map { it.value }

        PersistedLapEvidence(
            lapCount = comparableDurations.size,
            bestLapMs = pbDurations.minOrNull(),
            topSpeedKmh = samples.maxOfOrNull { it.speedKmh }?.takeIf { it > 0.0 },
            lastCrossingWallClock = crossings.mapNotNull { it.crossingWallClockTimestampMs }.maxOrNull(),
            lastSampleDeltaMs = samples.maxOfOrNull { it.tsDeltaMs },
        )
    }

    private fun deriveRecoveredEndTs(
        session: TelemetrySessionEntity,
        evidence: PersistedLapEvidence,
        segments: List<VideoSegmentEntity>,
        recoveryNowMs: Long,
    ): Long {
        val videoEndCandidates = segments.mapNotNull { segment ->
            segment.endWallClock
                ?: segment.durationMs?.let { duration -> safeAdd(segment.startWallClock, duration) }
                ?: segment.startWallClock
        }
        val sampleEnd = evidence.lastSampleDeltaMs
            ?.takeIf { it >= 0L }
            ?.let { safeAdd(session.startTs, it) }
        val latestEvidence = buildList {
            evidence.lastCrossingWallClock?.let(::add)
            sampleEnd?.let(::add)
            addAll(videoEndCandidates)
        }.filter { it > session.startTs && it <= recoveryNowMs }
            .maxOrNull()

        return latestEvidence ?: safeAdd(session.startTs, 1L) ?: session.startTs
    }

    private fun safeAdd(left: Long, right: Long): Long? =
        runCatching { Math.addExact(left, right) }.getOrNull()

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

    /** Persist evidence only; raw telemetry and crossing rows remain the truth sources. */
    suspend fun writeLapEvidence(sessionId: String, lapIndex: Int, evidence: LapEvidence) {
        lapEvidenceDao?.upsert(
            LapEvidenceEntity(
                sessionId = sessionId,
                lapIndex = lapIndex,
                evidenceVersion = evidence.version,
                startCrossingTimestampMillis = evidence.startCrossingTimestampMillis,
                finishCrossingTimestampMillis = evidence.finishCrossingTimestampMillis,
                requiredGateIdsCsv = evidence.requiredGateIds.sorted().joinToString(","),
                acceptedGateIdsCsv = evidence.acceptedGateIds.sorted().joinToString(","),
                gapIntervalsJson = gson.toJson(evidence.gaps),
                qualityFlagsCsv = evidence.flags.map { it.name }.sorted().joinToString(","),
                reviewProvenance = evidence.reviewProvenance.name,
            )
        )
    }

    suspend fun getLapEvidence(sessionId: String, lapIndex: Int): LapEvidence? =
        lapEvidenceDao?.find(sessionId, lapIndex)?.toDomainEvidence()

    suspend fun getLapEvidenceForSession(sessionId: String): Map<Int, LapEvidence> =
        lapEvidenceDao?.findBySession(sessionId).orEmpty().associate { it.lapIndex to it.toDomainEvidence() }

    private fun LapEvidenceEntity.toDomainEvidence(): LapEvidence = LapEvidence(
        version = evidenceVersion,
        startCrossingTimestampMillis = startCrossingTimestampMillis,
        finishCrossingTimestampMillis = finishCrossingTimestampMillis,
        requiredGateIds = requiredGateIdsCsv.csvSet(),
        acceptedGateIds = acceptedGateIdsCsv.csvSet(),
        gaps = gson.fromJson(gapIntervalsJson, Array<LapGapInterval>::class.java)?.toList().orEmpty(),
        flags = qualityFlagsCsv.csvSet().mapNotNull { runCatching { LapEvidenceFlag.valueOf(it) }.getOrNull() }.toSet(),
        reviewProvenance = runCatching { LapReviewProvenance.valueOf(reviewProvenance) }
            .getOrDefault(LapReviewProvenance.LegacyUnknown),
    )

    private fun String.csvSet(): Set<String> = split(',').filter { it.isNotBlank() }.toSet()

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
     * 删除 lap session：cascade 清 crossing_events 关联行 + binary 文件 + 视频文件（若有）。
     * 白名单防路径穿越：binary 需含 `/telemetry/`，视频需含 `/telemetry/` 或 `/video/`。
     * 不存在的 sessionId 视为 no-op；File.delete 失败不抛，埋 FileLogger.e 日志。
     *
     * @author CC
     * @description cascade delete lap session entity + crossings + binary file + video file
     * @date 2026-05-30
     */
    suspend fun deleteSession(sessionId: String) {
        val entity = sessionDao.queryBySessionId(sessionId) ?: return
        // video-segment-schema round ②a：行删除前先取全段路径逐个删文件
        //（FK CASCADE 只删行不管文件系统；旧字段单文件与最新段同路径，二次 delete no-op）。
        // 段行显式删（FK CASCADE 仅兜底——不依赖 pragma 状态，且与 deleteSessionVideo 语义一致）。
        val segments = videoSegmentDao.queryBySessionId(sessionId)
        segments.forEach { deleteVideoFileIfPresent(it.filePath, "deleteSession-segment") }
        videoSegmentDao.deleteBySessionId(sessionId)
        if (segments.isNotEmpty()) {
            Log.d("VideoSegment", "deleteSession cascade removed ${segments.size} segment files: sessionId=$sessionId")
        }
        crossingDao.deleteCrossingsBySessionId(sessionId)
        sessionDao.deleteSession(entity)
        // 删 binary file（原有逻辑）
        val path = entity.binaryFilePath
        if (path.isNotEmpty()) {
            val file = File(path)
            if (file.canonicalPath.contains("/telemetry/")) {
                file.delete()
            }
        }
        // 删视频文件（session-video-metadata-persist round；video-storage-cleanup round 抽 helper 复用）
        deleteVideoFileIfPresent(entity.videoFilePath, "deleteSession")
    }

    /**
     * 统一视频文件删除（video-storage-cleanup round · Decision 5）。
     * 白名单（canonicalPath 含 `/video/` 或 `/telemetry/`）防路径穿越；不存在 skip；删失败埋日志不抛。
     * deleteSession / attachVideoToSession(删旧) / deleteSessionVideo 三处复用，白名单单点维护。
     */
    private fun deleteVideoFileIfPresent(videoPath: String?, tag: String) {
        if (videoPath == null) return
        val videoFile = File(videoPath)
        val canonicalPath = videoFile.canonicalPath
        val allowedPaths = listOf("/telemetry/", "/video/")
        if (allowedPaths.none { canonicalPath.contains(it) }) {
            Log.d(tag, "video path not in whitelist, skip: $videoPath")
            return
        }
        if (!videoFile.exists()) {
            Log.d(tag, "video file not found, skip: $videoPath")
        } else if (videoFile.delete()) {
            Log.d(tag, "deleted video: $videoPath")
        } else {
            Log.e(tag, "failed to delete video: $videoPath")
        }
    }

    /**
     * 该 session 全部视频段（②c 回放/导出消费侧数据源，按 segmentIndex 升序）。
     */
    suspend fun getVideoSegments(sessionId: String): List<VideoSegment> =
        videoSegmentDao.queryBySessionId(sessionId).map { it.toDomain() }

    /**
     * 冷启动恢复“CameraX 已产生数据，但进程在 Finalize/绑定写库前被杀”的窗口。
     *
     * 新录像文件名固化为 `<session UUID>_<createdAt>.mp4`；Session 在 CameraX 启动前已经
     * 持久化，因此这里只恢复能验证 Session 存在、文件非空、且尚未绑定的文件。
     * 旧版纯时间戳文件名无法可靠反推归属，故 fail closed 不猜测。
     *
     * @return 本次新恢复的视频段数
     */
    suspend fun recoverSessionVideoFiles(): Int {
        val videoDir = File(context.filesDir, "video")
        val candidates = videoDir.listFiles { file -> file.isFile && file.extension.equals("mp4", true) }
            .orEmpty()
        var recovered = 0
        val pattern = Regex("^([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})_(\\d+)\\.mp4$")
        for (file in candidates) {
            val match = pattern.matchEntire(file.name) ?: continue
            if (file.length() <= 0L) continue
            val sessionId = match.groupValues[1]
            val createdAt = match.groupValues[2].toLongOrNull() ?: continue
            val session = sessionDao.queryBySessionId(sessionId) ?: continue
            val alreadyBound = videoSegmentDao.queryBySessionId(sessionId)
                .any { it.filePath == file.absolutePath } || session.videoFilePath == file.absolutePath
            if (alreadyBound) continue
            attachVideoToSession(
                sessionId = sessionId,
                videoFilePath = file.absolutePath,
                videoStartedAtWallClock = createdAt,
                playable = null,
                durationMs = null,
            )
            recovered++
            Log.d("VideoRecovery", "recovered interrupted recording: sessionId=$sessionId path=${file.name}")
        }
        return recovered
    }

    /**
     * playable 首播回写 wrapper（②c）。失败由调用方 catch，仅日志不阻塞播放。
     */
    suspend fun updateSegmentPlayable(id: Long, playable: Boolean) {
        videoSegmentDao.updatePlayable(id, playable)
        Log.d("VideoSegment", "playable write-back: id=$id playable=$playable")
    }

    private fun VideoSegmentEntity.toDomain() = VideoSegment(
        id = id,
        sessionId = sessionId,
        segmentIndex = segmentIndex,
        filePath = filePath,
        startWallClock = startWallClock,
        endWallClock = endWallClock,
        durationMs = durationMs,
        playable = playable,
    )

    /**
     * 存量 PERFORMANCE_TEST 孤儿行一次性 sweep（cleanup-perftest-telemetry-session-orphan round）。
     * App 启动时由 BlazePushApplication 调一次；幂等，cascade 修复后理论恒返回 0。
     * 落盘锚点（FileLogger）在调用方——core/data 依赖方向不可达 feature/test 的 FileLogger。
     *
     * @return 删除行数（=0 为健康基线，>0 说明有旧存量或 cascade 漏）
     */
    suspend fun cleanupPerftestOrphans(): Int {
        val removed = sessionDao.deletePerftestOrphans()
        Log.d("PerftestCascade", "cleanupPerftestOrphans removed=$removed")
        return removed
    }

    /**
     * 单删 session 视频（video-storage-cleanup round · 成绩页"删视频"，保留圈速成绩）。
     * 删视频文件 + 置空 video 字段；MUST NOT 动圈速 / crossing / binary / session 行。
     */
    suspend fun deleteSessionVideo(sessionId: String) {
        val entity = sessionDao.queryBySessionId(sessionId) ?: return
        // video-segment-schema round ②a：全段删除（文件 + 行），旧字段照旧置空。
        val segments = videoSegmentDao.queryBySessionId(sessionId)
        segments.forEach { deleteVideoFileIfPresent(it.filePath, "deleteSessionVideo-segment") }
        videoSegmentDao.deleteBySessionId(sessionId)
        deleteVideoFileIfPresent(entity.videoFilePath, "deleteSessionVideo")
        sessionDao.clearVideo(sessionId)
        Log.d(
            "VideoSegment",
            "deleteSessionVideo: removed ${segments.size} segments, lap data kept: sessionId=$sessionId",
        )
    }

    /**
     * Session 级录像统计。分段表是事实源；表为空时才使用旧 videoFilePath 兼容存量数据。
     * 文件大小读取失败按 0 处理，不影响删除入口可达。
     */
    suspend fun getSessionVideoStats(sessionId: String): SessionVideoStats {
        val entity = sessionDao.queryBySessionId(sessionId)
            ?: return SessionVideoStats(segmentCount = 0, existingFileCount = 0, totalBytes = 0L)
        val segments = videoSegmentDao.queryBySessionId(sessionId)
        val paths = if (segments.isNotEmpty()) {
            segments.map { it.filePath }.distinct()
        } else {
            listOfNotNull(entity.videoFilePath)
        }
        val files = paths.map(::File).filter { it.exists() && it.isFile }
        return SessionVideoStats(
            segmentCount = if (segments.isNotEmpty()) segments.size else paths.size,
            existingFileCount = files.size,
            totalBytes = files.sumOf { runCatching { it.length() }.getOrDefault(0L) },
        )
    }

    /**
     * 写入视频元数据（供录制引擎调用；video-segment-schema round ②a 改 append 语义）。
     *
     * 多段模型（一对多 video_segments 表）：
     * 1. INSERT 子表新段（segmentIndex = 现有 max+1，首段 0）——停录再录 / ERROR 救援重录
     *    全部保留，不再覆盖（修 2026-06-03 路测"圈 1 救援段被 5 秒尾段覆盖"）。
     * 2. 照旧 UPDATE session 旧字段 = 本段（双写向后兼容：16 个消费文件零改动，读到"最新段"
     *    与改造前一致；②c 切到子表后废弃旧字段写入）。
     *
     * round A"覆盖前删旧文件"已取消——旧段是子表登记的合法数据，删除走成绩页删视频（全段）
     * 或 deleteSession。两步顺序写（INSERT 先 UPDATE 后）：repository 不持 db 实例无跨 DAO
     * 事务能力；半写最坏情形 = 段已入子表但旧字段陈旧，消费方读旧段不致命，日志可诊断。
     *
     * @param playable true=Finalize OK；null=ERROR 救援时长未知（②c 首播回写）
     * @param durationMs Finalize event 可取则传（endWallClock = start + duration），取不到 null
     * @author CC
     * @description append video segment + dual-write legacy fields (②a)
     * @date 2026-06-07
     */
    suspend fun attachVideoToSession(
        sessionId: String,
        videoFilePath: String,
        videoStartedAtWallClock: Long,
        playable: Boolean?,
        durationMs: Long?,
    ) {
        val nextIndex = (videoSegmentDao.maxSegmentIndex(sessionId) ?: -1) + 1
        videoSegmentDao.insert(
            VideoSegmentEntity(
                sessionId = sessionId,
                segmentIndex = nextIndex,
                filePath = videoFilePath,
                startWallClock = videoStartedAtWallClock,
                endWallClock = durationMs?.let { videoStartedAtWallClock + it },
                durationMs = durationMs,
                playable = playable,
            )
        )
        sessionDao.updateVideoMetadata(
            sessionId = sessionId,
            videoFilePath = videoFilePath,
            videoStartedAtWallClock = videoStartedAtWallClock,
        )
        Log.d(
            "VideoSegment",
            "segment appended: sessionId=$sessionId index=$nextIndex playable=$playable " +
                "durationMs=$durationMs path=$videoFilePath (legacy fields dual-written)",
        )
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

    /**
     * 单圈完整 telemetry 切片读取。
     * crossing wallClock=null → null（MUST NOT fallback 到 crossingTimestampMs）；
     * lapIndex 越界 → null；binary 缺失/空 → null（不抛异常）。
     *
     * sectorBoundaries 派生语义（future-sector-derivation round）：
     * 从 lap 窗口 [lapStartWallClock, lapEndWallClock) 内 accepted Sector 过线的
     * crossingWallClockTimestampMs 升序前置 lapStartWallClock 组成；窗口内 == lapStart 的
     * 退化项去重；无 accepted/非空 wallClock 的 sector 过线时回退单段 listOf(lapStartWallClock)。
     * 窗口判定与取值统一用 wallClock（与 lapStart/lapEnd/binary absoluteTsMs 同时钟域），
     * MUST NOT 用 crossingTimestampMs（GPS 协议时钟）做 sector 窗口判定/取值。
     *
     * @author CC
     * @description single-lap complete telemetry slice reader
     * @date 2026-05-04
     */
    suspend fun getLapTelemetry(sessionId: String, lapIndex: Int): LapTelemetry? {
        val entity = sessionDao.queryBySessionId(sessionId) ?: return null
        val crossings = crossingDao.queryBySessionId(sessionId)
        val acceptedSF = crossings
            .filter { it.gateType.equals("StartFinish", ignoreCase = true) && it.accepted }
            .sortedBy { it.crossingWallClockTimestampMs ?: Long.MAX_VALUE }
        if (lapIndex < 0 || lapIndex + 1 >= acceptedSF.size) return null
        val lapStartWallClock = acceptedSF[lapIndex].crossingWallClockTimestampMs ?: return null
        val lapEndWallClock = acceptedSF[lapIndex + 1].crossingWallClockTimestampMs ?: return null
        val rawSamples = withContext(Dispatchers.IO) {
            runCatching { LapTelemetryReader.read(entity.binaryFilePath, lapStartWallClock, lapEndWallClock) }
                .getOrDefault(emptyList())
        }
        if (rawSamples.isEmpty()) return null
        val samples = rawSamples.map { sample ->
            LapTelemetrySample(
                absoluteTsMs = entity.startTs + sample.tsDeltaMs,
                elapsedMsInLap = entity.startTs + sample.tsDeltaMs - lapStartWallClock,
                lat = sample.lat,
                lon = sample.lon,
                speedKmh = sample.speedKmh,
                bearingDeg = sample.bearingDeg,
                accelerationG = null,
                flags = sample.flags,
            )
        }
        // sectorBoundaries 派生（future-sector-derivation round）：复用上方已读 crossings，
        // 取 lap 窗口 [lapStartWallClock, lapEndWallClock) 内 accepted Sector 过线 wallClock，
        // 升序前置 lapStart 组成多段；空集回退单段（不回归 baseline 单段行为）。
        val sectorWallClocks = crossings
            .filter {
                it.gateType.equals("Sector", ignoreCase = true) &&
                    it.accepted &&
                    it.crossingWallClockTimestampMs != null
            }
            .mapNotNull { it.crossingWallClockTimestampMs }
            .filter { it >= lapStartWallClock && it < lapEndWallClock && it != lapStartWallClock }
            .sorted()
        val sectorBoundaries = listOf(lapStartWallClock) + sectorWallClocks
        return LapTelemetry(
            sessionId = sessionId,
            lapIndex = lapIndex,
            lapStartWallClock = lapStartWallClock,
            lapEndWallClock = lapEndWallClock,
            lapDurationMs = lapEndWallClock - lapStartWallClock,
            samples = samples,
            sectorBoundaries = sectorBoundaries,
            trackId = entity.trackId,
            trackNameSnapshot = entity.trackNameSnapshot,
        )
    }

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
        videoFilePath = videoFilePath,
        videoStartedAtWallClock = videoStartedAtWallClock,
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
