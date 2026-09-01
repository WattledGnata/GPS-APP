// @IgnoreFormatCheck
// 理由：本 round wire-real-data-to-records-and-laps-tabs §1.3 追加 3 个统计 Flow 方法 +
//       toSummary() 私有扩展；既有方法 doc 缺失 + getBestResult 的 when sealed 表达式 kt-check
//       未识别 sealed exhaustiveness（语义已覆盖 TestTemplate 全集）为 baseline 历史问题，
//       按 scope-boundary 推到 D round（kt-format-cleanup-pass）批量补齐，本 round 不顺手改。
package com.blazepush.core.data.repository

import android.util.Log
import com.blazepush.core.data.local.dao.SpeedSegmentDao
import com.blazepush.core.data.local.dao.TestRecordDao
import com.blazepush.core.data.local.entity.SpeedSegmentEntity
import com.blazepush.core.data.local.entity.TestRecordEntity
import com.blazepush.core.domain.model.SpeedSegment
import com.blazepush.core.domain.model.TestResult
import com.blazepush.core.domain.model.LapTelemetrySample
import com.blazepush.core.domain.model.PerformanceTelemetry
import com.blazepush.core.domain.model.TestResultSummary
import com.blazepush.core.domain.model.TestTemplate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * 加减速测试记录持久化（A56 后已不再用 JSON dataFilePath，改走 binary file path）。
 *
 * @author CC
 * @description test result Room persistence
 * @date 2026-04-30
 */
class TestResultRepository(
    private val testRecordDao: TestRecordDao,
    private val speedSegmentDao: SpeedSegmentDao,
    private val telemetryRepository: TelemetryRepository,
) {
    val testResultsFlow: Flow<List<TestRecordEntity>> =
        testRecordDao.getAllTestRecordsFlow()

    /**
     * 保存测试结果 + 速度分段；dataFilePath 现指向 binary chunk file。
     */
    suspend fun saveResult(result: TestResult) {
        val entity = result.toRecordEntity()
        testRecordDao.insertTestRecord(entity)

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

    /**
     * 按 testId 拉所有速度分段并转换为 domain model。
     */
    suspend fun getSegments(testId: String): List<SpeedSegment> {
        return speedSegmentDao.getSegmentsByTestIdSync(testId).map {
            SpeedSegment(it.startSpeed, it.endSpeed, it.time, it.distance)
        }
    }

    // round wire-real-data-to-records-and-laps-tabs §1.3：性能测试聚合查询。
    // 返回 TestResultSummary 而非完整 TestResult —— TestRecordEntity 不含 segments
    // / dataPoints，无法无损构造 TestResult；UI 渲染只需轻量 summary。

    fun getBestResult(template: TestTemplate): Flow<TestResultSummary?> = when (template) {
        TestTemplate.Acceleration0To100 -> testRecordDao.getBestAcceleration0To100()
        TestTemplate.Braking100To0 -> testRecordDao.getBestBraking100To0()
    }.map { it?.toSummary() }

    fun getTotalRunCount(): Flow<Int> = testRecordDao.getTotalCount()

    fun getRecentResultsFlow(limit: Int): Flow<List<TestResultSummary>> =
        testRecordDao.getRecentFlow(limit).map { list -> list.map { it.toSummary() } }

    private fun TestRecordEntity.toSummary(): TestResultSummary = TestResultSummary(
        id = id,
        testTemplateId = testTemplateId,
        carModel = carModel,
        timestamp = timestamp,
        totalTime = totalTime,
        totalDistance = totalDistance,
        dataFilePath = dataFilePath,
        window = if (
            windowAlgorithmVersion > 0 &&
            windowStartSampleIndex != null && windowEndSampleIndex != null &&
            windowStartDeltaMs != null && windowEndDeltaMs != null
        ) {
            com.blazepush.core.domain.model.PerformanceResultWindow(
                windowStartSampleIndex,
                windowEndSampleIndex,
                windowStartDeltaMs,
                windowEndDeltaMs,
                windowAlgorithmVersion,
            )
        } else null,
    )

    /**
     * 删除测试记录，cascade 清理 telemetry_sessions 同 sessionId 行 + binary 文件
     * （cleanup-perftest-telemetry-session-orphan round：PERFORMANCE 删除与 LAPS 同标准）。
     *
     * 顺序（design Decision 3，与 LAPS cascade 一致：关联先删 / db 后 fs）：
     * 1. test_records 主表行
     * 2. telemetry_sessions cascade（复用 [TelemetryRepository.deleteSession]：null-safe，
     *    crossing/视频步骤对 PERFORMANCE 行自然 no-op，binary 经 binaryFilePath 删除）
     * 3. 原 dataFilePath 白名单删除保留为兜底（非 UUID 旧记录 / telemetry_sessions 无行时
     *    仍能删文件；与步骤 2 双删同一文件时第二次 delete() 返回 false 无害）
     */
    suspend fun deleteResult(entity: TestRecordEntity) {
        testRecordDao.deleteTestRecord(entity)
        val sessionId = extractSessionIdFromDataFilePath(entity.dataFilePath)
        if (sessionId != null) {
            Log.d(TAG_CASCADE, "deleteResult cascade -> deleteSession($sessionId)")
            telemetryRepository.deleteSession(sessionId)
        } else {
            Log.d(TAG_CASCADE, "deleteResult skip cascade: non-UUID basename in '${entity.dataFilePath}'")
        }
        if (entity.dataFilePath.isNotEmpty()) {
            val file = java.io.File(entity.dataFilePath)
            // 安全边界：只删除 app telemetry 目录内的文件，防止路径穿越
            if (file.canonicalPath.contains("/telemetry/")) {
                file.delete()
            }
        }
    }

    /**
     * 从 dataFilePath basename 提取 sessionId（design Decision 2）。
     * A56 写入端 invariant：PERFORMANCE 路径 test_records.dataFilePath 的 basename（去 .bin）
     * 与 telemetry_sessions.sessionId 同 UUID 派生。UUID regex 验证防御非 UUID 命名的
     * 旧 binary path 误匹配（向后兼容）；空 path / 非 UUID 返回 null。
     */
    private fun extractSessionIdFromDataFilePath(path: String): String? {
        if (path.isEmpty()) return null
        val basename = java.io.File(path).nameWithoutExtension
        return if (UUID_REGEX.matches(basename)) basename else null
    }

    /**
     * 按 id 删除测试记录：先 query 再复用 [deleteResult] 的 cascade（含 /telemetry/ 白名单防御）。
     * 不存在的 id 视为 no-op。
     *
     * 引入背景：UI / ViewModel 层只持有 recordId 字符串，不注入 DAO；
     * 把 by-id 查询封装进 repository 避免 DAO 边界泄漏到 feature 层（add-history-deletion round）。
     *
     * @author CC
     * @description by-id wrapper for delete cascade, used by ViewModel
     * @date 2026-05-02
     */
    suspend fun deleteResultById(id: String) {
        val entity = testRecordDao.getTestRecordById(id) ?: return
        deleteResult(entity)
    }

    /**
     * PERFORMANCE_TEST 完整 dataPoints 切片读取。
     * testId 不存在 → null；dataFilePath="" → null；binary 缺失/异常 → null（不抛异常）。
     *
     * @author CC
     * @description performance test complete data-points reader
     * @date 2026-05-04
     */
    suspend fun getDataPointsForResult(testId: String): PerformanceTelemetry? {
        val entity = testRecordDao.getTestRecordById(testId) ?: return null
        if (entity.dataFilePath.isEmpty()) return null
        // sentinel guard：entity.timestamp 是 GPS 协议时间（RaceChronoParser 未同步时写 Long.MIN_VALUE）。
        // 若为 sentinel，absoluteTsMs = testStartWallClock + tsDeltaMs ≈ Long.MIN_VALUE，chart 时间轴崩塌。
        // writer 侧 trigger guard（satellites>=6 + hdop<2.0）已阻断 sentinel 进 binary；本行是 reader 侧
        // defensive 第二道防线（防 future trigger guard 回归），MUST 在 readPerformanceSamples 调用之前。
        // 详 spec lap-telemetry-readers Requirement 2 invariant 三条款 / unify-perftest-anchor-cross-clock round。
        if (entity.timestamp == Long.MIN_VALUE) return null
        val rawSamples = withContext(Dispatchers.IO) {
            runCatching { telemetryRepository.readPerformanceSamples(entity.dataFilePath) }
                .getOrDefault(emptyList())
        }
        if (rawSamples.isEmpty()) return null
        val testStartWallClock = entity.timestamp
        val testEndWallClock = entity.timestamp + (rawSamples.lastOrNull()?.tsDeltaMs ?: 0L)
        val samples = rawSamples.map { sample ->
            LapTelemetrySample(
                absoluteTsMs = testStartWallClock + sample.tsDeltaMs,
                elapsedMsInLap = sample.tsDeltaMs,
                lat = sample.lat,
                lon = sample.lon,
                speedKmh = sample.speedKmh,
                bearingDeg = sample.bearingDeg,
                accelerationG = null,
                flags = sample.flags,
            )
        }
        return PerformanceTelemetry(
            testId = testId,
            testStartWallClock = testStartWallClock,
            testEndWallClock = testEndWallClock,
            samples = samples,
        )
    }

    private companion object {
        // core/data 内用 android.util.Log（FileLogger 在 feature/test，依赖方向不可达，
        // 对齐 TelemetryRepository.deleteSessionVideo 既有惯例）；
        // 落盘锚点在 BlazePushApplication sweep 调用处（design Decision 5）。
        private const val TAG_CASCADE = "PerftestCascade"
        private val UUID_REGEX =
            Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}

internal fun TestResult.toRecordEntity(): TestRecordEntity = TestRecordEntity(
    id = id,
    testTemplateId = template.id,
    testType = template.name,
    carModel = carModel,
    deviceName = deviceSnapshot.displayName,
    deviceAddress = deviceSnapshot.address,
    result = String.format("%.2f", totalTime),
    timestamp = timestamp,
    totalTime = totalTime,
    totalDistance = totalDistance,
    avgAcceleration = avgAcceleration,
    maxAcceleration = maxAcceleration,
    maxDeceleration = maxDeceleration,
    dataFilePath = dataFilePath,
    windowStartSampleIndex = window?.startSampleIndex,
    windowEndSampleIndex = window?.endSampleIndex,
    windowStartDeltaMs = window?.startDeltaMs,
    windowEndDeltaMs = window?.endDeltaMs,
    windowAlgorithmVersion = window?.algorithmVersion ?: 0,
)
