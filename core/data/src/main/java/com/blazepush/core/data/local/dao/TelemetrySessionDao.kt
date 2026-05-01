package com.blazepush.core.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.blazepush.core.data.local.entity.TelemetrySessionEntity

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
}