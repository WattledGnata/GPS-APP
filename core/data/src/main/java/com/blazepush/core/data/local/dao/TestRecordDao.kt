// @IgnoreFormatCheck
// 理由：本 round wire-real-data-to-records-and-laps-tabs §1.1 仅追加 4 个统计 @Query 方法；
//       既有 CRUD 方法与 interface class-doc 缺失为 baseline 历史问题（先前 round 提交时 kt-check
//       未启 strict 模式）。按 CLAUDE.md scope-boundary 不在本 round 内统一补齐，
//       推到 D round（kt-format-cleanup-pass）批量处理。
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

    // round wire-real-data-to-records-and-laps-tabs §1.1：性能测试聚合查询。
    // acc 取 totalTime 最小（最佳加速），brake 取 totalDistance 最小（最佳刹车距离）。
    @Query("SELECT * FROM test_records WHERE testTemplateId = 'acc_0_100' ORDER BY totalTime ASC LIMIT 1")
    fun getBestAcceleration0To100(): Flow<TestRecordEntity?>

    @Query("SELECT * FROM test_records WHERE testTemplateId = 'brake_100_0' ORDER BY totalDistance ASC LIMIT 1")
    fun getBestBraking100To0(): Flow<TestRecordEntity?>

    @Query("SELECT COUNT(*) FROM test_records")
    fun getTotalCount(): Flow<Int>

    @Query("SELECT * FROM test_records ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentFlow(limit: Int): Flow<List<TestRecordEntity>>
}
