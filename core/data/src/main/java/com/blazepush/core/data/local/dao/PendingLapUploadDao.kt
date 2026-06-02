// @IgnoreFormatCheck
package com.blazepush.core.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.blazepush.core.data.local.entity.PendingLapUploadEntity

/**
 * 待传圈队列 DAO（livetiming-lap-upload）。
 *
 * `enqueue` 用 OnConflict.IGNORE：同 clientLapId 重复入队 = no-op，保留首条（不重置 retryCount），
 * 满足"同圈不重复堆积"（spec R3）。flush 成功 → deleteByClientLapId 出队。
 */
@Dao
interface PendingLapUploadDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enqueue(entity: PendingLapUploadEntity)

    @Query("SELECT * FROM pending_lap_uploads ORDER BY createdAtMs ASC")
    suspend fun all(): List<PendingLapUploadEntity>

    @Query("DELETE FROM pending_lap_uploads WHERE clientLapId = :clientLapId")
    suspend fun deleteByClientLapId(clientLapId: String)

    @Query("UPDATE pending_lap_uploads SET retryCount = retryCount + 1 WHERE clientLapId = :clientLapId")
    suspend fun incrementRetry(clientLapId: String)

    @Query("SELECT COUNT(*) FROM pending_lap_uploads")
    suspend fun count(): Int
}
