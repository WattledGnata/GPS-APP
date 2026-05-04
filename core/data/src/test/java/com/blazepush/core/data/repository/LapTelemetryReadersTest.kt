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
 * 10 cases (A-J) 覆盖 spec Requirement 1-5。
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

    private fun crossingEvent(sessionId: String, crossingWallClock: Long? = null, crossingTs: Long = 0L, accepted: Boolean = true) =
        TelemetryCrossingEvent(sessionId = sessionId, lapIndex = 0, crossingTimestampMs = crossingTs, speedKmh = 100.0, gateId = "SF", gateType = "StartFinish", accepted = accepted, reason = "", directionScore = 1.0, crossingWallClockTimestampMs = crossingWallClock)

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
