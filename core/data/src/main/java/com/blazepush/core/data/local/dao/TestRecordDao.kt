package com.blazepush.core.data.local.dao

import androidx.room.*
import com.blazepush.core.data.local.entity.TestRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TestRecordDao {
    @Query("SELECT * FROM test_records ORDER BY timestamp DESC")
    fun getAllTestRecordsFlow(): Flow<List<TestRecordEntity>>

    @Query("SELECT * FROM test_records ORDER BY timestamp DESC")
    suspend fun getAllTestRecordsSync(): List<TestRecordEntity>

    @Query("SELECT * FROM test_records WHERE id = :id")
    suspend fun getTestRecordById(id: String): TestRecordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTestRecord(record: TestRecordEntity)

    @Delete
    suspend fun deleteTestRecord(record: TestRecordEntity)

    @Query("DELETE FROM test_records")
    suspend fun deleteAllTestRecords()
}
