package com.race.gps.data.repository

import com.race.gps.data.local.dao.TestRecordDao
import com.race.gps.data.local.mapper.toEntity
import com.race.gps.data.local.mapper.toModel
import com.race.gps.data.model.TestRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class TestRecordRepository(
    private val testRecordDao: TestRecordDao
) {
    // Flow自动从Room获取
    val testRecordsFlow: Flow<List<TestRecord>> =
        testRecordDao.getAllTestRecordsWithDataPoints()
            .map { list -> list.map { it.toModel() } }

    suspend fun saveTestRecords(testRecords: List<TestRecord>) {
        testRecords.forEach { testRecord ->
            val (entity, dataPoints) = testRecord.toEntity()
            testRecordDao.insertTestRecord(entity)
            if (dataPoints.isNotEmpty()) {
                testRecordDao.insertDataPoints(dataPoints)
            }
        }
    }

    suspend fun getSavedTestRecords(): List<TestRecord> {
        return testRecordDao.getAllTestRecordsWithDataPointsSync().map { it.toModel() }
    }

    suspend fun addTestRecord(testRecord: TestRecord) {
        val (entity, dataPoints) = testRecord.toEntity()
        testRecordDao.insertTestRecord(entity)
        if (dataPoints.isNotEmpty()) {
            testRecordDao.insertDataPoints(dataPoints)
        }
    }

    suspend fun removeTestRecord(testRecord: TestRecord) {
        val (entity, _) = testRecord.toEntity()
        testRecordDao.deleteTestRecord(entity)
    }

    suspend fun clearAllTestRecords() {
        testRecordDao.deleteAllTestRecords()
    }
}
