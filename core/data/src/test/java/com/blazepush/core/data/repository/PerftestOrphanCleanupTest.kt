// @IgnoreFormatCheck
package com.blazepush.core.data.repository

import android.content.Context
import com.blazepush.core.data.local.dao.CrossingEventDao
import com.blazepush.core.data.local.dao.SpeedSegmentDao
import com.blazepush.core.data.local.dao.TelemetrySessionDao
import com.blazepush.core.data.local.dao.TestRecordDao
import com.blazepush.core.data.local.entity.CrossingEventEntity
import com.blazepush.core.data.local.entity.SpeedSegmentEntity
import com.blazepush.core.data.local.entity.TelemetrySessionEntity
import com.blazepush.core.data.local.entity.TestRecordEntity
import kotlinx.coroutines.flow.Flow
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
import java.util.UUID

/**
 * cleanup-perftest-telemetry-session-orphan round 单测。
 *
 * 覆盖 spec history-deletion delta 两个 ADDED Requirement 全部 scenario：
 *   - deleteResult cascade 清 telemetry_sessions（case A-D）
 *   - 存量孤儿 sweep deletePerftestOrphans（case E-G）
 *   - DAO @Query SQL 字面量形态 grep contract（case H）
 *
 * 测试边界透明声明（tasks §4）：core/data 单测栈纯 JVM，@Query 真 SQL 不在覆盖内——
 * FakeTelemetrySessionDao.deletePerftestOrphans 忠实复刻 SQL 语义（sessionType 过滤 +
 * 反向 contains 关联检查）测 repository 层逻辑；SQL 字面量由 case H grep 锁形态；
 * 真 SQL 行为由真机攒批路测 FileLogger（tag=PerftestCascade）验证。
 *
 * @author CC
 * @description perftest orphan cascade + sweep unit tests
 * @date 2026-06-06
 */
class PerftestOrphanCleanupTest {

    private lateinit var tempDir: File
    private lateinit var telemetryDir: File
    private lateinit var context: Context
    private lateinit var fakeTestRecordDao: FakeTestRecordDao
    private lateinit var fakeSessionDao: FakeTelemetrySessionDao
    private lateinit var fakeCrossingDao: FakeCrossingEventDao
    private lateinit var telemetryRepo: TelemetryRepository
    private lateinit var testResultRepo: TestResultRepository

    @Before
    fun setup() {
        tempDir = Files.createTempDirectory("perftest_orphan_test").toFile()
        telemetryDir = File(tempDir, "telemetry").apply { mkdirs() }
        context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(tempDir)
        fakeTestRecordDao = FakeTestRecordDao()
        // sweep 语义复刻需要反向查 test_records.dataFilePath —— 注入 records provider
        fakeSessionDao = FakeTelemetrySessionDao { fakeTestRecordDao.records.toList() }
        fakeCrossingDao = FakeCrossingEventDao()
        telemetryRepo = TelemetryRepository(context, fakeSessionDao, fakeCrossingDao)
        testResultRepo = TestResultRepository(fakeTestRecordDao, FakeSpeedSegmentDao(), telemetryRepo)
    }

    @After
    fun teardown() {
        tempDir.deleteRecursively()
    }

    // --- fixture helpers ---

    private fun newBinaryFile(name: String): File =
        File(telemetryDir, name).apply { writeBytes(byteArrayOf(1, 2, 3)) }

    private fun testRecord(id: String, dataFilePath: String) = TestRecordEntity(
        id = id,
        testTemplateId = "acc_0_100",
        testType = "0-100",
        carModel = "test-car",
        deviceName = "RaceChrono GPS",
        deviceAddress = "",
        result = "7.95",
        timestamp = 1_700_000_000_000L,
        dataFilePath = dataFilePath,
    )

    private fun sessionEntity(sessionId: String, type: String, binaryPath: String = "") =
        TelemetrySessionEntity(
            sessionId = sessionId,
            sessionType = type,
            startTs = 1_700_000_000_000L,
            endTs = 1_700_000_060_000L,
            binaryFilePath = binaryPath,
        )

    /**
     * case A（spec Req1 Scenario 1）：正常 PERFORMANCE 记录删除三处全清。
     */
    @Test
    fun `case A - deleteResult cascades test_records and telemetry_sessions and binary`() = runTest {
        val uuid = UUID.randomUUID().toString()
        val binary = newBinaryFile("$uuid.bin")
        fakeTestRecordDao.insertTestRecord(testRecord("r1", binary.absolutePath))
        fakeSessionDao.insert(sessionEntity(uuid, "PERFORMANCE_TEST", binary.absolutePath))

        testResultRepo.deleteResultById("r1")

        assertNull("test_records 行应删除", fakeTestRecordDao.getTestRecordById("r1"))
        assertNull("telemetry_sessions 行应被 cascade 删除", fakeSessionDao.queryBySessionId(uuid))
        assertFalse("binary 文件应删除", binary.exists())
    }

    /**
     * case B（spec Req1 Scenario 2）：telemetry_sessions 无对应行时静默成功。
     */
    @Test
    fun `case B - deleteResult silent when no telemetry_sessions row`() = runTest {
        val uuid = UUID.randomUUID().toString()
        val binary = newBinaryFile("$uuid.bin")
        fakeTestRecordDao.insertTestRecord(testRecord("r1", binary.absolutePath))
        // 不插 session 行（早期数据形态）

        testResultRepo.deleteResultById("r1")

        assertNull(fakeTestRecordDao.getTestRecordById("r1"))
        assertFalse("binary 文件仍应被兜底删除", binary.exists())
    }

    /**
     * case C（spec Req1 Scenario 3）：非 UUID basename 跳过 cascade，原删除正常。
     */
    @Test
    fun `case C - deleteResult skips cascade for non-UUID basename`() = runTest {
        val binary = newBinaryFile("legacy_data.bin")
        fakeTestRecordDao.insertTestRecord(testRecord("r1", binary.absolutePath))
        // 放一条 PERFORMANCE_TEST 行：cascade 不应触发，行必须保留
        val unrelated = UUID.randomUUID().toString()
        fakeSessionDao.insert(sessionEntity(unrelated, "PERFORMANCE_TEST"))

        testResultRepo.deleteResultById("r1")

        assertNull(fakeTestRecordDao.getTestRecordById("r1"))
        assertFalse("binary 文件应被原白名单逻辑删除", binary.exists())
        assertNotNull("非 UUID basename 不得触发任何 session 删除", fakeSessionDao.queryBySessionId(unrelated))
    }

    /**
     * case D（spec Req1 Scenario 4 反例）：cascade 不误删其他 session 行。
     */
    @Test
    fun `case D - deleteResult does not touch other sessions`() = runTest {
        val uuidA = UUID.randomUUID().toString()
        val uuidB = UUID.randomUUID().toString()
        val lapId = UUID.randomUUID().toString()
        val binaryA = newBinaryFile("$uuidA.bin")
        fakeTestRecordDao.insertTestRecord(testRecord("rA", binaryA.absolutePath))
        fakeSessionDao.insert(sessionEntity(uuidA, "PERFORMANCE_TEST", binaryA.absolutePath))
        fakeSessionDao.insert(sessionEntity(uuidB, "PERFORMANCE_TEST"))
        fakeSessionDao.insert(sessionEntity(lapId, "LAP_SESSION"))

        testResultRepo.deleteResultById("rA")

        assertNull("目标行应删除", fakeSessionDao.queryBySessionId(uuidA))
        assertNotNull("其他 PERFORMANCE 行必须保留", fakeSessionDao.queryBySessionId(uuidB))
        assertNotNull("LAP_SESSION 行必须保留", fakeSessionDao.queryBySessionId(lapId))
    }

    /**
     * case E（spec Req2 Scenario 1+2）：sweep 删孤儿、留有引用行。
     */
    @Test
    fun `case E - sweep removes orphan keeps referenced`() = runTest {
        val orphanId = UUID.randomUUID().toString()
        val referencedId = UUID.randomUUID().toString()
        fakeSessionDao.insert(sessionEntity(orphanId, "PERFORMANCE_TEST"))
        fakeSessionDao.insert(sessionEntity(referencedId, "PERFORMANCE_TEST"))
        fakeTestRecordDao.insertTestRecord(
            testRecord("r1", File(telemetryDir, "$referencedId.bin").absolutePath)
        )

        val removed = telemetryRepo.cleanupPerftestOrphans()

        assertEquals(1, removed)
        assertNull("孤儿行应删除", fakeSessionDao.queryBySessionId(orphanId))
        assertNotNull("有引用行应保留", fakeSessionDao.queryBySessionId(referencedId))
    }

    /**
     * case F（spec Req2 Scenario 3 反例）：LAP_SESSION 行绝不参与 sweep。
     * LAP 路径本就不写 test_records，天然"无引用"——若实现遗漏 sessionType 限定，
     * LAP 数据会被误删（不可接受的数据丢失），本 case 即红。
     */
    @Test
    fun `case F - sweep never touches LAP_SESSION rows`() = runTest {
        val lapId = UUID.randomUUID().toString()
        fakeSessionDao.insert(sessionEntity(lapId, "LAP_SESSION"))

        val removed = telemetryRepo.cleanupPerftestOrphans()

        assertEquals(0, removed)
        assertNotNull("LAP_SESSION 行必须完整保留", fakeSessionDao.queryBySessionId(lapId))
    }

    /**
     * case G（spec Req2 Scenario 4）：混合 fixture 精确清理——返回 2 仅删 2。
     */
    @Test
    fun `case G - sweep mixed fixture removes exactly orphans`() = runTest {
        val orphan1 = UUID.randomUUID().toString()
        val orphan2 = UUID.randomUUID().toString()
        val referenced = UUID.randomUUID().toString()
        val lap1 = UUID.randomUUID().toString()
        val lap2 = UUID.randomUUID().toString()
        fakeSessionDao.insert(sessionEntity(orphan1, "PERFORMANCE_TEST"))
        fakeSessionDao.insert(sessionEntity(orphan2, "PERFORMANCE_TEST"))
        fakeSessionDao.insert(sessionEntity(referenced, "PERFORMANCE_TEST"))
        fakeSessionDao.insert(sessionEntity(lap1, "LAP_SESSION"))
        fakeSessionDao.insert(sessionEntity(lap2, "LAP_SESSION"))
        fakeTestRecordDao.insertTestRecord(
            testRecord("r1", File(telemetryDir, "$referenced.bin").absolutePath)
        )

        val removed = telemetryRepo.cleanupPerftestOrphans()

        assertEquals(2, removed)
        assertNull(fakeSessionDao.queryBySessionId(orphan1))
        assertNull(fakeSessionDao.queryBySessionId(orphan2))
        assertNotNull(fakeSessionDao.queryBySessionId(referenced))
        assertNotNull(fakeSessionDao.queryBySessionId(lap1))
        assertNotNull(fakeSessionDao.queryBySessionId(lap2))
    }

    /**
     * case H（grep contract）：锁死 DAO @Query SQL 字面量形态——
     * 必须含反向 NOT EXISTS 关联 + sessionType 限定；禁止 path 前缀 REPLACE 提取写法
     * （memo perftest-cascade-orphan-cleanup-deferred.md §5.3 反例：多用户路径 / 厂商 ROM /
     * 格式迁移敏感，有误删风险）。改写 SQL 形态时本 case 即红。
     */
    @Test
    fun `case H - dao query literal locks NOT EXISTS form and forbids REPLACE`() {
        val daoFile = locateDaoFile()
        val source = daoFile.readText()

        assertTrue("DAO 必须声明 deletePerftestOrphans", source.contains("suspend fun deletePerftestOrphans(): Int"))
        assertTrue("sweep SQL 必须用 NOT EXISTS 反向关联", source.contains("NOT EXISTS"))
        assertTrue(
            "sweep SQL 必须限定 sessionType = 'PERFORMANCE_TEST'",
            source.contains("sessionType = 'PERFORMANCE_TEST'"),
        )
        // 整文件断言：DAO 内任何 @Query 都不得用 REPLACE( 路径前缀提取（memo §5.3 反例）
        assertFalse(
            "sweep SQL 禁止 REPLACE( 路径前缀提取写法（memo §5.3 反例）",
            source.contains("REPLACE("),
        )
    }

    /**
     * 跨 working dir 定位 DAO 源文件（candidates 模式，参 BinaryPerftestTelemetryRoundTripTest:450-462）。
     */
    private fun locateDaoFile(): File {
        val relPath = "core/data/src/main/java/com/blazepush/core/data/local/dao/TelemetrySessionDao.kt"
        val candidates = listOf(
            File("src/main/java/com/blazepush/core/data/local/dao/TelemetrySessionDao.kt"), // cwd = core/data
            File("../$relPath"),
            File("../../$relPath"),
            File(relPath), // cwd = repo root
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("TelemetrySessionDao.kt not found, cwd=${System.getProperty("user.dir")}")
    }

    // --- fakes（形态对齐 LapTelemetryReadersTest 现有 fake；sweep 语义忠实复刻 SQL）---

    private class FakeTestRecordDao : TestRecordDao {
        val records = mutableListOf<TestRecordEntity>()
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

    private class FakeTelemetrySessionDao(
        private val recordsProvider: () -> List<TestRecordEntity>,
    ) : TelemetrySessionDao {
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
        override suspend fun clearVideo(sessionId: String) {}
        override suspend fun updateVideoMetadata(sessionId: String, videoFilePath: String, videoStartedAtWallClock: Long) {}
        override suspend fun deleteSession(e: TelemetrySessionEntity) { sessions.removeIf { it.sessionId == e.sessionId } }

        /**
         * 忠实复刻 @Query SQL 语义：
         * DELETE WHERE sessionType='PERFORMANCE_TEST' AND NOT EXISTS(
         *   SELECT 1 FROM test_records WHERE dataFilePath LIKE '%'||sessionId||'%')
         */
        override suspend fun deletePerftestOrphans(): Int {
            val paths = recordsProvider().map { it.dataFilePath }
            val orphans = sessions.filter { s ->
                s.sessionType == "PERFORMANCE_TEST" && paths.none { it.contains(s.sessionId) }
            }
            sessions.removeAll(orphans.toSet())
            return orphans.size
        }
    }

    private class FakeCrossingEventDao : CrossingEventDao {
        private val crossings = mutableListOf<CrossingEventEntity>()
        override suspend fun insertInTransaction(e: CrossingEventEntity) { crossings.add(e) }
        override suspend fun queryBySessionId(sid: String) = crossings.filter { it.sessionId == sid }
        override suspend fun deleteCrossingsBySessionId(sid: String) { crossings.removeIf { it.sessionId == sid } }
    }
}
