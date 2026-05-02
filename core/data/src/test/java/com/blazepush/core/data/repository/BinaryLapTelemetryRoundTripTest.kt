package com.blazepush.core.data.repository

import android.content.Context
import com.blazepush.core.data.local.binary.BinaryTelemetryWriter
import com.blazepush.core.data.local.binary.GpsBinaryFormat
import com.blazepush.core.data.local.dao.CrossingEventDao
import com.blazepush.core.data.local.dao.TelemetrySessionDao
import com.blazepush.core.data.local.entity.CrossingEventEntity
import com.blazepush.core.data.local.entity.TelemetrySessionEntity
import com.blazepush.core.domain.model.TelemetrySample
import com.blazepush.core.domain.model.TelemetrySessionType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files

/**
 * fix-lap-binary-ts-hygiene round 测试套件：lap session binary 写入读取的时钟域 hygiene + anchor 同源验证。
 *
 * 7 个 case（A-G）覆盖：
 * - case A：round trip 在 anchor 同源下窗口过滤命中
 * - case B（关键反例 / Codex review §1）：直接 BinaryTelemetryWriter 构造的 anchor 错位 binary 文件被 round trip 测试捕获
 * - case C：时间窗口正确剔除窗外样本
 * - case D：readLapSamples 全窗口 vs readPerformanceSamples 顺序读 等价
 * - case E（Codex review v2 §1）：startSession 后 entity.startTs / activeSessionStartTs / header.startTs 三相等
 * - case F：endSession 后 activeSessionStartTs 清空
 * - case G：writer flush 后未 close 即 read 仍正确返回，时钟域无错位
 *
 * 测试形态参考现有 TelemetryRepositoryEndSessionPersistTest：
 * - Fake DAO（实现接口）+ 真实 BinaryTelemetryWriter / LapTelemetryReader / PerformanceTestTelemetryReader
 * - mockito-core mock(Context) + when(context.filesDir).thenReturn(tempDir)
 * - 不引入 Room.inMemoryDatabaseBuilder / room-testing / Robolectric / mockk
 *
 * @author CC
 * @description lap binary clock-domain hygiene + anchor source round trip tests
 * @date 2026-05-01
 */
class BinaryLapTelemetryRoundTripTest {

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
        tempDir = Files.createTempDirectory("lap_round_trip_test").toFile()
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

    private fun lapSample(tsDeltaMs: Long, speedKmh: Double = 50.0): TelemetrySample =
        TelemetrySample(
            tsDeltaMs = tsDeltaMs,
            lat = 39.9042,
            lon = 116.4074,
            speedKmh = speedKmh,
            bearingDeg = 90.0,
        )

    private fun readHeader(file: File): GpsBinaryFormat.ChunkHeader {
        return RandomAccessFile(file, "r").use { raf ->
            val bytes = ByteArray(GpsBinaryFormat.HEADER_SIZE)
            raf.readFully(bytes)
            GpsBinaryFormat.decodeHeader(bytes)
        }
    }

    /**
     * §3.2 case A：round trip 在 anchor 同源下窗口过滤命中。
     * startSession 后 query activeSessionStartTs = T1，写入 N 帧 tsDeltaMs = i × 40，
     * readLapSamples(file, T1, T1 + (N-1)×40 + tolerance) 返回 N 帧。
     */
    @Test
    fun `case A - round trip with same anchor returns all written samples`() = runTest {
        val sessionId = repo.startSession(
            type = TelemetrySessionType.LAP_SESSION,
            trackId = "preset-tfic-lpcc",
            trackNameSnapshot = "成都天府国际赛道",
        )
        val t1 = requireNotNull(repo.activeSessionStartTs)

        val n = 50
        repeat(n) { i -> repo.writeSample(lapSample(i * 40L)) }
        repo.flush()
        repo.endSession(sessionId)

        val filePath = requireNotNull(repo.getSession(sessionId)).binaryFilePath
        val tolerance = 1L
        val samples = repo.readLapSamples(filePath, t1, t1 + (n - 1) * 40L + tolerance)
        assertEquals("expected all $n samples in [t1, t1 + (n-1)*40] window", n, samples.size)
    }

    /**
     * §3.3 case B（关键反例 / Codex review §1）：anchor 错位被 round trip 捕获。
     * 直接 BinaryTelemetryWriter.open(path, type, fakeStartTs=10000)，写入 100 帧 tsDeltaMs = (i × 40) + 5000
     * （额外 +5000 模拟 anchor 错位偏差），调 readLapSamples(file, 10000, 10000 + 100×40) 返回 0 帧。
     * 不依赖 mock System.currentTimeMillis，无需 Clock 注入。
     */
    @Test
    fun `case B - anchor misalignment is captured by round trip filter`() = runTest {
        val tempFile = File.createTempFile("anchor_misalign", ".bin", tempDir)
        val fakeStartTs = 10_000L
        val n = 100
        val writer = BinaryTelemetryWriter()
        writer.open(tempFile.absolutePath, TelemetrySessionType.LAP_SESSION, fakeStartTs)
        repeat(n) { i ->
            writer.write(
                TelemetrySample(
                    tsDeltaMs = (i * 40L) + 5000L,  // 模拟 anchor 错位 5s 偏差
                    lat = 39.9042,
                    lon = 116.4074,
                    speedKmh = 50.0,
                    bearingDeg = 90.0,
                )
            )
        }
        writer.close()

        // 窗口理论范围 [10000, 14000]，但 absoluteTs 全部 ≥ 15000（10000 + 5000+），全在窗口外
        val samples = repo.readLapSamples(tempFile.absolutePath, fakeStartTs, fakeStartTs + n * 40L)
        assertEquals("misaligned anchor should produce 0 samples in window", 0, samples.size)
    }

    /**
     * §3.4 case C：时间窗口正确剔除窗外样本。
     * 写 100 帧持续 4 秒，截取中间 2 秒 [T1+1000, T1+3000]，返回 50±1 帧（端点 boundary 容差）。
     */
    @Test
    fun `case C - time window filter correctly slices middle range`() = runTest {
        val sessionId = repo.startSession(TelemetrySessionType.LAP_SESSION)
        val t1 = requireNotNull(repo.activeSessionStartTs)

        repeat(100) { i -> repo.writeSample(lapSample(i * 40L)) }
        repo.flush()
        repo.endSession(sessionId)

        val filePath = requireNotNull(repo.getSession(sessionId)).binaryFilePath
        val samples = repo.readLapSamples(filePath, t1 + 1000, t1 + 3000)
        assertTrue("expected 49..51 samples in [t1+1000, t1+3000], got ${samples.size}", samples.size in 49..51)
    }

    /**
     * §3.5 case D：readLapSamples 全窗口 vs readPerformanceSamples 顺序读 等价。
     * 全窗口 readLapSamples 应该返回与顺序读相同的 N 帧，且字段逐条相等。
     */
    @Test
    fun `case D - readLapSamples vs readPerformanceSamples equivalence on full window`() = runTest {
        val sessionId = repo.startSession(TelemetrySessionType.LAP_SESSION)
        val t1 = requireNotNull(repo.activeSessionStartTs)

        val n = 30
        repeat(n) { i -> repo.writeSample(lapSample(i * 40L, speedKmh = 100.0 + i)) }
        repo.flush()
        repo.endSession(sessionId)

        val filePath = requireNotNull(repo.getSession(sessionId)).binaryFilePath
        val byWindow = repo.readLapSamples(filePath, t1, t1 + (n - 1) * 40L + 1)
        val sequential = repo.readPerformanceSamples(filePath)

        assertEquals("byWindow size == sequential size", byWindow.size, sequential.size)
        byWindow.zip(sequential).forEachIndexed { i, (w, s) ->
            assertEquals("sample $i lat", w.lat, s.lat, 1e-7)
            assertEquals("sample $i lon", w.lon, s.lon, 1e-7)
            assertEquals("sample $i speedKmh", w.speedKmh, s.speedKmh, 1e-3)
            assertEquals(
                "sample $i bearingDeg",
                w.bearingDeg ?: -1.0,
                s.bearingDeg ?: -1.0,
                1e-3,
            )
        }
    }

    /**
     * §3.6 case E（Codex review v2 §1 / 无 Clock 注入）：anchor 与 header.startTs / entity.startTs 三相等。
     * (1) repository.activeSessionStartTs != null
     * (2) sessionDao.queryBySessionId(sid).startTs == repository.activeSessionStartTs
     * (3) GpsBinaryFormat.decodeHeader(file).startTs == repository.activeSessionStartTs
     * 参考 BinaryTelemetryWriterTest readHeader() helper pattern；不引入 Clock 抽象。
     */
    @Test
    fun `case E - activeSessionStartTs equals entity startTs and header startTs`() = runTest {
        val sessionId = repo.startSession(TelemetrySessionType.LAP_SESSION)
        val activeStartTs = repo.activeSessionStartTs
        assertNotNull("activeSessionStartTs should be set after startSession", activeStartTs)

        val entity = requireNotNull(fakeSessionDao.queryBySessionId(sessionId))
        assertEquals("entity.startTs == repository.activeSessionStartTs", activeStartTs, entity.startTs)

        // header.startTs 在 writer.open 时就写入，不依赖 flush
        val filePath = entity.binaryFilePath
        val header = readHeader(File(filePath))
        assertEquals("header.startTs == repository.activeSessionStartTs", activeStartTs, header.startTs)

        repo.endSession(sessionId)
    }

    /**
     * §3.7 case F：endSession 后 activeSessionStartTs 清空。
     * 避免下个 session 复用 stale 值导致 anchor 漂移。
     */
    @Test
    fun `case F - activeSessionStartTs cleared on endSession`() = runTest {
        val sessionId = repo.startSession(TelemetrySessionType.LAP_SESSION)
        assertNotNull("activeSessionStartTs set after startSession", repo.activeSessionStartTs)
        repo.endSession(sessionId)
        assertNull("activeSessionStartTs should be cleared after endSession", repo.activeSessionStartTs)
    }

    /**
     * §3.8 case G：writer flush 后未 close 即 read 仍正确返回，时钟域无错位。
     * 模拟 "session 进行中（未 close）" 时的中途读取场景，验证 reader 路径在该状态下仍可读出 N 帧 + absoluteTs 精确。
     * Floor 截断逻辑由 BinaryTelemetryWriterTest 已覆盖，本 case 不重复测。
     */
    @Test
    fun `case G - read after flush without close returns frames with no clock drift`() = runTest {
        val tempFile = File.createTempFile("uncommit_close", ".bin", tempDir)
        val startTs = 10_000L
        val writer = BinaryTelemetryWriter()
        writer.open(tempFile.absolutePath, TelemetrySessionType.LAP_SESSION, startTs)
        repeat(5) { i ->
            writer.write(
                TelemetrySample(
                    tsDeltaMs = i * 40L,
                    lat = 39.9042,
                    lon = 116.4074,
                    speedKmh = 50.0,
                    bearingDeg = 90.0,
                )
            )
        }
        writer.flush()  // 不 close

        val samples = repo.readLapSamples(tempFile.absolutePath, startTs, startTs + 200L)
        assertEquals("expected 5 samples after flush even without close", 5, samples.size)
        samples.forEachIndexed { i, s ->
            val absoluteTs = startTs + s.tsDeltaMs
            assertEquals("sample $i absoluteTs precisely matches startTs + i*40", startTs + i * 40L, absoluteTs)
        }

        writer.close()
    }

    // --- Fake DAO 实现（与 baseline TelemetryRepositoryEndSessionPersistTest 同款）---

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

        // round wire-real-data-to-records-and-laps-tabs §1.6 引入的 abstract 方法，
        // 本套件不消费聚合行为，统一返回轻量空 flow（与现役 FakeTelemetrySessionDao 同款 stub）。
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