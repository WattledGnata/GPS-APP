package com.blazepush.core.data.repository

import com.blazepush.core.data.local.dao.SpeedSegmentDao
import com.blazepush.core.data.local.dao.TestRecordDao
import com.blazepush.core.data.local.entity.SpeedSegmentEntity
import com.blazepush.core.data.local.entity.TestRecordEntity
import com.blazepush.core.data.local.file.TestDataFileStorage
import com.blazepush.core.domain.model.SpeedSegment
import com.blazepush.core.domain.model.TestResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 测试结果仓库
 * 元数据存数据库，原始数据点存文件
 */
class TestResultRepository(
    private val testRecordDao: TestRecordDao,
    private val speedSegmentDao: SpeedSegmentDao,
    private val fileStorage: TestDataFileStorage
) {
    val testResultsFlow: Flow<List<TestRecordEntity>> =
        testRecordDao.getAllTestRecordsFlow()

    suspend fun saveResult(result: TestResult) {
        // 1. 保存原始数据点到文件
        val filePath = fileStorage.saveDataPoints(result.id, result.dataPoints)

        // 2. 保存元数据到数据库
        val entity = TestRecordEntity(
            id = result.id,
            testTemplateId = result.template.id,
            testType = result.template.name,
            carModel = result.carModel,
            deviceName = "RaceChrono GPS",
            deviceAddress = "",
            result = String.format("%.2f", result.totalTime),
            timestamp = result.timestamp,
            totalTime = result.totalTime,
            totalDistance = result.totalDistance,
            avgAcceleration = result.avgAcceleration,
            maxAcceleration = result.maxAcceleration,
            dataFilePath = filePath
        )
        testRecordDao.insertTestRecord(entity)

        // 3. 保存分段数据到数据库
        val segmentEntities = result.segments.map { seg ->
            SpeedSegmentEntity(
                testRecordId = result.id,
                startSpeed = seg.startSpeed,
                endSpeed = seg.endSpeed,
                time = seg.time,
                distance = seg.distance
            )
        }
        speedSegmentDao.insertSegments(segmentEntities)
    }

    suspend fun getSegments(testId: String): List<SpeedSegment> {
        return speedSegmentDao.getSegmentsByTestIdSync(testId).map {
            SpeedSegment(it.startSpeed, it.endSpeed, it.time, it.distance)
        }
    }

    suspend fun deleteResult(entity: TestRecordEntity) {
        fileStorage.deleteDataFile(entity.dataFilePath)
        testRecordDao.deleteTestRecord(entity)
    }
}
