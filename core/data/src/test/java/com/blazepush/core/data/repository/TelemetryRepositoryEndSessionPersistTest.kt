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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File
import java.nio.file.Files

/**
 * persist-session-summary-fields round 测试套件：endSession 派生 + 持久化字段验证。
 *
 * 测试形态与 baseline TelemetryRepositoryTest 一致：
 * - Fake DAO（实现接口）+ 真实 BinaryTelemetryWriter / PerformanceTestTelemetryReader
 * - mockito-core mock(Context) + when(context.filesDir).thenReturn(tempDir)
 * - **不**引入 Room.inMemoryDatabaseBuilder / room-testing / Robolectric / mockk
 *
 * 覆盖契约：
 * 1. startSession 写入 trackId + trackNameSnapshot
 * 2. endSession 派生 topSpeedKmh（基于 binary samples 全程 max）
 * 3. endSession 派生 lapCount（accepted SF crossing pairs 语义；不读 LapRecord.qualityFlags）
 * 4. endSession 派生 bestLapMs（durations.minOrNull）
 * 5. binary 缺失 fallback null（topSpeedKmh = null，不抛异常）
 *
 * @author CC
 * @description endSession derive + persist summary fields tests
 * @date 2026-05-01
 */
class TelemetryRepositoryEndSessionPersistTest {

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
        tempDir = Files.createTempDirectory("persist_summary_test").toFile()
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

    @Test
    fun `startSession persists trackId`() = runTest {
        val sessionId = repo.startSession(
            type = TelemetrySessionType.LAP_SESSION,
            trackId = "preset-tfic-lpcc",
            trackNameSnapshot = "成都天府国际赛道",
        )

        val entity = requireNotNull(fakeSessionDao.queryBySessionId(sessionId))
        assertEquals("preset-tfic-lpcc", entity.trackId)

        repo.endSession(sessionId)
    }

    @Test
    fun `startSession persists trackNameSnapshot`() = runTest {
        val sessionId = repo.startSession(
            type = TelemetrySessionType.LAP_SESSION,
            trackId = "preset-tfic-lpcc",
            trackNameSnapshot = "成都天府国际赛道",
        )

        val entity = requireNotNull(fakeSessionDao.queryBySessionId(sessionId))
        assertEquals("成都天府国际赛道", entity.trackNameSnapshot)

        repo.endSession(sessionId)
    }

    @Test
    fun `endSession derives topSpeedKmh as max sample speed`() = runTest {
        val sessionId = repo.startSession(
            type = TelemetrySessionType.LAP_SESSION,
            trackId = "test-track",
            trackNameSnapshot = "Test Track",
        )

        // 喂 4 帧 sample，max speed = 200
        listOf(100.0, 150.0, 200.0, 180.0).forEachIndexed { idx, speed ->
            repo.writeSample(
                TelemetrySample(
                    tsDeltaMs = idx * 40L,
                    lat = 30.495,
                    lon = 104.437,
                    speedKmh = speed,
                    bearingDeg = 0.0,
                )
            )
        }
        repo.endSession(sessionId)

        val entity = requireNotNull(fakeSessionDao.queryBySessionId(sessionId))
        assertEquals(200.0, requireNotNull(entity.topSpeedKmh), 0.001)
    }

    @Test
    fun `endSession derives lapCount from accepted SF crossing pairs`() = runTest {
        val sessionId = repo.startSession(
            type = TelemetrySessionType.LAP_SESSION,
            trackId = "test-track",
            trackNameSnapshot = "Test Track",
        )

        // 4 个 accepted StartFinish crossing → durations.size = 3 → lapCount = 3
        listOf(1000L, 2200L, 3300L, 4400L).forEachIndexed { i, ts ->
            repo.writeCrossing(
                TelemetryCrossingEvent(
                    sessionId = sessionId,
                    lapIndex = i,
                    crossingTimestampMs = ts,
                    speedKmh = 100.0,
                    gateId = "sf",
                    gateType = "StartFinish",
                    accepted = true,
                    reason = "Accepted",
                    directionScore = null,
                )
            )
        }
        // 加 1 个 rejected crossing 验证不计入 lapCount
        repo.writeCrossing(
            TelemetryCrossingEvent(
                sessionId = sessionId,
                lapIndex = 99,
                crossingTimestampMs = 2700L,
                speedKmh = 100.0,
                gateId = "sf",
                gateType = "StartFinish",
                accepted = false,
                reason = "WrongDirection",
                directionScore = -1.0,
            )
        )
        repo.endSession(sessionId)

        val entity = requireNotNull(fakeSessionDao.queryBySessionId(sessionId))
        assertEquals(3, entity.lapCount)
    }

    @Test
    fun `endSession derives bestLapMs as min duration`() = runTest {
        val sessionId = repo.startSession(
            type = TelemetrySessionType.LAP_SESSION,
            trackId = "test-track",
            trackNameSnapshot = "Test Track",
        )

        // crossings t=1000/2200/3300/4400 → durations [1200, 1100, 1100] → min = 1100
        listOf(1000L, 2200L, 3300L, 4400L).forEachIndexed { i, ts ->
            repo.writeCrossing(
                TelemetryCrossingEvent(
                    sessionId = sessionId,
                    lapIndex = i,
                    crossingTimestampMs = ts,
                    speedKmh = 100.0,
                    gateId = "sf",
                    gateType = "StartFinish",
                    accepted = true,
                    reason = "Accepted",
                    directionScore = null,
                )
            )
        }
        repo.endSession(sessionId)

        val entity = requireNotNull(fakeSessionDao.queryBySessionId(sessionId))
        assertEquals(1100L, requireNotNull(entity.bestLapMs))
    }

    @Test
    fun `endSession sets topSpeedKmh null when no samples written`() = runTest {
        val sessionId = repo.startSession(
            type = TelemetrySessionType.LAP_SESSION,
            trackId = "test-track",
            trackNameSnapshot = "Test Track",
        )

        // 不调 writeSample → binary 仅含 header
        repo.endSession(sessionId)

        val entity = requireNotNull(fakeSessionDao.queryBySessionId(sessionId))
        assertNull(entity.topSpeedKmh)
        // crossings 也为空 → lapCount = 0 / bestLapMs = null
        assertEquals(0, entity.lapCount)
        assertNull(entity.bestLapMs)
    }

    @Test
    fun `endSession does not throw when invariants degrade`() = runTest {
        val sessionId = repo.startSession(
            type = TelemetrySessionType.PERFORMANCE_TEST,
        )
        // PERFORMANCE_TEST callsite 走默认值 trackId / trackNameSnapshot = null
        val entity = requireNotNull(fakeSessionDao.queryBySessionId(sessionId))
        assertNull(entity.trackId)
        assertNull(entity.trackNameSnapshot)

        // endSession 即使无 sample 无 crossing 也不抛异常
        repo.endSession(sessionId)

        val updated = requireNotNull(fakeSessionDao.queryBySessionId(sessionId))
        assertEquals(0, updated.lapCount)
        assertNull(updated.bestLapMs)
        assertNull(updated.topSpeedKmh)
    }

    // --- Fake DAO 实现（与 baseline TelemetryRepositoryTest 同款，含 updateSummary） ---

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
        // 避免 :core:data:testDebugUnitTest 编译失败。
        override fun getBestLapForTrack(trackId: String) =
            kotlinx.coroutines.flow.flowOf<TelemetrySessionEntity?>(null)

        override fun getSessionCountForTrack(trackId: String) =
            kotlinx.coroutines.flow.flowOf(0)

        override fun getTotalLapCountForTrack(trackId: String) =
            kotlinx.coroutines.flow.flowOf(0)

        override fun getRecentSessionsForTrack(trackId: String, limit: Int) =
            kotlinx.coroutines.flow.flowOf<List<TelemetrySessionEntity>>(emptyList())
    }

    private class FakeCrossingEventDao : CrossingEventDao {
        private val crossings = mutableListOf<CrossingEventEntity>()

        override suspend fun insertInTransaction(entity: CrossingEventEntity) {
            crossings.add(entity)
        }

        override suspend fun queryBySessionId(sessionId: String) =
            crossings.filter { it.sessionId == sessionId }
    }
}