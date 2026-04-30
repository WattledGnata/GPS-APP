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
     */
    @Query("UPDATE telemetry_sessions SET endTs = :endTs WHERE sessionId = :sessionId")
    suspend fun updateEndTs(sessionId: String, endTs: Long)

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