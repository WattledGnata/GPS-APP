// @IgnoreFormatCheck
// 理由：本 round wire-real-data-to-records-and-laps-tabs §1.2 追加 4 个统计 @Query 方法；
//       既有方法 doc 缺失为 baseline 历史问题，按 scope-boundary 推到 D round
//       （kt-format-cleanup-pass）批量补齐，本 round 不顺手改。
package com.blazepush.core.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.blazepush.core.data.local.entity.TelemetrySessionEntity
import kotlinx.coroutines.flow.Flow

/**
 * Telemetry session metadata DAO（A56 引入）。
 * Room 只存元数据（sessionId、起止 ts、binary file 路径），原始点阵在 binary 文件。
 *
 * @author CC
 * @description telemetry session metadata DAO
 * @date 2026-04-30
 */
@Dao
interface TelemetrySessionDao {

    /**
     * 插入新 session metadata；REPLACE 策略允许重启时覆盖同 id。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: TelemetrySessionEntity)

    /**
     * Session 结束时更新 endTs（startTs/binary 路径不变）。
     * baseline A56 引入；persist-session-summary-fields round 保留兼容（其他 callsite 仍可用），
     * 但 LAP_SESSION endSession 现在用 [updateSummary] 一次写齐 4 字段。
     */
    @Query("UPDATE telemetry_sessions SET endTs = :endTs WHERE sessionId = :sessionId")
    suspend fun updateEndTs(sessionId: String, endTs: Long)

    /**
     * Session 结束时一次写齐 4 个 summary 字段（endTs / lapCount / bestLapMs / topSpeedKmh）。
     * 由 [com.blazepush.core.data.repository.TelemetryRepository.endSession] 调用。
     * trackId / trackNameSnapshot 在 startSession 时已写，update 不动。
     *
     * @author CC
     * @description bulk update of session summary fields on endSession
     * @date 2026-05-01
     */
    @Query("UPDATE telemetry_sessions SET endTs = :endTs, lapCount = :lapCount, bestLapMs = :bestLapMs, topSpeedKmh = :topSpeedKmh WHERE sessionId = :sessionId")
    suspend fun updateSummary(
        sessionId: String,
        endTs: Long,
        lapCount: Int,
        bestLapMs: Long?,
        topSpeedKmh: Double?,
    )

    /**
     * 按 sessionId 拿 metadata，未找到返回 null。
     */
    @Query("SELECT * FROM telemetry_sessions WHERE sessionId = :sessionId LIMIT 1")
    suspend fun queryBySessionId(sessionId: String): TelemetrySessionEntity?

    /**
     * 拿所有 session metadata，按 startTs 降序（最近的在前）。
     */
    @Query("SELECT * FROM telemetry_sessions ORDER BY startTs DESC")
    suspend fun queryAll(): List<TelemetrySessionEntity>

    /**
     * 冷启动恢复候选：只处理本进程启动前已存在、仍保持 startSession 占位值的圈速 session。
     *
     * 使用默认实现复用 [queryAll]，让既有 Fake DAO 自动继承同一过滤语义；数据量是本机圈速
     * session 量级，不引入新 schema/index。`createdBeforeMs` 是进程启动 cutoff，防异步恢复任务
     * 误收尾本次进程刚创建的 session。
     */
    suspend fun queryIncompleteLapSessions(createdBeforeMs: Long): List<TelemetrySessionEntity> =
        queryAll().filter { entity ->
            entity.sessionType == "LAP_SESSION" &&
                entity.endTs <= entity.startTs &&
                entity.startTs < createdBeforeMs
        }

    // round wire-real-data-to-records-and-laps-tabs §1.2：按 trackId 聚合查询。
    // 关键 schema 口径：
    // - 列名 sessionType（不是 type），值 'LAP_SESSION'（与 TelemetrySessionType.LAP_SESSION.name 一致）
    // - endTs 非空，startSession 写 endTs=startTs 占位、endSession 才写真实值；
    //   闭环判定 MUST 用 endTs > startTs（不能 endTs IS NOT NULL）
    // - bestLapMs 可空（首圈未完成 / 无有效 best），best lap 查询 MUST 加 IS NOT NULL 排除

    @Query(
        "SELECT * FROM telemetry_sessions " +
            "WHERE trackId = :trackId AND endTs > startTs " +
            "AND bestLapMs IS NOT NULL AND sessionType = 'LAP_SESSION' " +
            "ORDER BY bestLapMs ASC LIMIT 1"
    )
    fun getBestLapForTrack(trackId: String): Flow<TelemetrySessionEntity?>

    @Query(
        "SELECT COUNT(*) FROM telemetry_sessions " +
            "WHERE trackId = :trackId AND endTs > startTs AND sessionType = 'LAP_SESSION'"
    )
    fun getSessionCountForTrack(trackId: String): Flow<Int>

    @Query(
        "SELECT COALESCE(SUM(lapCount), 0) FROM telemetry_sessions " +
            "WHERE trackId = :trackId AND endTs > startTs AND sessionType = 'LAP_SESSION'"
    )
    fun getTotalLapCountForTrack(trackId: String): Flow<Int>

    @Query(
        "SELECT * FROM telemetry_sessions " +
            "WHERE trackId = :trackId AND endTs > startTs AND sessionType = 'LAP_SESSION' " +
            "ORDER BY startTs DESC LIMIT :limit"
    )
    fun getRecentSessionsForTrack(trackId: String, limit: Int): Flow<List<TelemetrySessionEntity>>

    /**
     * 视频元数据写入（session-video-metadata-persist round 引入）。
     * 由 [com.blazepush.core.data.repository.TelemetryRepository.attachVideoToSession] 调用，
     * round 3 camera-recording-and-gps-sync 录制引擎在录制结束 + 首帧回调后调用。
     * 若 sessionId 不存在，Room UPDATE 无副作用（不抛）。
     *
     * @author CC
     * @description update video metadata (path + wallClock anchor) for a session
     * @date 2026-05-30
     */
    @Query("UPDATE telemetry_sessions SET videoFilePath = :videoFilePath, videoStartedAtWallClock = :videoStartedAtWallClock WHERE sessionId = :sessionId")
    suspend fun updateVideoMetadata(
        sessionId: String,
        videoFilePath: String,
        videoStartedAtWallClock: Long,
    )

    /**
     * 置空 video 元数据（video-storage-cleanup round · 成绩页单删视频，保留圈速成绩）。
     * 调用方负责先删视频文件；本 query 只清字段，不删 session 行。
     */
    @Query("UPDATE telemetry_sessions SET videoFilePath = NULL, videoStartedAtWallClock = NULL WHERE sessionId = :sessionId")
    suspend fun clearVideo(sessionId: String)

    /**
     * 按 entity 删除单条 session metadata（add-history-deletion round 引入）。
     * 由 [com.blazepush.core.data.repository.TelemetryRepository.deleteSession] 调用，
     * 调用方负责先清 crossing_events 关联行 + binary 文件 cascade。
     */
    @Delete
    suspend fun deleteSession(entity: TelemetrySessionEntity)

    /**
     * 存量 PERFORMANCE_TEST 孤儿行一次性 sweep（cleanup-perftest-telemetry-session-orphan round）。
     * 孤儿 = 已被删除的 PERFORMANCE 测试记录留下的 telemetry_sessions 行（cascade 修复前的历史遗留）。
     * 反向 LIKE 关联检查（J round 真机 sanity check 实测写法，2/2 命中 0 误删）；
     * MUST NOT 改用 path 前缀 REPLACE 提取——对多用户路径 / 厂商 ROM filesDir / 格式迁移敏感，
     * 有误删正常记录风险（memo perftest-cascade-orphan-cleanup-deferred.md §5.3 反例）。
     * WHERE sessionType 限定保证 LAP_SESSION 行绝不参与。
     *
     * @return 删除行数（=0 为健康基线）
     */
    @Query(
        "DELETE FROM telemetry_sessions " +
            "WHERE sessionType = 'PERFORMANCE_TEST' " +
            "AND NOT EXISTS (" +
            "SELECT 1 FROM test_records tr " +
            "WHERE tr.dataFilePath LIKE '%' || sessionId || '%'" +
            ")"
    )
    suspend fun deletePerftestOrphans(): Int
}
