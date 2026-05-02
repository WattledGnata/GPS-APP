package com.blazepush.core.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.blazepush.core.data.local.entity.CrossingEventEntity

/**
 * 圈速过线事件 DAO（A56 引入）。
 * 走 Room 事务保证写入原子性，过线事件是计时精度的真相源不能丢。
 *
 * @author CC
 * @description crossing event DAO with transactional insert
 * @date 2026-04-30
 */
@Dao
interface CrossingEventDao {

    /**
     * 事务式插入单条过线事件；REPLACE 冲突策略允许同 sessionId+lapIndex 重写。
     */
    @Transaction
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertInTransaction(entity: CrossingEventEntity)

    /**
     * 按 sessionId 查询所有过线事件，按时间升序。
     */
    @Query("SELECT * FROM crossing_events WHERE sessionId = :sessionId ORDER BY crossingTimestampMs ASC")
    suspend fun queryBySessionId(sessionId: String): List<CrossingEventEntity>

    /**
     * 按 sessionId 删除关联过线事件（add-history-deletion round 引入）。
     * 由 [com.blazepush.core.data.repository.TelemetryRepository.deleteSession] cascade 调用。
     */
    @Query("DELETE FROM crossing_events WHERE sessionId = :sessionId")
    suspend fun deleteCrossingsBySessionId(sessionId: String)
}