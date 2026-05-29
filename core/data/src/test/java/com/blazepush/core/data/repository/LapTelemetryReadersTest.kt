package com.blazepush.core.data.repository

import android.content.Context
import com.blazepush.core.data.local.dao.CrossingEventDao
import com.blazepush.core.data.local.dao.SpeedSegmentDao
import com.blazepush.core.data.local.dao.TestRecordDao
import com.blazepush.core.data.local.dao.TelemetrySessionDao
import com.blazepush.core.data.local.entity.CrossingEventEntity
import com.blazepush.core.data.local.entity.SpeedSegmentEntity
import com.blazepush.core.data.local.entity.TestRecordEntity
import com.blazepush.core.data.local.entity.TelemetrySessionEntity
import com.blazepush.core.domain.model.TelemetryCrossingEvent
import com.blazepush.core.domain.model.TelemetrySample
import com.blazepush.core.domain.model.TelemetrySessionType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
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
import java.nio.file.Files

/**
 * lap-data-readers round: getLapTelemetry + getDataPointsForResult reader API。
 * 11 cases (A-J + L) 覆盖 spec Requirement 1-5；case L 由 unify-perftest-anchor-cross-clock round
 * 追加（getDataPointsForResult sentinel entity.timestamp 跨时钟域 guard）。
 * cases M-R 由 future-sector-derivation round 追加，覆盖 sectorBoundaries 派生契约：
 *   M 多段派生 / N 无 sector 回退单段 / O 窗口外 sector 排除（反例）/ P rejected+null-wallClock 排除 /
 *   Q sector wallClock 恰等 lapStart 去重 / R 跨时钟域 guard（MUST 用 wallClock 不用 GPS 协议钟，反例）。
 */
class LapTelemetryReadersTest {

    private lateinit var tempDir: File
    private lateinit var context: Context
    private lateinit var fakeSessionDao: FakeTelemetrySessionDao
    private lateinit var fakeCrossingDao: FakeCrossingEventDao
    private lateinit var fakeTestRecordDao: FakeTestRecordDao
    private lateinit var fakeSpeedSegmentDao: FakeSpeedSegmentDao
    private lateinit var repo: TelemetryRepository
    private lateinit var testResultRepo: TestResultRepository

    @Before
    fun setup() {
        tempDir = Files.createTempDirectory("lap_readers_test").toFile()
        context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(tempDir)
        fakeSessionDao = FakeTelemetrySessionDao()
        fakeCrossingDao = FakeCrossingEventDao()
        fakeTestRecordDao = FakeTestRecordDao()
        fakeSpeedSegmentDao = FakeSpeedSegmentDao()
        repo = TelemetryRepository(context, fakeSessionDao, fakeCrossingDao)
        testResultRepo = TestResultRepository(fakeTestRecordDao, fakeSpeedSegmentDao, repo)
    }

    @After
    fun teardown() {
        tempDir.deleteRecursively()
    }

    private fun lapSample(tsDeltaMs: Long, speedKmh: Double = 50.0, flags: Int = 0) =
        TelemetrySample(tsDeltaMs = tsDeltaMs, lat = 39.9042, lon = 116.4074, speedKmh = speedKmh, bearingDeg = 90.0, flags = flags)

    private fun crossingEvent(
        sessionId: String,
        crossingWallClock: Long? = null,
        crossingTs: Long = 0L,
        accepted: Boolean = true,
        gateType: String = "StartFinish",
        gateId: String = if (gateType.equals("Sector", ignoreCase = true)) "S" else "SF",
    ) =
        TelemetryCrossingEvent(sessionId = sessionId, lapIndex = 0, crossingTimestampMs = crossingTs, speedKmh = 100.0, gateId = gateId, gateType = gateType, accepted = accepted, reason = "", directionScore = 1.0, crossingWallClockTimestampMs = crossingWallClock)

    // --- case A ---
    @Test
    fun `case A - normal single lap read with flags passthrough`() = runTest {
        val sessionId = repo.startSession(TelemetrySessionType.LAP_SESSION)
        val entity = fakeSessionDao.queryBySessionId(sessionId)!!
        val wb = entity.startTs + 1000L
        repeat(100) { repo.writeSample(lapSample(it * 40L, flags = 5)) }
        repo.flush()
        repo.writeCrossing(crossingEvent(sessionId, crossingWallClock = wb))
        repo.writeCrossing(crossingEvent(sessionId, crossingWallClock = wb + 2000))
        repo.writeCrossing(crossingEvent(sessionId, crossingWallClock = wb + 4000))
        val r = repo.getLapTelemetry(sessionId, 0)
        assertNotNull(r); r!!
        assertTrue(r.samples.isNotEmpty())
        assertEquals(r.lapStartWallClock, r.sectorBoundaries.first())
        assertTrue(r.lapDurationMs > 0)
        assertEquals(5, r.samples.first().flags)
    }

    // --- case B ---
    @Test
    fun `case B - lapIndex out of bounds returns null`() = runTest {
        val sessionId = repo.startSession(TelemetrySessionType.LAP_SESSION)
        val wb = fakeSessionDao.queryBySessionId(sessionId)!!.startTs + 1000L
        repo.writeCrossing(crossingEvent(sessionId, crossingWallClock = wb))
        repo.writeCrossing(crossingEvent(sessionId, crossingWallClock = wb + 2000))
        repo.writeCrossing(crossingEvent(sessionId, crossingWallClock = wb + 4000))
        assertNull(repo.getLapTelemetry(sessionId, 5))
    }

    // --- case C ---
    @Test
    fun `case C - non-existent session returns null`() = runTest {
        assertNull(repo.getLapTelemetry("non-existent", 0))
    }

    // --- case D ---
    @Test
    fun `case D - missing binary file returns null`() = runTest {
        val sessionId = repo.startSession(TelemetrySessionType.LAP_SESSION)
        val entity = fakeSessionDao.queryBySessionId(sessionId)!!
        val wb = entity.startTs + 1000L
        repo.writeCrossing(crossingEvent(sessionId, crossingWallClock = wb))
        repo.writeCrossing(crossingEvent(sessionId, crossingWallClock = wb + 2000))
        File(entity.binaryFilePath).delete()
        assertNull(repo.getLapTelemetry(sessionId, 0))
    }

    // --- case E ---
    @Test
    fun `case E - all null wallClock returns null`() = runTest {
        val sessionId = repo.startSession(TelemetrySessionType.LAP_SESSION)
        repeat(5) { repo.writeCrossing(crossingEvent(sessionId, crossingWallClock = null, crossingTs = it * 1000L)) }
        assertNull(repo.getLapTelemetry(sessionId, 0))
        assertNull(repo.getLapTelemetry(sessionId, 1))
    }

    // --- case F ---
    @Test
    fun `case F - getDataPointsForResult normal path with flags passthrough`() = runTest {
        val sessionId = repo.startSession(TelemetrySessionType.PERFORMANCE_TEST)
        val entity = fakeSessionDao.queryBySessionId(sessionId)!!
        repeat(100) { repo.writeSample(lapSample(it * 40L, speedKmh = 80.0 + it, flags = 7)) }
        repo.flush(); repo.endSession(sessionId)
        fakeTestRecordDao.insertTestRecord(TestRecordEntity(
            id = "t1", testTemplateId = "Acceleration0To100", testType = "0-100", carModel = "Test",
            deviceName = "GPS", deviceAddress = "", result = "4.21", timestamp = entity.startTs,
            totalTime = 4.21, totalDistance = 100.0, avgAcceleration = 0.5, maxAcceleration = 0.8,
            dataFilePath = entity.binaryFilePath))
        val r = testResultRepo.getDataPointsForResult("t1")
        assertNotNull(r); r!!
        assertEquals(100, r.samples.size)
        assertEquals(entity.startTs, r.testStartWallClock)
        assertEquals(7, r.samples.first().flags)
    }

    // --- case G ---
    @Test
    fun `case G - non-existent testId returns null`() = runTest {
        assertNull(testResultRepo.getDataPointsForResult("non-existent"))
    }

    // --- case H ---
    @Test
    fun `case H - empty dataFilePath returns null`() = runTest {
        fakeTestRecordDao.insertTestRecord(TestRecordEntity(
            id = "t2", testTemplateId = "A", testType = "0-100", carModel = "", deviceName = "",
            deviceAddress = "", result = "", timestamp = 0, totalTime = 0.0, totalDistance = 0.0,
            avgAcceleration = 0.0, maxAcceleration = 0.0, dataFilePath = ""))
        assertNull(testResultRepo.getDataPointsForResult("t2"))
    }

    // --- case I ---
    @Test
    fun `case I - non-existent file path returns null`() = runTest {
        fakeTestRecordDao.insertTestRecord(TestRecordEntity(
            id = "t3", testTemplateId = "A", testType = "0-100", carModel = "", deviceName = "",
            deviceAddress = "", result = "", timestamp = 0, totalTime = 0.0, totalDistance = 0.0,
            avgAcceleration = 0.0, maxAcceleration = 0.0, dataFilePath = "/nonexistent.bin"))
        assertNull(testResultRepo.getDataPointsForResult("t3"))
    }

    // --- case J：grep gate ---
    private fun projectRoot(): File {
        val classesDir = File(javaClass.protectionDomain.codeSource.location.toURI())
        val userDir = File(System.getProperty("user.dir"))
        return sequenceOf(classesDir, userDir)
            .flatMap { start -> generateSequence(start) { it.parentFile }.filterNotNull() }
            .first { File(it, "settings.gradle").exists() || File(it, "settings.gradle.kts").exists() }
    }

    @Test
    fun `case J - grep gate verifies wallClock Elvis and no protocol ts fallback`() {
        val root = projectRoot()
        val trSrc = File(root, "core/data/src/main/java/com/blazepush/core/data/repository/TelemetryRepository.kt").readText()
        val gateA = Regex("""crossingWallClockTimestampMs\s*\?\:\s*return\s+null""").findAll(trSrc).count()
        assertEquals("gate-A: exactly 2 Elvis early returns", 2, gateA)
        val lines = trSrc.lines()
        val s = lines.indexOfFirst { it.contains("suspend fun getLapTelemetry") }
        assertTrue(s >= 0)
        var depth = 0; var e = -1
        outer@ for (i in s..lines.lastIndex) { for (c in lines[i]) { if (c == '{') depth++; else if (c == '}') { depth--; if (depth == 0) { e = i; break@outer } } } }
        assertTrue(e > s)
        val block = lines.subList(s, e + 1).joinToString("\n")
        assertEquals("gate-B: 0 bare crossingTimestampMs", 0, Regex("""\.crossingTimestampMs\b""").findAll(block).count())
        val gateC = Regex("""^\s*(import |private val |val |var |fun |class |suspend fun ).*\b(TestResultRepository|TestRecordDao|TestRecordEntity|TestResultSummary)\b""")
        assertEquals("gate-C: 0 production refs to TestResult series", 0, trSrc.lines().filter { !it.trimStart().startsWith("*") && gateC.containsMatchIn(it) }.count())
        val trrSrc = File(root, "core/data/src/main/java/com/blazepush/core/data/repository/TestResultRepository.kt").readText()
        assertEquals("gate-D: only readPerformanceSamples", 0, Regex("""telemetryRepository\.(?!readPerformanceSamples\b)\w+""").findAll(trrSrc).count())
    }

    // --- case L：sentinel entity.timestamp 返回 null（unify-perftest-anchor-cross-clock round）---
    @Test
    fun `case L - getDataPointsForResult sentinel entity timestamp returns null`() = runTest {
        // binary 文件完全合法（100 帧），仅 entity.timestamp = Long.MIN_VALUE（GPS 未同步 sentinel）。
        // 没有 sentinel guard 时：readPerformanceSamples 读出 100 帧 → 返回非 null PerformanceTelemetry
        //   （absoluteTsMs = Long.MIN_VALUE + tsDeltaMs，catastrophic）。
        // 有 guard 时：在 readPerformanceSamples 调用之前返回 null。assertNull 即证明 guard 生效。
        val sessionId = repo.startSession(TelemetrySessionType.PERFORMANCE_TEST)
        val entity = fakeSessionDao.queryBySessionId(sessionId)!!
        repeat(100) { repo.writeSample(lapSample(it * 40L, speedKmh = 80.0 + it, flags = 7)) }
        repo.flush(); repo.endSession(sessionId)
        fakeTestRecordDao.insertTestRecord(TestRecordEntity(
            id = "t-sentinel", testTemplateId = "Acceleration0To100", testType = "0-100", carModel = "Test",
            deviceName = "GPS", deviceAddress = "", result = "4.21", timestamp = Long.MIN_VALUE,
            totalTime = 4.21, totalDistance = 100.0, avgAcceleration = 0.5, maxAcceleration = 0.8,
            dataFilePath = entity.binaryFilePath))
        val r = testResultRepo.getDataPointsForResult("t-sentinel")
        assertNull("sentinel entity.timestamp MUST 返回 null（即便 binary 完全可读）", r)
    }

    // --- case M：多 sector 派生多元素 sectorBoundaries（future-sector-derivation round）---
    // 窗口 [wb, wb+2000)；2 个 accepted Sector wallClock = wb+500 / wb+1200 落窗口内。
    @Test
    fun `case M - multi sector derives multi-element sectorBoundaries`() = runTest {
        val sessionId = repo.startSession(TelemetrySessionType.LAP_SESSION)
        val entity = fakeSessionDao.queryBySessionId(sessionId)!!
        val wb = entity.startTs + 1000L
        // binary samples 覆盖窗口 [wb, wb+2000] → tsDeltaMs in [1000, 3000]。
        repeat(100) { repo.writeSample(lapSample(it * 40L)) }
        repo.flush()
        // 2 个 accepted StartFinish 配对出 lapIndex=0 窗口 [wb, wb+2000]。
        repo.writeCrossing(crossingEvent(sessionId, crossingWallClock = wb))
        repo.writeCrossing(crossingEvent(sessionId, crossingWallClock = wb + 2000))
        // 2 个 accepted Sector 落窗口内。
        repo.writeCrossing(crossingEvent(sessionId, crossingWallClock = wb + 500, gateType = "Sector"))
        repo.writeCrossing(crossingEvent(sessionId, crossingWallClock = wb + 1200, gateType = "Sector"))
        val r = repo.getLapTelemetry(sessionId, 0)
        assertNotNull(r); r!!
        assertEquals(listOf(wb, wb + 500, wb + 1200), r.sectorBoundaries)
        assertEquals(wb, r.sectorBoundaries.first())
        assertEquals(3, r.sectorBoundaries.size)
    }

    // --- case N：无 sector 回退单段（不回归 baseline）---
    @Test
    fun `case N - no sector falls back to single element`() = runTest {
        val sessionId = repo.startSession(TelemetrySessionType.LAP_SESSION)
        val entity = fakeSessionDao.queryBySessionId(sessionId)!!
        val wb = entity.startTs + 1000L
        repeat(100) { repo.writeSample(lapSample(it * 40L)) }
        repo.flush()
        repo.writeCrossing(crossingEvent(sessionId, crossingWallClock = wb))
        repo.writeCrossing(crossingEvent(sessionId, crossingWallClock = wb + 2000))
        // 无任何 Sector crossing。
        val r = repo.getLapTelemetry(sessionId, 0)
        assertNotNull(r); r!!
        assertEquals(listOf(wb), r.sectorBoundaries)
    }

    // --- case O：窗口外 sector 排除（反例锁死窗口过滤）---
    // 圈0 窗口 [wb, wb+2000)；圈1 窗口 [wb+2000, wb+4000)。
    @Test
    fun `case O - out of window sector excluded`() = runTest {
        val sessionId = repo.startSession(TelemetrySessionType.LAP_SESSION)
        val entity = fakeSessionDao.queryBySessionId(sessionId)!!
        val wb = entity.startTs + 1000L
        // binary 覆盖两圈窗口 → tsDeltaMs in [1000, 5000]。
        repeat(150) { repo.writeSample(lapSample(it * 40L)) }
        repo.flush()
        // 3 个 accepted SF → lapIndex=0 [wb, wb+2000]，lapIndex=1 [wb+2000, wb+4000]。
        repo.writeCrossing(crossingEvent(sessionId, crossingWallClock = wb))
        repo.writeCrossing(crossingEvent(sessionId, crossingWallClock = wb + 2000))
        repo.writeCrossing(crossingEvent(sessionId, crossingWallClock = wb + 4000))
        // 2 个 sector 落圈0 / 2 个落圈1。
        repo.writeCrossing(crossingEvent(sessionId, crossingWallClock = wb + 500, gateType = "Sector"))
        repo.writeCrossing(crossingEvent(sessionId, crossingWallClock = wb + 1200, gateType = "Sector"))
        repo.writeCrossing(crossingEvent(sessionId, crossingWallClock = wb + 2500, gateType = "Sector"))
        repo.writeCrossing(crossingEvent(sessionId, crossingWallClock = wb + 3300, gateType = "Sector"))
        val r0 = repo.getLapTelemetry(sessionId, 0)
        assertNotNull(r0); r0!!
        assertEquals(listOf(wb, wb + 500, wb + 1200), r0.sectorBoundaries)
        // 圈1 的 sector MUST NOT 混入圈0。
        assertTrue(!r0.sectorBoundaries.contains(wb + 2500))
        assertTrue(!r0.sectorBoundaries.contains(wb + 3300))
        val r1 = repo.getLapTelemetry(sessionId, 1)
        assertNotNull(r1); r1!!
        assertEquals(listOf(wb + 2000, wb + 2500, wb + 3300), r1.sectorBoundaries)
    }

    // --- case P：rejected 与 null-wallClock sector 排除 ---
    @Test
    fun `case P - rejected and null-wallClock sector excluded`() = runTest {
        val sessionId = repo.startSession(TelemetrySessionType.LAP_SESSION)
        val entity = fakeSessionDao.queryBySessionId(sessionId)!!
        val wb = entity.startTs + 1000L
        repeat(100) { repo.writeSample(lapSample(it * 40L)) }
        repo.flush()
        repo.writeCrossing(crossingEvent(sessionId, crossingWallClock = wb))
        repo.writeCrossing(crossingEvent(sessionId, crossingWallClock = wb + 2000))
        // (a) accepted + wallClock 有效；(b) accepted=false；(c) wallClock=null。
        repo.writeCrossing(crossingEvent(sessionId, crossingWallClock = wb + 500, gateType = "Sector"))
        repo.writeCrossing(crossingEvent(sessionId, crossingWallClock = wb + 800, gateType = "Sector", accepted = false))
        repo.writeCrossing(crossingEvent(sessionId, crossingWallClock = null, gateType = "Sector"))
        val r = repo.getLapTelemetry(sessionId, 0)
        assertNotNull(r); r!!
        assertEquals(listOf(wb, wb + 500), r.sectorBoundaries)
    }

    // --- case Q：sector wallClock 恰等于 lapStart 去重 ---
    @Test
    fun `case Q - sector wallClock equal to lapStart deduped`() = runTest {
        val sessionId = repo.startSession(TelemetrySessionType.LAP_SESSION)
        val entity = fakeSessionDao.queryBySessionId(sessionId)!!
        val wb = entity.startTs + 1000L
        repeat(100) { repo.writeSample(lapSample(it * 40L)) }
        repo.flush()
        repo.writeCrossing(crossingEvent(sessionId, crossingWallClock = wb))
        repo.writeCrossing(crossingEvent(sessionId, crossingWallClock = wb + 2000))
        // 1 个 sector wallClock == lapStart（退化）/ 1 个正常。
        repo.writeCrossing(crossingEvent(sessionId, crossingWallClock = wb, gateType = "Sector"))
        repo.writeCrossing(crossingEvent(sessionId, crossingWallClock = wb + 1200, gateType = "Sector"))
        val r = repo.getLapTelemetry(sessionId, 0)
        assertNotNull(r); r!!
        assertEquals(listOf(wb, wb + 1200), r.sectorBoundaries)
        // 无重复相邻 == 项（首项不重复）。
        assertEquals(2, r.sectorBoundaries.size)
    }

    // --- case R：跨时钟域 guard——用 wallClock 不用 GPS 协议时钟 ---
    // sector 的 crossingWallClockTimestampMs 落窗口内，但 crossingTimestampMs（GPS 协议钟）落窗口外。
    @Test
    fun `case R - clock domain guard uses wallClock not gps clock`() = runTest {
        val sessionId = repo.startSession(TelemetrySessionType.LAP_SESSION)
        val entity = fakeSessionDao.queryBySessionId(sessionId)!!
        val wb = entity.startTs + 1000L
        repeat(100) { repo.writeSample(lapSample(it * 40L)) }
        repo.flush()
        repo.writeCrossing(crossingEvent(sessionId, crossingWallClock = wb))
        repo.writeCrossing(crossingEvent(sessionId, crossingWallClock = wb + 2000))
        // sector wallClock=wb+700 在窗口内；crossingTs=99_999_999（GPS 协议钟）落窗口外。
        // 若实现误用 crossingTimestampMs 判窗口 → 该 sector 被错排除 → sectorBoundaries 退化 [wb] → fail。
        repo.writeCrossing(
            crossingEvent(sessionId, crossingWallClock = wb + 700, crossingTs = 99_999_999L, gateType = "Sector")
        )
        val r = repo.getLapTelemetry(sessionId, 0)
        assertNotNull(r); r!!
        assertEquals(listOf(wb, wb + 700), r.sectorBoundaries)
    }

    // --- Fake DAOs ---
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
        override suspend fun deleteSession(e: TelemetrySessionEntity) { sessions.removeIf { it.sessionId == e.sessionId } }
    }
    private class FakeCrossingEventDao : CrossingEventDao {
        private val crossings = mutableListOf<CrossingEventEntity>()
        override suspend fun insertInTransaction(e: CrossingEventEntity) { crossings.add(e) }
        override suspend fun queryBySessionId(sid: String) = crossings.filter { it.sessionId == sid }
        override suspend fun deleteCrossingsBySessionId(sid: String) { crossings.removeIf { it.sessionId == sid } }
    }
    private class FakeTestRecordDao : TestRecordDao {
        private val records = mutableListOf<TestRecordEntity>()
        override fun getAllTestRecordsFlow(): Flow<List<TestRecordEntity>> = flowOf(records.toList())
        override suspend fun getAllTestRecordsSync() = records.toList()
        override suspend fun getTestRecordById(id: String) = records.find { it.id == id }
        override suspend fun insertTestRecord(r: TestRecordEntity) { records.removeIf { it.id == r.id }; records.add(r) }
        override suspend fun deleteTestRecord(r: TestRecordEntity) { records.removeIf { it.id == r.id } }
        override suspend fun deleteAllTestRecords() { records.clear() }
        override fun getBestAcceleration0To100(): Flow<TestRecordEntity?> = flowOf(null)
        override fun getBestBraking100To0(): Flow<TestRecordEntity?> = flowOf(null)
        override fun getTotalCount(): Flow<Int> = flowOf(0)
        override fun getRecentFlow(limit: Int): Flow<List<TestRecordEntity>> = flowOf(emptyList())
    }
    private class FakeSpeedSegmentDao : SpeedSegmentDao {
        override fun getSegmentsByTestId(id: String): Flow<List<SpeedSegmentEntity>> = flowOf(emptyList())
        override suspend fun getSegmentsByTestIdSync(id: String) = emptyList<SpeedSegmentEntity>()
        override suspend fun insertSegments(s: List<SpeedSegmentEntity>) {}
        override suspend fun deleteSegmentsByTestId(id: String) {}
    }
}
