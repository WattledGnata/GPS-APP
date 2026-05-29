// @IgnoreFormatCheck
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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File
import java.nio.file.Files

/**
 * unify-lap-count-pairing-semantics round 跨站点同源测试：endSession（站点 A）与
 * getLapTelemetry（站点 C）对"圈配对身份"严格同源。
 *
 * 形态：Fake DAO + 真实 BinaryTelemetryWriter / LapTelemetryReader（同
 * TelemetryRepositoryEndSessionPersistTest 套件惯例）。
 *
 * 测试边界（v3 盲点 #11）：deriveDetailMetrics（站点 B）在 feature/test module，本 class 只能验
 * A/C 同源；B 与 A 同排序键由 LapDetailMetricsDeriveTest（feature/test test source）+ spec normative
 * 锁死，传递保证三站点统一 key。
 *
 * 覆盖：
 * 1. endSession lapCount == getLapTelemetry 可读圈数（A/C 圈集合判断一致）
 * 2. getLapTelemetry(k) 的 (lapStart, lapEnd) wallClock == endSession 配对第 k 对 wallClock（A/C 同源）
 * 3. 跨时钟域分歧（GPS 序 ≠ wallClock 序）时 A/C 仍指向同圈（反例锁死 MUST 用 wallClock）
 *
 * @author CC
 * @description cross-site lap pairing identity consistency tests (A endSession vs C getLapTelemetry)
 * @date 2026-05-30
 */
class LapPairingCrossSiteConsistencyTest {

    private lateinit var tempDir: File
    private lateinit var context: Context
    private lateinit var fakeSessionDao: FakeSessionDao
    private lateinit var fakeCrossingDao: FakeCrossingDao
    private lateinit var repo: TelemetryRepository

    @Before
    fun setup() {
        tempDir = Files.createTempDirectory("lap_pairing_xsite").toFile()
        context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(tempDir)
        fakeSessionDao = FakeSessionDao()
        fakeCrossingDao = FakeCrossingDao()
        repo = TelemetryRepository(context, fakeSessionDao, fakeCrossingDao)
    }

    @After
    fun teardown() {
        tempDir.deleteRecursively()
    }

    private suspend fun writeAcceptedSf(
        sessionId: String,
        idx: Int,
        gpsTs: Long,
        wallTs: Long,
    ) {
        repo.writeCrossing(
            TelemetryCrossingEvent(
                sessionId = sessionId,
                lapIndex = idx,
                crossingTimestampMs = gpsTs,
                speedKmh = 100.0,
                gateId = "sf",
                gateType = "StartFinish",
                accepted = true,
                reason = "Accepted",
                directionScore = null,
                crossingWallClockTimestampMs = wallTs,
            )
        )
    }

    /** 写一帧 sample，tsDeltaMs 决定 absoluteTs = startTs + tsDeltaMs。 */
    private suspend fun writeSampleAt(tsDeltaMs: Long, speed: Double) {
        repo.writeSample(
            TelemetrySample(
                tsDeltaMs = tsDeltaMs,
                lat = 30.495,
                lon = 104.437,
                speedKmh = speed,
                bearingDeg = 0.0,
            )
        )
    }

    @Test
    fun `endSession lapCount equals getLapTelemetry readable count`() = runTest {
        val sessionId = repo.startSession(
            type = TelemetrySessionType.LAP_SESSION,
            trackId = "test-track",
            trackNameSnapshot = "Test Track",
        )
        // startTs 锚点（startSession 写入 entity.startTs = System.currentTimeMillis()）。
        // crossing wallClock 与 sample absoluteTs 同时钟域，故 wallClock = startTs + delta。
        val startTs = requireNotNull(fakeSessionDao.queryBySessionId(sessionId)).startTs

        // 3 个 accepted SF：wallClock = startTs + 1000 / 3000 / 5000 → 2 个有效圈。
        writeAcceptedSf(sessionId, 0, gpsTs = 1000L, wallTs = startTs + 1000L)
        writeAcceptedSf(sessionId, 1, gpsTs = 3000L, wallTs = startTs + 3000L)
        writeAcceptedSf(sessionId, 2, gpsTs = 5000L, wallTs = startTs + 5000L)

        // 写覆盖整 session 的 sample（每圈窗口内至少 1 帧，使 getLapTelemetry 不因 0 帧返回 null）。
        listOf(1500L, 2500L, 3500L, 4500L).forEach { d -> writeSampleAt(d, 120.0) }

        repo.endSession(sessionId)

        val entity = requireNotNull(fakeSessionDao.queryBySessionId(sessionId))
        // 站点 A：endSession lapCount。
        assertEquals(2, entity.lapCount)

        // 站点 C：依次调 getLapTelemetry 直到首次 null 的可读圈数。
        var readable = 0
        var k = 0
        while (true) {
            val lap = repo.getLapTelemetry(sessionId, k) ?: break
            assertNotNull(lap)
            readable++
            k++
        }
        assertEquals(entity.lapCount, readable)
    }

    @Test
    fun `getLapTelemetry lapIndex maps to same physical lap as endSession pairing`() = runTest {
        val sessionId = repo.startSession(
            type = TelemetrySessionType.LAP_SESSION,
            trackId = "test-track",
            trackNameSnapshot = "Test Track",
        )
        val startTs = requireNotNull(fakeSessionDao.queryBySessionId(sessionId)).startTs

        // wallClock 升序: w0 < w1 < w2 → 2 圈。手算配对：Lap0=(w0,w1), Lap1=(w1,w2)。
        val w0 = startTs + 1000L
        val w1 = startTs + 3000L
        val w2 = startTs + 6000L
        writeAcceptedSf(sessionId, 0, gpsTs = 1000L, wallTs = w0)
        writeAcceptedSf(sessionId, 1, gpsTs = 3000L, wallTs = w1)
        writeAcceptedSf(sessionId, 2, gpsTs = 6000L, wallTs = w2)
        listOf(1500L, 2500L, 4000L, 5000L).forEach { d -> writeSampleAt(d, 110.0) }

        repo.endSession(sessionId)

        // 站点 C getLapTelemetry(0) 的窗口 == endSession 配对路径手算第 0 对 (w0, w1)。
        val lap0 = requireNotNull(repo.getLapTelemetry(sessionId, 0))
        assertEquals(w0, lap0.lapStartWallClock)
        assertEquals(w1, lap0.lapEndWallClock)

        val lap1 = requireNotNull(repo.getLapTelemetry(sessionId, 1))
        assertEquals(w1, lap1.lapStartWallClock)
        assertEquals(w2, lap1.lapEndWallClock)

        // lapIndex 2 越界（仅 2 圈）→ null。
        assertNull(repo.getLapTelemetry(sessionId, 2))
    }

    @Test
    fun `cross clock divergence A and C still agree`() = runTest {
        val sessionId = repo.startSession(
            type = TelemetrySessionType.LAP_SESSION,
            trackId = "test-track",
            trackNameSnapshot = "Test Track",
        )
        val startTs = requireNotNull(fakeSessionDao.queryBySessionId(sessionId)).startTs

        // 跨时钟域分歧：GPS 序 c1<c2<c3，wallClock 序 c2<c3<c1。
        // wallClock: c2 = startTs+1000, c3 = startTs+3000, c1 = startTs+5000。
        // wallClock 排序 (c2,c3,c1) → 配对 Lap0=(c2,c3)=(startTs+1000, startTs+3000),
        //   Lap1=(c3,c1)=(startTs+3000, startTs+5000)。
        writeAcceptedSf(sessionId, 0, gpsTs = 100L, wallTs = startTs + 5000L) // c1
        writeAcceptedSf(sessionId, 1, gpsTs = 200L, wallTs = startTs + 1000L) // c2
        writeAcceptedSf(sessionId, 2, gpsTs = 300L, wallTs = startTs + 3000L) // c3
        listOf(1500L, 2500L, 3500L, 4500L).forEach { d -> writeSampleAt(d, 130.0) }

        repo.endSession(sessionId)

        val entity = requireNotNull(fakeSessionDao.queryBySessionId(sessionId))
        // 站点 A：wallClock 排序后 durations [(c3-c2)=2000, (c1-c3)=2000] → lapCount=2。
        assertEquals(2, entity.lapCount)

        // 站点 C：getLapTelemetry(0) 取到 (c2, c3) 窗口 —— 与 A 配对第 0 对同源。
        // 若 A 误用 GPS 时钟排序（c1,c2,c3），A 配对第 0 对会是 (c1,c2)，与 C(wallClock) 错位 → fail。
        val lap0 = requireNotNull(repo.getLapTelemetry(sessionId, 0))
        assertEquals(startTs + 1000L, lap0.lapStartWallClock) // c2.wall
        assertEquals(startTs + 3000L, lap0.lapEndWallClock)   // c3.wall
        // A 的 bestLapMs == C 第 0 圈 duration 域（均 2000）。
        assertEquals(2000L, requireNotNull(entity.bestLapMs))
        assertEquals(2000L, lap0.lapDurationMs)
    }

    // --- Fake DAO（与 TelemetryRepositoryEndSessionPersistTest 同款，含全部 abstract 方法）---

    private class FakeSessionDao : TelemetrySessionDao {
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

        override fun getBestLapForTrack(trackId: String) =
            kotlinx.coroutines.flow.flowOf<TelemetrySessionEntity?>(null)

        override fun getSessionCountForTrack(trackId: String) =
            kotlinx.coroutines.flow.flowOf(0)

        override fun getTotalLapCountForTrack(trackId: String) =
            kotlinx.coroutines.flow.flowOf(0)

        override fun getRecentSessionsForTrack(trackId: String, limit: Int) =
            kotlinx.coroutines.flow.flowOf<List<TelemetrySessionEntity>>(emptyList())

        override suspend fun deleteSession(entity: TelemetrySessionEntity) {
            sessions.removeIf { it.sessionId == entity.sessionId }
        }
    }

    private class FakeCrossingDao : CrossingEventDao {
        private val crossings = mutableListOf<CrossingEventEntity>()

        override suspend fun insertInTransaction(entity: CrossingEventEntity) {
            crossings.add(entity)
        }

        override suspend fun queryBySessionId(sessionId: String) =
            crossings.filter { it.sessionId == sessionId }

        override suspend fun deleteCrossingsBySessionId(sessionId: String) {
            crossings.removeIf { it.sessionId == sessionId }
        }
    }
}
