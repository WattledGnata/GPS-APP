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
     * 按 entity 删除单条 session metadata（add-history-deletion round 引入）。
     * 由 [com.blazepush.core.data.repository.TelemetryRepository.deleteSession] 调用，
     * 调用方负责先清 crossing_events 关联行 + binary 文件 cascade。
     */
    @Delete
    suspend fun deleteSession(entity: TelemetrySessionEntity)
}