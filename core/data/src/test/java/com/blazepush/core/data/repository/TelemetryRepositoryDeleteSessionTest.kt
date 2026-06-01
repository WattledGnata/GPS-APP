package com.blazepush.core.data.repository

import android.content.Context
import com.blazepush.core.data.local.dao.CrossingEventDao
import com.blazepush.core.data.local.dao.TelemetrySessionDao
import com.blazepush.core.data.local.entity.CrossingEventEntity
import com.blazepush.core.data.local.entity.TelemetrySessionEntity
import com.blazepush.core.domain.model.TelemetryCrossingEvent
import com.blazepush.core.domain.model.TelemetrySessionType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File
import java.nio.file.Files

/**
 * TelemetryRepository.deleteSession cascade 单测（add-history-deletion round）。
 *
 * 覆盖 spec 3 个 scenario：
 *   1. 普通 cascade（session entity + crossing_events + binary 文件全清）
 *   2. 不存在 sessionId 的 no-op 行为
 *   3. binary 路径不在 /telemetry/ 下时不删文件（路径白名单防穿越）
 *
 * @author CC
 * @description cascade delete session unit tests
 * @date 2026-05-02
 */
class TelemetryRepositoryDeleteSessionTest {

    private lateinit var tempDir: File
    private lateinit var context: Context
    private lateinit var fakeSessionDao: FakeTelemetrySessionDao
    private lateinit var fakeCrossingDao: FakeCrossingEventDao
    private lateinit var repo: TelemetryRepository

    @Before
    fun setup() {
        tempDir = Files.createTempDirectory("delete_session_test").toFile()
        context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(tempDir)
        fakeSessionDao = FakeTelemetrySessionDao()
        fakeCrossingDao = FakeCrossingEventDao()
        repo = TelemetryRepository(context, fakeSessionDao, fakeCrossingDao)
    }

    @After
    fun teardown() {
        tempDir.deleteRecursively()
    }

    /**
     * Scenario 1：普通 cascade —— session + 5 个 crossing + binary 文件，deleteSession 后全部清掉。
     */
    @Test
    fun `deleteSession - cascades crossings and binary file`() = runTest {
        val sessionId = repo.startSession(TelemetrySessionType.LAP_SESSION)

        repeat(5) { i ->
            repo.writeCrossing(
                TelemetryCrossingEvent(
                    sessionId = sessionId,
                    lapIndex = i,
                    crossingTimestampMs = 1_700_000_000_000L + i * 1000,
                    speedKmh = 120.0 + i,
                    gateId = "sf-gate",
                    gateType = "START_FINISH",
                    accepted = true,
                    reason = "VALID",
                    directionScore = 0.95,
                )
            )
        }
        repo.endSession(sessionId)

        val entityBefore = fakeSessionDao.queryBySessionId(sessionId)
        assertNotNull("session entity should exist before delete", entityBefore)
        assertEquals(5, fakeCrossingDao.queryBySessionId(sessionId).size)
        val binaryFile = File(entityBefore!!.binaryFilePath)
        assertTrue("binary file should exist before delete", binaryFile.exists())
        assertTrue(
            "binary file path must be inside /telemetry/ for whitelist guard to apply",
            binaryFile.canonicalPath.contains("/telemetry/"),
        )

        repo.deleteSession(sessionId)

        assertNull("session entity gone", fakeSessionDao.queryBySessionId(sessionId))
        assertEquals(0, fakeCrossingDao.queryBySessionId(sessionId).size)
        assertFalse("binary file should be deleted", binaryFile.exists())
    }

    /**
     * Scenario 2：不存在的 sessionId 视为 no-op，不抛 + 不影响其它行。
     */
    @Test
    fun `deleteSession - non-existent sessionId is silent no-op`() = runTest {
        // 先插一条无关 session 占位
        val keepSessionId = repo.startSession(TelemetrySessionType.LAP_SESSION)
        repo.writeCrossing(
            TelemetryCrossingEvent(
                sessionId = keepSessionId,
                lapIndex = 0,
                crossingTimestampMs = 1_700_000_000_000L,
                speedKmh = 100.0,
                gateId = "g",
                gateType = "START_FINISH",
                accepted = true,
                reason = "VALID",
                directionScore = 0.9,
            )
        )
        repo.endSession(keepSessionId)

        // 删一个不存在的 sessionId
        repo.deleteSession("does-not-exist")

        // 占位 session 仍在
        assertNotNull(fakeSessionDao.queryBySessionId(keepSessionId))
        assertEquals(1, fakeCrossingDao.queryBySessionId(keepSessionId).size)
    }

    /**
     * Scenario 3：binary 路径不在 /telemetry/ 下 → db 行删除但文件**不**被删（白名单防御）。
     *
     * 手动构造 entity（binaryFilePath 指向 tempDir/other/x.bin），插入 fake DAO；
     * 创建该文件 → deleteSession(id) → 验证 db 行清掉但文件仍在。
     */
    @Test
    fun `deleteSession - binary path outside telemetry whitelist is preserved`() = runTest {
        val sessionId = "evil-path-session"
        val otherDir = File(tempDir, "other").apply { mkdirs() }
        val outsideFile = File(otherDir, "$sessionId.bin").apply {
            writeText("must not be deleted")
        }
        assertTrue(outsideFile.exists())
        assertFalse(
            "test prerequisite: file path must NOT contain /telemetry/",
            outsideFile.canonicalPath.contains("/telemetry/"),
        )

        val entity = TelemetrySessionEntity(
            sessionId = sessionId,
            sessionType = TelemetrySessionType.LAP_SESSION.name,
            startTs = 1_700_000_000_000L,
            endTs = 1_700_000_001_000L,
            binaryFilePath = outsideFile.absolutePath,
            lapCount = 0,
            bestLapMs = null,
            topSpeedKmh = null,
            trackId = null,
            trackNameSnapshot = null,
        )
        fakeSessionDao.insert(entity)
        // 同 session 的 crossing 也插一条，验证 cascade 仍清 db 行
        fakeCrossingDao.insertInTransaction(
            CrossingEventEntity(
                sessionId = sessionId,
                lapIndex = 0,
                crossingTimestampMs = entity.startTs,
                speedKmh = 80.0,
                gateId = "g",
                gateType = "START_FINISH",
                accepted = true,
                reason = "VALID",
                directionScore = 0.9,
            )
        )

        repo.deleteSession(sessionId)

        assertNull("db row should be deleted regardless of file outcome",
            fakeSessionDao.queryBySessionId(sessionId))
        assertEquals(0, fakeCrossingDao.queryBySessionId(sessionId).size)
        assertTrue(
            "file outside /telemetry/ whitelist must be preserved (path-traversal guard)",
            outsideFile.exists(),
        )
    }

    // --- video-storage-cleanup round A 测试 ---

    /** 成绩页单删视频：删文件 + 置空字段，session 行保留（spec 手动删 Requirement）。 */
    @Test
    fun `deleteSessionVideo - removes file and clears fields but keeps session`() = runTest {
        val sessionId = repo.startSession(TelemetrySessionType.LAP_SESSION)
        val videoFile = File(File(tempDir, "video").apply { mkdirs() }, "v.mp4").apply { writeText("x") }
        repo.attachVideoToSession(sessionId, videoFile.absolutePath, 123L)
        assertTrue(videoFile.exists())

        repo.deleteSessionVideo(sessionId)

        assertFalse("video file deleted", videoFile.exists())
        val entity = fakeSessionDao.queryBySessionId(sessionId)
        assertNotNull("session row kept", entity)
        assertNull("videoFilePath cleared", entity?.videoFilePath)
    }

    /** 同 session 重录覆盖前删旧文件（spec 重录 Requirement）。 */
    @Test
    fun `attachVideoToSession - deletes old file on re-record`() = runTest {
        val sessionId = repo.startSession(TelemetrySessionType.LAP_SESSION)
        val videoDir = File(tempDir, "video").apply { mkdirs() }
        val oldFile = File(videoDir, "old.mp4").apply { writeText("o") }
        val newFile = File(videoDir, "new.mp4").apply { writeText("n") }
        repo.attachVideoToSession(sessionId, oldFile.absolutePath, 100L)

        repo.attachVideoToSession(sessionId, newFile.absolutePath, 200L)

        assertFalse("old file deleted on re-record", oldFile.exists())
        assertTrue("new file kept", newFile.exists())
        assertEquals(newFile.absolutePath, fakeSessionDao.queryBySessionId(sessionId)?.videoFilePath)
    }

    /** 白名单外路径不删文件（spec 删除安全反例），但字段仍置空。 */
    @Test
    fun `deleteSessionVideo - whitelist rejects path outside video or telemetry`() = runTest {
        val sessionId = repo.startSession(TelemetrySessionType.LAP_SESSION)
        val strayFile = File(tempDir, "stray.mp4").apply { writeText("x") }
        repo.attachVideoToSession(sessionId, strayFile.absolutePath, 100L)

        repo.deleteSessionVideo(sessionId)

        assertTrue("stray file NOT deleted (whitelist)", strayFile.exists())
        assertNull(fakeSessionDao.queryBySessionId(sessionId)?.videoFilePath)
    }

    // --- Fake DAO 实现（与 baseline TelemetryRepositoryTest 同款） ---

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

        override fun getBestLapForTrack(trackId: String) =
            kotlinx.coroutines.flow.flowOf<TelemetrySessionEntity?>(null)

        override fun getSessionCountForTrack(trackId: String) =
            kotlinx.coroutines.flow.flowOf(0)

        override fun getTotalLapCountForTrack(trackId: String) =
            kotlinx.coroutines.flow.flowOf(0)

        override fun getRecentSessionsForTrack(trackId: String, limit: Int) =
            kotlinx.coroutines.flow.flowOf<List<TelemetrySessionEntity>>(emptyList())

        // video-storage-cleanup round：升级为真存（A 测试消费视频路径）。
        override suspend fun clearVideo(sessionId: String) {
            val idx = sessions.indexOfFirst { it.sessionId == sessionId }
            if (idx >= 0) sessions[idx] = sessions[idx].copy(videoFilePath = null, videoStartedAtWallClock = null)
        }
        override suspend fun updateVideoMetadata(sessionId: String, videoFilePath: String, videoStartedAtWallClock: Long) {
            val idx = sessions.indexOfFirst { it.sessionId == sessionId }
            if (idx >= 0) sessions[idx] = sessions[idx].copy(videoFilePath = videoFilePath, videoStartedAtWallClock = videoStartedAtWallClock)
        }

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

        override suspend fun deleteCrossingsBySessionId(sessionId: String) {
            crossings.removeIf { it.sessionId == sessionId }
        }
    }
}
