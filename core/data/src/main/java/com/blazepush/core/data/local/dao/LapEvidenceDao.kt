package com.blazepush.core.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.blazepush.core.data.local.entity.LapEvidenceEntity

@Dao
interface LapEvidenceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LapEvidenceEntity)

    @Query("SELECT * FROM lap_evidence WHERE sessionId = :sessionId AND lapIndex = :lapIndex")
    suspend fun find(sessionId: String, lapIndex: Int): LapEvidenceEntity?

    @Query("SELECT * FROM lap_evidence WHERE sessionId = :sessionId ORDER BY lapIndex")
    suspend fun findBySession(sessionId: String): List<LapEvidenceEntity>
}
