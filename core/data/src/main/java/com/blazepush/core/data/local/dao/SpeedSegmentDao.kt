package com.blazepush.core.data.local.dao

import androidx.room.*
import com.blazepush.core.data.local.entity.SpeedSegmentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SpeedSegmentDao {
    @Query("SELECT * FROM speed_segments WHERE testRecordId = :testRecordId ORDER BY startSpeed ASC")
    fun getSegmentsByTestId(testRecordId: String): Flow<List<SpeedSegmentEntity>>

    @Query("SELECT * FROM speed_segments WHERE testRecordId = :testRecordId ORDER BY startSpeed ASC")
    suspend fun getSegmentsByTestIdSync(testRecordId: String): List<SpeedSegmentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSegments(segments: List<SpeedSegmentEntity>)

    @Query("DELETE FROM speed_segments WHERE testRecordId = :testRecordId")
    suspend fun deleteSegmentsByTestId(testRecordId: String)
}
