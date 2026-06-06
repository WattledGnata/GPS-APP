// @IgnoreFormatCheck
package com.blazepush.core.data.repository

import android.content.Context
import com.blazepush.core.data.local.dao.CrossingEventDao
import com.blazepush.core.data.local.dao.TelemetrySessionDao
import com.blazepush.core.data.local.dao.VideoSegmentDao
import com.blazepush.core.data.local.entity.CrossingEventEntity
import com.blazepush.core.data.local.entity.TelemetrySessionEntity
import com.blazepush.core.data.local.entity.VideoSegmentEntity
import com.blazepush.core.domain.model.TelemetrySessionType
import kotlinx.coroutines.flow.flowOf
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
 * video-segment-schema round ②a 单测：attach append 语义 + 双写 + 全段 cascade。
 *
 * 覆盖 spec video-segment-model 全部 attach/cascade scenario +
 * video-storage-cleanup delta（重录保留旧段，原"删旧"语义反转——
 * 该反转的回归断言同时在 TelemetryRepositoryDeleteSessionTest 重录 case）。
 *
 * @author CC
 * @description segment append + dual-write + full cascade unit tests
 * @date 2026-06-07
 */
class VideoSegmentAttachCascadeTest {

    private lateinit var tempDir: File
    private lateinit var videoDir: File
    private lateinit var context: Context
    private lateinit var fakeSessionDao: FakeTelemetrySessionDao
    private lateinit var fakeCrossingDao: FakeCrossingEventDao
    private lateinit var fakeSegmentDao: FakeVideoSegmentDao
    private lateinit var repo: TelemetryRepository

    @Before
    fun setup() {
        tempDir = Files.createTempDirectory("video_segment_test").toFile()
        videoDir = File(tempDir, "video").apply { mkdirs() }
        context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(tempDir)
        fakeSessionDao = FakeTelemetrySessionDao()
        fakeCrossingDao = FakeCrossingEventDao()
        fakeSegmentDao = FakeVideoSegmentDao()
        repo = TelemetryRepository(context, fakeSessionDao, fakeCrossingDao, fakeSegmentDao)
    }

    @After
    fun teardown() {
        tempDir.deleteRecursively()
    }

    private fun newVideoFile(name: String): File =
        File(videoDir, name).apply { writeText("v") }

    /** case A（spec Req2 Scenario 1 核心痛点）：救援段 + 正常段都保留，旧字段=最新段。 */
    @Test
    fun `case A - two attaches both kept with dual-written legacy fields`() = runTest {
        val sessionId = repo.startSession(TelemetrySessionType.LAP_SESSION)
        val rescue = newVideoFile("rescue.mp4")
        val normal = newVideoFile("normal.mp4")

        repo.attachVideoToSession(sessionId, rescue.absolutePath, 1000L, playable = null, durationMs = null)
        repo.attachVideoToSession(sessionId, normal.absolutePath, 2000L, playable = true, durationMs = 60000L)

        val segments = fakeSegmentDao.queryBySessionId(sessionId)
        assertEquals(2, segments.size)
        assertEquals("救援段 index 0 playable=null", null, segments[0].playable)
        assertEquals(true, segments[1].playable)
        assertTrue("救援段文件未被删旧逻辑删除", rescue.exists())
        assertEquals("旧字段=最新段", normal.absolutePath, fakeSessionDao.queryBySessionId(sessionId)?.videoFilePath)
    }

    /** case B（spec Req2 Scenario 2）：首段 index 0 + endWallClock 推算。 */
    @Test
    fun `case B - first segment index zero with computed endWallClock`() = runTest {
        val sessionId = repo.startSession(TelemetrySessionType.LAP_SESSION)
        val f = newVideoFile("first.mp4")

        repo.attachVideoToSession(sessionId, f.absolutePath, 5000L, playable = true, durationMs = 60000L)

        val seg = fakeSegmentDao.queryBySessionId(sessionId).single()
        assertEquals(0, seg.segmentIndex)
        assertEquals(65000L, seg.endWallClock)
        assertEquals(60000L, seg.durationMs)
    }

    /** case C（spec Req2 Scenario 3 反例锁）：双写一致——子表最新段与旧字段同 path 同 wallClock。 */
    @Test
    fun `case C - dual write consistency between segment table and legacy fields`() = runTest {
        val sessionId = repo.startSession(TelemetrySessionType.LAP_SESSION)
        val f = newVideoFile("dw.mp4")

        repo.attachVideoToSession(sessionId, f.absolutePath, 7777L, playable = true, durationMs = null)

        val latest = fakeSegmentDao.queryBySessionId(sessionId).last()
        val entity = fakeSessionDao.queryBySessionId(sessionId)
        assertEquals(latest.filePath, entity?.videoFilePath)
        assertEquals(latest.startWallClock, entity?.videoStartedAtWallClock)
    }

    /** case D（spec Req2 Scenario 4 反例）：删旧逻辑残留 grep 锁。 */
    @Test
    fun `case D - replaceOld branch must not exist in attach implementation`() {
        val src = locateTelemetryRepositoryFile().readText()
        assertFalse(
            "attachVideoToSession-replaceOld 分支 MUST 已删除（残留即重录静默删合法旧段）",
            src.contains("attachVideoToSession-replaceOld"),
        )
    }

    /** case E（spec Req3 Scenario 1）：deleteSession 清全段文件 + 行。 */
    @Test
    fun `case E - deleteSession cascades all segment files and rows`() = runTest {
        val sessionId = repo.startSession(TelemetrySessionType.LAP_SESSION)
        val files = (0..2).map { newVideoFile("seg$it.mp4") }
        files.forEachIndexed { i, f ->
            repo.attachVideoToSession(sessionId, f.absolutePath, 1000L * (i + 1), playable = true, durationMs = 1000L)
        }
        repo.endSession(sessionId)

        repo.deleteSession(sessionId)

        files.forEach { assertFalse("段文件 ${it.name} 应删除", it.exists()) }
        assertEquals("段行显式清空（实现不依赖 FK CASCADE，fake 可真实断言）", 0, fakeSegmentDao.queryBySessionId(sessionId).size)
        assertNull("session 行删除", fakeSessionDao.queryBySessionId(sessionId))
    }

    /** case F（spec Req3 Scenario 2）：成绩页删视频清全段，圈速数据保留。 */
    @Test
    fun `case F - deleteSessionVideo removes all segments keeps lap data`() = runTest {
        val sessionId = repo.startSession(TelemetrySessionType.LAP_SESSION)
        val f0 = newVideoFile("a.mp4")
        val f1 = newVideoFile("b.mp4")
        repo.attachVideoToSession(sessionId, f0.absolutePath, 100L, playable = true, durationMs = null)
        repo.attachVideoToSession(sessionId, f1.absolutePath, 200L, playable = true, durationMs = null)
        repo.endSession(sessionId)

        repo.deleteSessionVideo(sessionId)

        assertFalse(f0.exists())
        assertFalse(f1.exists())
        assertEquals("段行全删", 0, fakeSegmentDao.queryBySessionId(sessionId).size)
        val entity = fakeSessionDao.queryBySessionId(sessionId)
        assertNotNull("session 行保留", entity)
        assertNull("旧字段置空", entity?.videoFilePath)
    }

    /** case G（spec Req3 Scenario 3 反例）：白名单外段文件不删，行照删。 */
    @Test
    fun `case G - whitelist rejects segment file outside video and telemetry`() = runTest {
        val sessionId = repo.startSession(TelemetrySessionType.LAP_SESSION)
        val stray = File(tempDir, "stray.mp4").apply { writeText("x") } // 不在 /video/ 下
        repo.attachVideoToSession(sessionId, stray.absolutePath, 100L, playable = true, durationMs = null)
        repo.endSession(sessionId)

        repo.deleteSessionVideo(sessionId)

        assertTrue("白名单外文件 MUST NOT 删", stray.exists())
        assertEquals("行照常删除", 0, fakeSegmentDao.queryBySessionId(sessionId).size)
    }

    private fun locateTelemetryRepositoryFile(): File {
        val relPath = "core/data/src/main/java/com/blazepush/core/data/repository/TelemetryRepository.kt"
        val candidates = listOf(
            File("src/main/java/com/blazepush/core/data/repository/TelemetryRepository.kt"),
            File("../$relPath"),
            File("../../$relPath"),
            File(relPath),
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("TelemetryRepository.kt not found, cwd=${System.getProperty("user.dir")}")
    }

    // --- fakes（与既有测试同款形态）---

    private class FakeTelemetrySessionDao : TelemetrySessionDao {
        private val sessions = mutableListOf<TelemetrySessionEntity>()
        override suspend fun insert(e: TelemetrySessionEntity) { sessions.removeIf { it.sessionId == e.sessionId }; sessions.add(e) }
        override suspend fun updateEndTs(sid: String, endTs: Long) { val i = sessions.indexOfFirst { it.sessionId == sid }; if (i >= 0) sessions[i] = sessions[i].copy(endTs = endTs) }
        override suspend fun updateSummary(sid: String, endTs: Long, lapCount: Int, bestLapMs: Long?, topSpeed: Double?) { val i = sessions.indexOfFirst { it.sessionId == sid }; if (i >= 0) sessions[i] = sessions[i].copy(endTs = endTs, lapCount = lapCount, bestLapMs = bestLapMs, topSpeedKmh = topSpeed) }
        override suspend fun queryBySessionId(sid: String) = sessions.find { it.sessionId == sid }
        override suspend fun queryAll() = sessions.toList()
        override fun getBestLapForTrack(trackId: String) = flowOf<TelemetrySessionEntity?>(null)
        override fun getSessionCountForTrack(trackId: String) = flowOf(0)
        override fun getTotalLapCountForTrack(trackId: String) = flowOf(0)
        override fun getRecentSessionsForTrack(trackId: String, limit: Int) = flowOf<List<TelemetrySessionEntity>>(emptyList())
        override suspend fun clearVideo(sessionId: String) { val i = sessions.indexOfFirst { it.sessionId == sessionId }; if (i >= 0) sessions[i] = sessions[i].copy(videoFilePath = null, videoStartedAtWallClock = null) }
        override suspend fun updateVideoMetadata(sessionId: String, videoFilePath: String, videoStartedAtWallClock: Long) { val i = sessions.indexOfFirst { it.sessionId == sessionId }; if (i >= 0) sessions[i] = sessions[i].copy(videoFilePath = videoFilePath, videoStartedAtWallClock = videoStartedAtWallClock) }
        override suspend fun deletePerftestOrphans(): Int = 0
        override suspend fun deleteSession(e: TelemetrySessionEntity) { sessions.removeIf { it.sessionId == e.sessionId } }
    }

    private class FakeCrossingEventDao : CrossingEventDao {
        private val crossings = mutableListOf<CrossingEventEntity>()
        override suspend fun insertInTransaction(e: CrossingEventEntity) { crossings.add(e) }
        override suspend fun queryBySessionId(sid: String) = crossings.filter { it.sessionId == sid }
        override suspend fun deleteCrossingsBySessionId(sid: String) { crossings.removeIf { it.sessionId == sid } }
    }

    private class FakeVideoSegmentDao : VideoSegmentDao {
        val segments = mutableListOf<VideoSegmentEntity>()
        override suspend fun insert(entity: VideoSegmentEntity): Long {
            // 模拟 FK：session 删除后的插入不做约束（测试场景不覆盖）；自增 id 简化
            segments.add(entity.copy(id = (segments.maxOfOrNull { it.id } ?: 0L) + 1))
            return segments.size.toLong()
        }
        override suspend fun queryBySessionId(sessionId: String) = segments.filter { it.sessionId == sessionId }.sortedBy { it.segmentIndex }
        override suspend fun maxSegmentIndex(sessionId: String) = segments.filter { it.sessionId == sessionId }.maxOfOrNull { it.segmentIndex }
        override suspend fun deleteBySessionId(sessionId: String) { segments.removeIf { it.sessionId == sessionId } }
    }
}
