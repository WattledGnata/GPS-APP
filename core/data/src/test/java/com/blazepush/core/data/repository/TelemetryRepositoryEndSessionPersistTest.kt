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
 * 6. endSession lapCount 用 crossingWallClockTimestampMs 排序配对（跨时钟域分歧时 MUST 用 wallClock 而非
 *    GPS 协议时钟；含 null wallClock 的相邻对不计有效圈）—— unify-lap-count-pairing-semantics round
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
        // unify-lap-count-pairing-semantics round：endSession 配对键改 crossingWallClockTimestampMs。
        // 既有 case MUST 补 wallClock 字段（与 crossingTimestampMs 同序同值），否则改 key 后
        // wallClock 全 null → lapCount 退化为 0 假绿（R6）。
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
                    crossingWallClockTimestampMs = ts,
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
                crossingWallClockTimestampMs = 2700L,
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

        // crossings wallClock=1000/2200/3300/4400 → durations [1200, 1100, 1100] → min = 1100
        // 既有 case 补 wallClock 字段（同 crossingTimestampMs 序值），防 R6 假绿。
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
                    crossingWallClockTimestampMs = ts,
                )
            )
        }
        repo.endSession(sessionId)

        val entity = requireNotNull(fakeSessionDao.queryBySessionId(sessionId))
        assertEquals(1100L, requireNotNull(entity.bestLapMs))
    }

    @Test
    fun `endSession lapCount uses wallClock ordering not gps clock`() = runTest {
        val sessionId = repo.startSession(
            type = TelemetrySessionType.LAP_SESSION,
            trackId = "test-track",
            trackNameSnapshot = "Test Track",
        )

        // 跨时钟域分歧：GPS 协议时钟序 c1<c2<c3，但 wallClock 序 c2<c3<c1。
        // 若误用 crossingTimestampMs 排序 → durations 来自 (c1,c2),(c2,c3)；
        // 正确用 wallClock 排序 → durations 来自 (c2,c3),(c3,c1)，min 不同 → 锁死 MUST 用 wallClock。
        // wallClock 排序后：c2=1700000000100, c3=1700000000200, c1=1700000000300。
        //   durations = [c3-c2=100, c1-c3=100]，min=100。
        // 若误用 GPS 序（c1=100, c2=200, c3=300，按 crossingTimestampMs 排序后 wallClock 仍各自取）：
        //   durations = [c2.wall-c1.wall = 1700000000100-1700000000300 = -200,
        //                c3.wall-c2.wall = 1700000000200-1700000000100 = 100]，min=-200 ≠ 100。
        data class C(val gpsTs: Long, val wallTs: Long)
        listOf(
            C(gpsTs = 100L, wallTs = 1700000000300L), // c1
            C(gpsTs = 200L, wallTs = 1700000000100L), // c2
            C(gpsTs = 300L, wallTs = 1700000000200L), // c3
        ).forEachIndexed { i, c ->
            repo.writeCrossing(
                TelemetryCrossingEvent(
                    sessionId = sessionId,
                    lapIndex = i,
                    crossingTimestampMs = c.gpsTs,
                    speedKmh = 100.0,
                    gateId = "sf",
                    gateType = "StartFinish",
                    accepted = true,
                    reason = "Accepted",
                    directionScore = null,
                    crossingWallClockTimestampMs = c.wallTs,
                )
            )
        }
        repo.endSession(sessionId)

        val entity = requireNotNull(fakeSessionDao.queryBySessionId(sessionId))
        // wallClock 排序 (c2,c3,c1) → durations [100, 100] → lapCount=2, bestLapMs=100。
        assertEquals(2, entity.lapCount)
        assertEquals(100L, requireNotNull(entity.bestLapMs))
    }

    @Test
    fun `endSession null wallClock pairs not counted`() = runTest {
        val sessionId = repo.startSession(
            type = TelemetrySessionType.LAP_SESSION,
            trackId = "test-track",
            trackNameSnapshot = "Test Track",
        )

        // 5 个 accepted SF：前 2 个 wallClock=null（旧 row），后 3 个非空 5000/6100/7200。
        // wallClock 排序后非空 3 个在前、null 2 个排末尾（?: Long.MAX_VALUE）。
        // 有效相邻对：(5000,6100),(6100,7200) = 2 对；含 null 端的相邻对不计 → lapCount=2。
        val wallClocks = listOf<Long?>(null, null, 5000L, 6100L, 7200L)
        wallClocks.forEachIndexed { i, wc ->
            repo.writeCrossing(
                TelemetryCrossingEvent(
                    sessionId = sessionId,
                    lapIndex = i,
                    crossingTimestampMs = (i + 1) * 1000L,
                    speedKmh = 100.0,
                    gateId = "sf",
                    gateType = "StartFinish",
                    accepted = true,
                    reason = "Accepted",
                    directionScore = null,
                    crossingWallClockTimestampMs = wc,
                )
            )
        }
        repo.endSession(sessionId)

        val entity = requireNotNull(fakeSessionDao.queryBySessionId(sessionId))
        assertEquals(2, entity.lapCount)
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

        // session-video-metadata-persist round：同步 abstract 方法。本套件不消费视频路径，no-op。
        override suspend fun clearVideo(sessionId: String) {}
        override suspend fun updateVideoMetadata(sessionId: String, videoFilePath: String, videoStartedAtWallClock: Long) {
        }

        // add-history-deletion round：同步 abstract 方法。本套件不消费删除路径，no-op。
        override suspend fun deletePerftestOrphans(): Int = 0

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