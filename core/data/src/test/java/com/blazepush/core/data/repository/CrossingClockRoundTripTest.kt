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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File
import java.nio.file.Files
import com.blazepush.core.data.local.dao.VideoSegmentDao
import com.blazepush.core.data.local.entity.VideoSegmentEntity

/**
 * fix-lap-crossing-clock-hygiene round 测试套件：crossing event 双时钟域字段 + per-lap segment 反例锁死。
 *
 * 5 个 case（A / B / C1 / C2 / D）覆盖：
 * - case A：双时钟域字段 round trip 映射不漏（精确等 + assertNotNull 三层断言）
 * - case B：per-lap segment readLapSamples 用 wallClock 窗口命中
 * - case C1：极端偏差反例（0 命中锁死）
 * - case C2：小偏差错位反例（silent failure 锁死，wallClock vs protocolTs 两次截取不等）
 * - case D：写入路径 grep gate（grep TestSessionViewModel.kt 内 wallClock = currentTimeMillis() 表达式
 *   命中恰好 1 次 + 行号位置接近 writeCrossing 调用）
 *
 * case D'（跨文件逃逸 grep gate）放 feature/test 模块；case E1（SQL 自检）扩展 AppDatabaseMigrationSqlTest；
 * case E2（AppModule 注册自检）放 feature/test 模块。
 *
 * 测试形态参考现有 BinaryLapTelemetryRoundTripTest / TelemetryRepositoryEndSessionPersistTest：
 * - Fake DAO（实现接口）+ 真实 BinaryTelemetryWriter / LapTelemetryReader
 * - mockito-core mock(Context) + when(context.filesDir).thenReturn(tempDir)
 * - 不引入 Room.inMemoryDatabaseBuilder / room-testing / Robolectric / mockk
 *
 * @author CC
 * @description crossing wall-clock + per-lap segment round trip + reverse case lockdown
 * @date 2026-05-03
 */
class CrossingClockRoundTripTest {

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
        tempDir = Files.createTempDirectory("crossing_clock_test").toFile()
        context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(tempDir)
        fakeSessionDao = FakeTelemetrySessionDao()
        fakeCrossingDao = FakeCrossingEventDao()
        repo = TelemetryRepository(context, fakeSessionDao, fakeCrossingDao, FakeVideoSegmentDao())
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

    /**
     * §6.2 case A · 双时钟域字段 round trip 映射不漏（精确等，nullable 形态明示）。
     *
     * 三层断言：
     * (1) crossingTimestampMs 精确等（验证写入路径不污染该字段）
     * (2) crossingWallClockTimestampMs != null（验证 toDomain 映射不漏；禁用 ?.let 形态否则 null 时
     *     assertion 静默 skip 不 fail）
     * (3) crossingWallClockTimestampMs 精确等（测试场景下手工注入值不需 100ms 容差）
     *
     * 生产路径 100ms 漂移契约（spec scenario 1b）由真机 sanity check 验证，不在本 case 覆盖。
     */
    @Test
    fun `case A - crossing event round trip preserves both protocolTs and wallClock`() = runTest {
        val sessionId = repo.startSession(
            type = TelemetrySessionType.LAP_SESSION,
            trackId = "preset-tfic-lpcc",
            trackNameSnapshot = "成都天府国际赛道",
        )

        val protocolTsInjected = 1_700_000_000_000L
        val wallClockInjected = 1_700_000_000_500L
        val event = TelemetryCrossingEvent(
            sessionId = sessionId,
            lapIndex = 1,
            crossingTimestampMs = protocolTsInjected,
            crossingWallClockTimestampMs = wallClockInjected,
            speedKmh = 100.0,
            gateId = "sf",
            gateType = "StartFinish",
            accepted = true,
            reason = "Accepted",
            directionScore = 0.95,
        )

        repo.writeCrossing(event)
        val retrieved = repo.getCrossings(sessionId).single()

        // 三层断言（v3 review v3 §P1#2 / §C#5 修订）
        assertEquals("crossingTimestampMs 精确等", protocolTsInjected, retrieved.crossingTimestampMs)
        assertNotNull(
            "crossingWallClockTimestampMs 必须非 null（验证 toDomain 映射不漏字段）",
            retrieved.crossingWallClockTimestampMs,
        )
        assertEquals(
            "crossingWallClockTimestampMs 精确等（测试手工注入值，不需 100ms 容差）",
            wallClockInjected,
            retrieved.crossingWallClockTimestampMs,
        )

        repo.endSession(sessionId)
    }

    /**
     * §6.3 case B · per-lap segment readLapSamples 用 wallClock 窗口命中。
     *
     * T1 由 repository.activeSessionStartTs query 拿（== header.startTs，由 fix-lap-binary-ts-hygiene round
     * 锁定同源）。写 N=100 帧 samples（每 40ms 一帧，tsDeltaMs=0..3960）+ wallClock 在中间区间的 2 个 crossing
     * → readLapSamples(filePath, T1+1000, T1+3000) 返回 50±1 帧（端点 boundary 容差 = LapTelemetryReader.kt:39
     * 闭区间端点取整）。
     */
    @Test
    fun `case B - readLapSamples with wallClock window hits expected mid-range frames`() = runTest {
        val sessionId = repo.startSession(TelemetrySessionType.LAP_SESSION)
        val t1 = requireNotNull(repo.activeSessionStartTs)

        repeat(100) { i -> repo.writeSample(lapSample(i * 40L)) }
        // 模拟 wallClock 在中间时刻：c1 = t1+1000, c2 = t1+3000
        repo.writeCrossing(makeCrossing(sessionId, lapIndex = 1, protocolTs = 999L, wallClock = t1 + 1000))
        repo.writeCrossing(makeCrossing(sessionId, lapIndex = 2, protocolTs = 999L, wallClock = t1 + 3000))
        repo.flush()
        repo.endSession(sessionId)

        val filePath = requireNotNull(repo.getSession(sessionId)).binaryFilePath
        val samples = repo.readLapSamples(filePath, t1 + 1000, t1 + 3000)
        assertTrue(
            "expected 49..51 samples in [t1+1000, t1+3000] (闭区间端点容差), got ${samples.size}",
            samples.size in 49..51,
        )
    }

    /**
     * §6.4 case C1 · 极端偏差反例（0 命中锁死）。
     *
     * binary samples absoluteTs 在真壁钟时钟域 [T1, T1+4000]；构造 2 个 crossing：wallClock 在 [T1+1000, T1+3000]
     * 中间，但 protocolTs 偏移极大（+1_000_000_000ms ~ 16 分钟，模拟跨小时切换 / simulator 重启时的协议时间跳变）。
     * 用 protocolTs 截取 → 0 帧（窗口跟 binary samples 完全无交集）。
     */
    @Test
    fun `case C1 - extreme protocolTs offset produces 0 samples in window`() = runTest {
        val sessionId = repo.startSession(TelemetrySessionType.LAP_SESSION)
        val t1 = requireNotNull(repo.activeSessionStartTs)

        repeat(100) { i -> repo.writeSample(lapSample(i * 40L)) }
        // wallClock 正确，protocolTs 偏移 ~16 分钟（模拟极端跳变）
        val skew = 1_000_000_000L
        repo.writeCrossing(
            makeCrossing(sessionId, lapIndex = 1, protocolTs = t1 + 1000 + skew, wallClock = t1 + 1000),
        )
        repo.writeCrossing(
            makeCrossing(sessionId, lapIndex = 2, protocolTs = t1 + 3000 + skew, wallClock = t1 + 3000),
        )
        repo.flush()
        repo.endSession(sessionId)

        val crossings = repo.getCrossings(sessionId).sortedBy { it.lapIndex }
        val filePath = requireNotNull(repo.getSession(sessionId)).binaryFilePath
        val samplesByProtocolTs = repo.readLapSamples(
            filePath,
            crossings[0].crossingTimestampMs,
            crossings[1].crossingTimestampMs,
        )
        assertEquals(
            "用 protocolTs 极端偏差窗口截取应 0 命中（跨时钟域反例锁死）",
            0,
            samplesByProtocolTs.size,
        )
    }

    /**
     * §6.5 case C2 · 小偏差错位反例（silent failure 锁死，v3 review §P1#3 关键 case）。
     *
     * 构造 wallClock 在中间时刻，protocolTs = wallClock + 1500（典型 GPS clock skew）。
     * 两次截取对比：
     * - 用 wallClock 窗口 → 50±1 帧（正确）
     * - 用 protocolTs 窗口 → 不等于 wallClock 集合（数量差非 0；典型情况是窗口偏移 1500ms 后命中错的样本子集）
     *
     * 此 case 锁死"小偏差不会让窗口空，但会命中错误样本"的 silent failure 模式。
     */
    @Test
    fun `case C2 - small protocolTs skew causes silent failure misalignment`() = runTest {
        val sessionId = repo.startSession(TelemetrySessionType.LAP_SESSION)
        val t1 = requireNotNull(repo.activeSessionStartTs)

        repeat(100) { i -> repo.writeSample(lapSample(i * 40L)) }
        val skew = 1500L
        repo.writeCrossing(
            makeCrossing(sessionId, lapIndex = 1, protocolTs = t1 + 1000 + skew, wallClock = t1 + 1000),
        )
        repo.writeCrossing(
            makeCrossing(sessionId, lapIndex = 2, protocolTs = t1 + 3000 + skew, wallClock = t1 + 3000),
        )
        repo.flush()
        repo.endSession(sessionId)

        val crossings = repo.getCrossings(sessionId).sortedBy { it.lapIndex }
        val filePath = requireNotNull(repo.getSession(sessionId)).binaryFilePath
        val samplesByWallClock = repo.readLapSamples(
            filePath,
            requireNotNull(crossings[0].crossingWallClockTimestampMs),
            requireNotNull(crossings[1].crossingWallClockTimestampMs),
        )
        val samplesByProtocolTs = repo.readLapSamples(
            filePath,
            crossings[0].crossingTimestampMs,
            crossings[1].crossingTimestampMs,
        )

        // wallClock 截取正确（50±1）
        assertTrue(
            "wallClock 截取应得 49..51 帧, got ${samplesByWallClock.size}",
            samplesByWallClock.size in 49..51,
        )
        // protocolTs 截取数量与 wallClock 不等（典型偏移 1500ms 让窗口截到错的样本子集）
        assertNotEquals(
            "protocolTs 截取的样本集合数量必须与 wallClock 集合不等（silent failure 锁死）",
            samplesByWallClock.size,
            samplesByProtocolTs.size,
        )
    }

    /**
     * §6.6 case D · 写入路径 grep gate（v3 review v2 §P0#3 重写为有保护价值）。
     *
     * grep `feature/test/.../TestSessionViewModel.kt` 内 `crossingWallClockTimestampMs = System.currentTimeMillis()`
     * 表达式：
     * - 命中**恰好 1 次**（防有人加第二处入口或回退删除）
     * - 行号位置位于 `telemetryRepository.writeCrossing(` 调用上方 ≤30 行（确保写入路径就在 LAP_SESSION 锚点位置）
     *
     * 跨模块定位用候选路径列表兜底（同 BinaryLapTelemetryRoundTripTest case H pattern）。
     */
    @Test
    fun `case D - source grep gate locks crossingWallClock assignment to writeCrossing site`() {
        val viewModelFile = locateTestSessionViewModelFile()
        val source = viewModelFile.readText()
        val lines = source.lines()

        val wallClockAssignPattern =
            Regex("""crossingWallClockTimestampMs\s*=\s*System\.currentTimeMillis\(\)""")
        val wallClockHits = lines.withIndex()
            .filter { wallClockAssignPattern.containsMatchIn(it.value) }
            .map { it.index + 1 } // 1-based line number

        assertEquals(
            "crossingWallClockTimestampMs = System.currentTimeMillis() 表达式必须恰好命中 1 次（防回退或多入口）：" +
                "命中行号 = $wallClockHits",
            1,
            wallClockHits.size,
        )

        val writeCrossingPattern = Regex("""telemetryRepository\.writeCrossing\(""")
        val writeCrossingHits = lines.withIndex()
            .filter { writeCrossingPattern.containsMatchIn(it.value) }
            .map { it.index + 1 }
        assertEquals(
            "telemetryRepository.writeCrossing( 调用必须恰好 1 次（确保 LAP_SESSION 路径单一入口）：" +
                "命中行号 = $writeCrossingHits",
            1,
            writeCrossingHits.size,
        )

        val wallClockLine = wallClockHits.single()
        val writeCrossingLine = writeCrossingHits.single()
        val distance = wallClockLine - writeCrossingLine
        assertTrue(
            "wallClock 赋值行 ($wallClockLine) 必须位于 writeCrossing 调用行 ($writeCrossingLine) 下方 0..30 行内" +
                "（确保锚点位置）：实际 distance = $distance",
            distance in 0..30,
        )
    }

    /**
     * 跨模块定位 TestSessionViewModel.kt 源文件。Gradle 跑 :core:data:testDebugUnitTest 时 cwd 为 core/data/，
     * 但 ./gradlew 在 repo root 时 cwd 不同；用候选路径列表兜底（同 BinaryLapTelemetryRoundTripTest 模式）。
     */
    private fun locateTestSessionViewModelFile(): File {
        val relPath = "feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt"
        val candidates = listOf(
            File("../$relPath"),       // cwd = core/data/
            File("../../$relPath"),    // cwd = core/data/build/...
            File(relPath),             // cwd = repo root
        )
        return candidates.firstOrNull { it.exists() }
            ?: error(
                "TestSessionViewModel.kt not found. Tried: ${candidates.map { it.absolutePath }.joinToString()}, " +
                    "cwd=${System.getProperty("user.dir")}",
            )
    }

    private fun makeCrossing(
        sessionId: String,
        lapIndex: Int,
        protocolTs: Long,
        wallClock: Long?,
    ): TelemetryCrossingEvent = TelemetryCrossingEvent(
        sessionId = sessionId,
        lapIndex = lapIndex,
        crossingTimestampMs = protocolTs,
        crossingWallClockTimestampMs = wallClock,
        speedKmh = 100.0,
        gateId = "sf",
        gateType = "StartFinish",
        accepted = true,
        reason = "Accepted",
        directionScore = 0.95,
    )

    // --- Fake DAO 实现（与 BinaryLapTelemetryRoundTripTest / EndSessionPersistTest 同款 stub）---

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

        // session-video-metadata-persist round：同步 abstract 方法。本套件不消费视频路径，no-op。
        override suspend fun clearVideo(sessionId: String) {}
        override suspend fun updateVideoMetadata(sessionId: String, videoFilePath: String, videoStartedAtWallClock: Long) {
        }

        // J round add-history-deletion 引入的 abstract 方法（v3 高频盲点 #14）；本套件不消费 cascade，stub 即可。
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

        // J round add-history-deletion 引入的 abstract 方法（v3 高频盲点 #14）；本套件不消费，stub 即可。
        override suspend fun deleteCrossingsBySessionId(sessionId: String) {
            crossings.removeAll { it.sessionId == sessionId }
        }
    }
    // video-segment-schema round ②a：构造第 4 参连锁 stub（minimal in-memory fake）。
    private class FakeVideoSegmentDao : VideoSegmentDao {
        val segments = mutableListOf<VideoSegmentEntity>()
        override suspend fun insert(entity: VideoSegmentEntity): Long { segments.add(entity); return segments.size.toLong() }
        override suspend fun queryBySessionId(sessionId: String) = segments.filter { it.sessionId == sessionId }.sortedBy { it.segmentIndex }
        override suspend fun maxSegmentIndex(sessionId: String) = segments.filter { it.sessionId == sessionId }.maxOfOrNull { it.segmentIndex }
        override suspend fun deleteBySessionId(sessionId: String) { segments.removeIf { it.sessionId == sessionId } }
    }
}
