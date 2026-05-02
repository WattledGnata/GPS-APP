package com.blazepush.core.data.repository

import android.content.Context
import com.blazepush.core.data.local.dao.CrossingEventDao
import com.blazepush.core.data.local.dao.TelemetrySessionDao
import com.blazepush.core.data.local.entity.CrossingEventEntity
import com.blazepush.core.data.local.entity.TelemetrySessionEntity
import com.blazepush.core.domain.model.TelemetryCrossingEvent
import com.blazepush.core.domain.model.TelemetrySample
import com.blazepush.core.domain.model.TelemetrySessionType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File
import java.nio.file.Files

/**
 * TelemetryRepository JVM 单测（Fake DAO + real BinaryTelemetryWriter + 临时文件）
 *
 * 替代 Robolectric in-memory Room（setup 复杂），验证:
 *   - writeCrossing() 后立即可通过 DAO 查询（5.5 core requirement）
 *   - startSession / endSession 生命周期在 DAO 层的反映
 *   - writeSample() 最终落盘（文件大小验证）
 *
 * @author CC
 * @description telemetry repository unit tests with fake DAO
 * @date 2026-04-30
 */
class TelemetryRepositoryTest {

    private lateinit var tempDir: File
    private lateinit var context: Context
    private lateinit var fakeSessionDao: FakeTelemetrySessionDao
    private lateinit var fakeCrossingDao: FakeCrossingEventDao
    private lateinit var repo: TelemetryRepository

    /**
     * 每个 case 之前重建临时目录 + mock Context + Fake DAO + Repository。
     */
    @Before
    fun setup() {
        tempDir = Files.createTempDirectory("telemetry_repo_test").toFile()
        context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(tempDir)
        fakeSessionDao = FakeTelemetrySessionDao()
        fakeCrossingDao = FakeCrossingEventDao()
        repo = TelemetryRepository(context, fakeSessionDao, fakeCrossingDao)
    }

    /**
     * 清理临时目录（递归）。
     */
    @After
    fun teardown() {
        tempDir.deleteRecursively()
    }

    // 5.5.1 writeCrossing 后立即可查询（核心要求）
    @Test
    fun `writeCrossing - event is immediately queryable via DAO`() = runTest {
        val sessionId = repo.startSession(TelemetrySessionType.LAP_SESSION)

        val event = TelemetryCrossingEvent(
            sessionId = sessionId,
            lapIndex = 0,
            crossingTimestampMs = 1_700_000_001_000L,
            speedKmh = 120.0,
            gateId = "sf-gate",
            gateType = "START_FINISH",
            accepted = true,
            reason = "VALID",
            directionScore = 0.95,
        )
        repo.writeCrossing(event)

        val crossings = fakeCrossingDao.queryBySessionId(sessionId)
        assertEquals(1, crossings.size)
        assertEquals("sf-gate", crossings[0].gateId)
        assertEquals(true, crossings[0].accepted)
        assertEquals(0.95, crossings[0].directionScore)
        assertEquals(0, crossings[0].lapIndex)

        repo.endSession(sessionId)
    }

    // 5.5.2 多次 writeCrossing 全部持久化，顺序正确
    @Test
    fun `writeCrossing multiple times - all persisted in order`() = runTest {
        val sessionId = repo.startSession(TelemetrySessionType.LAP_SESSION)

        repeat(3) { i ->
            repo.writeCrossing(
                TelemetryCrossingEvent(
                    sessionId = sessionId,
                    lapIndex = i,
                    crossingTimestampMs = 1_700_000_000_000L + i * 60_000,
                    speedKmh = 100.0 + i,
                    gateId = "gate-$i",
                    gateType = "START_FINISH",
                    accepted = true,
                    reason = "VALID",
                    directionScore = null,
                )
            )
        }

        val crossings = fakeCrossingDao.queryBySessionId(sessionId)
        assertEquals(3, crossings.size)
        assertEquals("gate-0", crossings[0].gateId)
        assertEquals("gate-2", crossings[2].gateId)

        repo.endSession(sessionId)
    }

    // 5.5.3 startSession 在 DAO 中创建 session entity
    @Test
    fun `startSession - inserts session entity in DAO`() = runTest {
        val sessionId = repo.startSession(TelemetrySessionType.PERFORMANCE_TEST)

        val entity = fakeSessionDao.queryBySessionId(sessionId)
        assertNotNull(entity)
        assertEquals(TelemetrySessionType.PERFORMANCE_TEST.name, requireNotNull(entity).sessionType)

        repo.endSession(sessionId)
    }

    // 5.5.4 endSession 更新 endTs（> startTs）
    @Test
    fun `endSession - updates endTs after startTs`() = runTest {
        val sessionId = repo.startSession(TelemetrySessionType.PERFORMANCE_TEST)
        val startEntity = requireNotNull(fakeSessionDao.queryBySessionId(sessionId))
        val startTs = startEntity.startTs

        repo.endSession(sessionId)

        val endEntity = requireNotNull(fakeSessionDao.queryBySessionId(sessionId))
        assert(endEntity.endTs >= startTs) {
            "endTs (${endEntity.endTs}) should be >= startTs ($startTs)"
        }
    }

    // 5.5.5 writeSample 落盘：写 5 帧后 close，文件大小 = HEADER_SIZE + 5 * SAMPLE_SIZE
    @Test
    fun `writeSample - samples persisted to binary file`() = runTest {
        val sessionId = repo.startSession(TelemetrySessionType.PERFORMANCE_TEST)

        repeat(5) { i ->
            repo.writeSample(
                TelemetrySample(
                    tsDeltaMs = i * 40L,
                    lat = 39.9042,
                    lon = 116.4074,
                    speedKmh = 50.0 + i,
                    bearingDeg = 90.0,
                )
            )
        }
        repo.endSession(sessionId)

        val entity = requireNotNull(fakeSessionDao.queryBySessionId(sessionId))
        val file = File(entity.binaryFilePath)
        val expected = com.blazepush.core.data.local.binary.GpsBinaryFormat.HEADER_SIZE +
                5 * com.blazepush.core.data.local.binary.GpsBinaryFormat.SAMPLE_SIZE
        assertEquals(expected.toLong(), file.length())
    }

    // --- Fake DAO 实现 ---

    private class FakeTelemetrySessionDao : TelemetrySessionDao {
        private val sessions = mutableListOf<TelemetrySessionEntity>()

        override suspend fun insert(entity: TelemetrySessionEntity) {
            sessions.removeIf { it.sessionId == entity.sessionId }
            sessions.add(entity)
        }

        override suspend fun updateEndTs(sessionId: String, endTs: Long) {
            val idx = sessions.indexOfFirst { it.sessionId == sessionId }
            if (idx >= 0) sessions[idx] = sessions[idx].copy(endTs = endTs)
        }

        // persist-session-summary-fields round 加：updateSummary 一次写齐 4 字段
        override suspend fun updateSummary(
            sessionId: String,
            endTs: Long,
            lapCount: Int,
            bestLapMs: Long?,
            topSpeedKmh: Double?,
        ) {
            val idx = sessions.indexOfFirst { it.sessionId == sessionId }
            if (idx >= 0) {
                sessions[idx] = sessions[idx].copy(
                    endTs = endTs,
                    lapCount = lapCount,
                    bestLapMs = bestLapMs,
                    topSpeedKmh = topSpeedKmh,
                )
            }
        }

        override suspend fun queryBySessionId(sessionId: String) =
            sessions.find { it.sessionId == sessionId }

        override suspend fun queryAll() = sessions.toList()

        // round wire-real-data-to-records-and-laps-tabs §1.6：同步新增 abstract 方法
        // 避免 :core:data:testDebugUnitTest 编译失败。本 fake 测试场景不需要 trackId
        // 聚合行为，统一返回轻量空 flow（getBestLapForTrack 行为由 §1.7
        // TelemetryRepositoryTrackQueryTest 用 in-memory Room 覆盖）。
        override fun getBestLapForTrack(trackId: String) =
            kotlinx.coroutines.flow.flowOf<TelemetrySessionEntity?>(null)

        override fun getSessionCountForTrack(trackId: String) =
            kotlinx.coroutines.flow.flowOf(0)

        override fun getTotalLapCountForTrack(trackId: String) =
            kotlinx.coroutines.flow.flowOf(0)

        override fun getRecentSessionsForTrack(trackId: String, limit: Int) =
            kotlinx.coroutines.flow.flowOf<List<TelemetrySessionEntity>>(emptyList())

        // add-history-deletion round：同步 abstract 方法。本套件不消费删除路径，no-op。
        override suspend fun deleteSession(entity: TelemetrySessionEntity) {
            sessions.removeIf { it.sessionId == entity.sessionId }
        }
    }

    private class FakeCrossingEventDao : CrossingEventDao {
        private val crossings = mutableListOf<CrossingEventEntity>()

        override suspend fun insertInTransaction(entity: CrossingEventEntity) {
            crossings.add(entity)
        }

        override suspend fun queryBySessionId(sessionId: String) =
            crossings.filter { it.sessionId == sessionId }

        // add-history-deletion round：同步 abstract 方法。本套件不消费删除路径，no-op。
        override suspend fun deleteCrossingsBySessionId(sessionId: String) {
            crossings.removeIf { it.sessionId == sessionId }
        }
    }
}