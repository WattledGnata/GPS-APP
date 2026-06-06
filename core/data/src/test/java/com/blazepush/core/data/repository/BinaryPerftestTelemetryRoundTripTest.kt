// @IgnoreFormatCheck
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files

/**
 * fix-perftest-binary-ts-hygiene round 测试套件：PERFORMANCE_TEST 路径 binary 写入读取的时钟域 hygiene + anchor 同源验证。
 *
 * 8 个 case（A-H）覆盖：
 * - case A：PERFORMANCE_TEST round trip 在 anchor 同源下窗口过滤命中
 * - case B（关键反例）：直接 BinaryTelemetryWriter 构造的 anchor 错位 binary 文件被窗口过滤剔除
 * - case C：preTrigger buffer 回填帧 absoluteTs 集中在 startTest 内部循环耗时窗口（spec 接受语义）
 * - case D：持续写 absoluteTs 分布真实（deterministic tsDeltaMs = i × 40，无 sleep）
 * - case E：源码 grep gate 时钟域单源自检（两类禁止 + 1 类正向 == 3）
 * - case F：跨文件 grep gate 防漏改 + 防扫错路径假性绿（命中 0 + 文件数 ≥ 30）
 * - case G：源码 fallback 形态对齐（processFilteredData / startTest 形态 A/B + 函数前缀 anchor 锁死 ==2 + P4 防 bare return regression）
 * - case H：activeTestStartTs 字段两步赋值语义未被破坏（防本修复误改下游派生）
 *
 * 测试形态参考 A round BinaryLapTelemetryRoundTripTest：
 * - Fake DAO（实现接口）+ 真实 BinaryTelemetryWriter / LapTelemetryReader / PerformanceTestTelemetryReader
 * - mockito-core mock(Context) + when(context.filesDir).thenReturn(tempDir)
 * - 不引入 Room.inMemoryDatabaseBuilder / room-testing / Robolectric / mockk
 *
 * @author CC
 * @description PERFORMANCE_TEST binary clock-domain hygiene + anchor source round trip tests
 * @date 2026-05-04
 */
class BinaryPerftestTelemetryRoundTripTest {

    private lateinit var tempDir: File
    private lateinit var context: Context
    private lateinit var fakeSessionDao: FakeTelemetrySessionDao
    private lateinit var fakeCrossingDao: FakeCrossingEventDao
    private lateinit var repo: TelemetryRepository

    /**
     * 每个 case 之前重建临时目录 + mock Context + Fake DAO + Repository
     * （与 A round BinaryLapTelemetryRoundTripTest 同 pattern）。
     */
    @Before
    fun setup() {
        tempDir = Files.createTempDirectory("perftest_round_trip_test").toFile()
        context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(tempDir)
        fakeSessionDao = FakeTelemetrySessionDao()
        fakeCrossingDao = FakeCrossingEventDao()
        repo = TelemetryRepository(context, fakeSessionDao, fakeCrossingDao)
    }

    /**
     * 清理临时目录（递归），避免 case 间状态串味。
     */
    @After
    fun teardown() {
        tempDir.deleteRecursively()
    }

    private fun perftestSample(tsDeltaMs: Long, speedKmh: Double = 80.0): TelemetrySample =
        TelemetrySample(
            tsDeltaMs = tsDeltaMs,
            lat = 30.4897,
            lon = 104.4326,
            speedKmh = speedKmh,
            bearingDeg = 90.0,
        )

    private fun readHeader(file: File): GpsBinaryFormat.ChunkHeader =
        RandomAccessFile(file, "r").use { raf ->
            val bytes = ByteArray(GpsBinaryFormat.HEADER_SIZE)
            raf.readFully(bytes)
            GpsBinaryFormat.decodeHeader(bytes)
        }

    /**
     * case A：PERFORMANCE_TEST round trip 在 anchor 同源下窗口过滤命中。
     * startSession(PERFORMANCE_TEST) → 拉 activeSessionStartTs = T1 → 连续 N=10 帧
     * `tsDeltaMs = currentTimeMillis - T1` 写入 → endSession → readPerformanceSamples 返回 N 帧；
     * readLapSamples(file, T1, T1 + N×40) 同样返回 N 帧。
     */
    @Test
    fun `case A - PERFORMANCE_TEST round trip with same anchor returns all samples`() = runTest {
        val sessionId = repo.startSession(TelemetrySessionType.PERFORMANCE_TEST)
        val t1 = requireNotNull(repo.activeSessionStartTs)

        val n = 10
        repeat(n) { repo.writeSample(perftestSample(System.currentTimeMillis() - t1)) }
        repo.flush()
        repo.endSession(sessionId)

        val filePath = requireNotNull(repo.getSession(sessionId)).binaryFilePath
        val sequential = repo.readPerformanceSamples(filePath)
        assertEquals("readPerformanceSamples should return all $n samples", n, sequential.size)

        // 所有 absoluteTs 落在 [entity.startTs, entity.endTs + 100ms tolerance]
        val entity = requireNotNull(fakeSessionDao.queryBySessionId(sessionId))
        val tolerance = 100L
        sequential.forEachIndexed { i, s ->
            val absoluteTs = entity.startTs + s.tsDeltaMs
            assertTrue(
                "sample $i absoluteTs=$absoluteTs not in [${entity.startTs}, ${entity.endTs + tolerance}]",
                absoluteTs in entity.startTs..(entity.endTs + tolerance),
            )
        }

        // readLapSamples(file, T1, T1 + N×40) 同样命中
        val byWindow = repo.readLapSamples(filePath, t1, t1 + n * 40L + tolerance)
        assertEquals("readLapSamples on same window should return all samples", n, byWindow.size)
    }

    /**
     * case B（关键反例）：anchor 错位 binary 被窗口过滤剔除。
     * 直接 BinaryTelemetryWriter.open(path, PERFORMANCE_TEST, startTs=10000) 构造文件，
     * 写入 100 帧 tsDeltaMs = (i × 40) + 5000（额外 +5000 模拟跨时钟域偏差），
     * readLapSamples(file, 10000, 14000) 返回 0 帧。
     * 反例语义：如果生产代码 anchor 仍跨时钟域偏移 5000ms，case A 的 happy assert
     * `samples.size == n` 会从 N 跌到 0，bug 被捕获。
     */
    @Test
    fun `case B - anchor misalignment is rejected by window filter`() = runTest {
        val tempFile = File.createTempFile("perftest_anchor_misalign", ".bin", tempDir)
        val fakeStartTs = 10_000L
        val n = 100
        val writer = BinaryTelemetryWriter()
        writer.open(tempFile.absolutePath, TelemetrySessionType.PERFORMANCE_TEST, fakeStartTs)
        repeat(n) { i ->
            writer.write(
                TelemetrySample(
                    tsDeltaMs = (i * 40L) + 5000L,
                    lat = 30.4897,
                    lon = 104.4326,
                    speedKmh = 80.0,
                    bearingDeg = 90.0,
                ),
            )
        }
        writer.close()

        val samples = repo.readLapSamples(tempFile.absolutePath, fakeStartTs, fakeStartTs + n * 40L)
        assertEquals("misaligned anchor should produce 0 samples in window", 0, samples.size)
    }

    /**
     * case C：preTrigger buffer 回填帧 absoluteTs 集中在 startTest 内部窗口（spec 接受语义）。
     * 模拟 startTest preTrigger 回填流程：构造 N=25 帧的 lockedPreTriggerBuffer 概念，
     * startSession 后循环内每帧 `tsDeltaMs = currentTimeMillis - sessionStartTs` 写入，
     * 验证所有 25 帧 absoluteTs ∈ [entity.startTs, entity.startTs + 100ms]
     * （ms 级集中，不要求散布在 buffer 实际采集时长——这是 spec 接受语义）。
     */
    @Test
    fun `case C - preTrigger backfill samples concentrate in startTest internal window`() = runTest {
        val sessionId = repo.startSession(TelemetrySessionType.PERFORMANCE_TEST)
        val sessionStartTs = requireNotNull(repo.activeSessionStartTs)

        // 模拟 preTrigger 回填循环 — 每帧写入瞬间用 currentTimeMillis
        val n = 25
        repeat(n) { repo.writeSample(perftestSample(System.currentTimeMillis() - sessionStartTs)) }
        repo.flush()
        repo.endSession(sessionId)

        val filePath = requireNotNull(repo.getSession(sessionId)).binaryFilePath
        val samples = repo.readPerformanceSamples(filePath)
        assertEquals("expected $n samples", n, samples.size)

        val entity = requireNotNull(fakeSessionDao.queryBySessionId(sessionId))
        val concentrationUpperBound = entity.startTs + 100L
        samples.forEachIndexed { i, s ->
            val absoluteTs = entity.startTs + s.tsDeltaMs
            assertTrue(
                "sample $i absoluteTs=$absoluteTs not in [${entity.startTs}, $concentrationUpperBound] " +
                    "(spec accepts ms-level concentration in startTest internal loop)",
                absoluteTs in entity.startTs..concentrationUpperBound,
            )
        }
    }

    /**
     * case D：持续写 absoluteTs 分布真实（deterministic 直接构造 writer，无 sleep / 合成时戳）。
     * 直接 BinaryTelemetryWriter.open(path, PERFORMANCE_TEST, startTs=20000) + 写 100 帧
     * tsDeltaMs = i × 40（精确间隔）→ readPerformanceSamples 验证：
     *   (a) 相邻 absoluteTs 间隔 == 40ms 精确
     *   (b) 全部样本 absoluteTs ∈ [20000, 20000 + 99 × 40]
     */
    @Test
    fun `case D - continuous write absoluteTs distribution is deterministic`() = runTest {
        val tempFile = File.createTempFile("perftest_continuous_write", ".bin", tempDir)
        val startTs = 20_000L
        val n = 100
        val writer = BinaryTelemetryWriter()
        writer.open(tempFile.absolutePath, TelemetrySessionType.PERFORMANCE_TEST, startTs)
        repeat(n) { i ->
            writer.write(
                TelemetrySample(
                    tsDeltaMs = i * 40L,
                    lat = 30.4897,
                    lon = 104.4326,
                    speedKmh = 80.0,
                    bearingDeg = 90.0,
                ),
            )
        }
        writer.close()

        val samples = repo.readPerformanceSamples(tempFile.absolutePath)
        assertEquals("expected $n samples", n, samples.size)

        // (a) 相邻间隔精确 40ms
        for (i in 1 until samples.size) {
            val deltaMs = samples[i].tsDeltaMs - samples[i - 1].tsDeltaMs
            assertEquals("sample $i interval should be precisely 40ms", 40L, deltaMs)
        }

        // (b) 全部样本 absoluteTs ∈ [startTs, startTs + (n-1)×40]
        samples.forEachIndexed { i, s ->
            val absoluteTs = startTs + s.tsDeltaMs
            assertTrue(
                "sample $i absoluteTs=$absoluteTs not in [$startTs, ${startTs + (n - 1) * 40L}]",
                absoluteTs in startTs..(startTs + (n - 1) * 40L),
            )
        }
    }

    /**
     * case E：源码 grep gate 时钟域单源自检（与 spec.md Scenario 5 对齐）。
     * 在 TestSessionViewModel.kt 内 grep：
     *   (1) `filteredData.timestamp - (anchorTs|activeTestStartTs)` 命中数 == 0（修复后归零）
     *   (2) `frame.timestamp - anchorTs` 命中数 == 0（修复后归零）
     *   正向 (3) `currentTimeMillis() - sessionStartTs` 命中数 == 3
     *     （A round bridgeGpsToLapTiming + 本 round startTest preTrigger + 本 round processFilteredData）
     */
    @Test
    fun `case E - clock domain single-source grep gate in TestSessionViewModel`() {
        val source = locateTestSessionViewModelFile().readText()

        val forbiddenPattern1 = Regex("""filteredData\.timestamp\s*-\s*(anchorTs|activeTestStartTs)""")
        val forbiddenPattern2 = Regex("""frame\.timestamp\s*-\s*anchorTs""")
        val matches1 = forbiddenPattern1.findAll(source).map { it.value }.toList()
        val matches2 = forbiddenPattern2.findAll(source).map { it.value }.toList()
        assertEquals(
            "禁止的协议时间减法 (filteredData.timestamp - anchorTs/activeTestStartTs)：${matches1.joinToString()}",
            0,
            matches1.size,
        )
        assertEquals(
            "禁止的协议时间减法 (frame.timestamp - anchorTs)：${matches2.joinToString()}",
            0,
            matches2.size,
        )

        val correctPattern = Regex("""System\.currentTimeMillis\(\)\s*-\s*sessionStartTs""")
        val correctMatches = correctPattern.findAll(source).map { it.value }.toList()
        assertEquals(
            "正向 anchor 同源公式应命中 3 处（A round bridgeGpsToLapTiming + 本 round startTest preTrigger 回填 + 本 round processFilteredData）；" +
                "实际命中：${correctMatches.size}",
            3,
            correctMatches.size,
        )
    }

    /**
     * case F：跨文件 grep gate 防漏改 + 防扫错路径假性绿（与 spec.md Scenario 6 对齐）。
     * 用 helper 上溯到 feature/test/src/main/java，递归 .kt 文件 grep
     * `tsDeltaMs = X.timestamp - Y` 形态：
     *   (a) 命中数 == 0（无任何文件用协议时间形态）
     *   (b) 扫到的 .kt 文件总数 ≥ 30（防扫错路径假性绿）
     * MUST 排除 src/test 子树。
     * 注（ble-device-memory round 修正，对齐 CrossingWallClockEscapeContractTest:48-50 已验证结论）：
     * mainJavaRoot 经 helper 上溯只走 feature/test/src/main/java 子树，其下不存在 .worktrees
     * （worktrees 在 repo root）；而 cwd 在 worktree 内跑时 mainJavaRoot 绝对路径本身含
     * /.worktrees/，按绝对路径排除会把所有文件全排除 → 触发下界断言假性红。故不排除 .worktrees。
     */
    @Test
    fun `case F - cross-file grep gate forbids timestamp subtraction across feature-test-main`() {
        val mainJavaRoot = locateFeatureTestMainJavaRoot()
        val ktFiles = mainJavaRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.absolutePath.contains("/src/test/") }
            .toList()

        assertTrue(
            "扫到的 .kt 文件数 ${ktFiles.size} < 30，可能扫错路径（防扫错假性绿）",
            ktFiles.size >= 30,
        )

        val forbiddenPattern = Regex("""tsDeltaMs\s*=\s*[a-zA-Z_]+\.timestamp\s*-""")
        val violations = ktFiles.flatMap { f ->
            forbiddenPattern.findAll(f.readText()).map { "${f.relativeTo(mainJavaRoot)}: ${it.value}" }
        }
        assertEquals(
            "禁止的 tsDeltaMs = X.timestamp - Y 形态命中（修复后应 0）：\n${violations.joinToString("\n")}",
            0,
            violations.size,
        )
    }

    /**
     * case G：源码 fallback 形态对齐（与 spec.md Scenario 7 对齐）。
     *
     * 形态 A（processFilteredData TestState.Running 分支）：
     *   - `val sessionStartTs = telemetryRepository.activeSessionStartTs` 命中（行号 P1）
     *   - 紧随 `if (sessionStartTs != null)`（行号 P2，P2 - P1 ∈ [0, 5]）
     *   - 后续窗口出现 `} else { FileLogger.e(TAG, "processFilteredData: missing activeSessionStartTs`（行号 P3，P3 - P2 ∈ [3, 15]）
     *   - **else 块之后**仍命中 `if (state.session.template.shouldEnd(filteredData.raw))`（行号 P4，P4 > P3 + 1）
     *     防 bare return regression（若有人写早返回 fallback，shouldEnd 漏判 finishTest 永不触发）
     *
     * 形态 B（startTest preTrigger 回填）同 pattern，含 `for (frame in lockedPreTriggerBuffer)`。
     *
     * 形态 C（本 round scope 收紧）：
     *   `FileLogger.e([^)]*(processFilteredData|startTest preTrigger backfill)[^)]*missing activeSessionStartTs`
     *   命中数 == 2（不算入 A round bridgeGpsToLapTiming line ~858）
     */
    @Test
    fun `case G - fallback shape alignment in TestSessionViewModel`() {
        val lines = locateTestSessionViewModelFile().readLines()
        val source = lines.joinToString("\n")

        /**
         * 在指定行号区间内找首个匹配 predicate 的行号；找不到返回 -1。
         * 为本 case 的形态 A/B 行号锚点 (P1..P4 / Q1..Q4) 服务。
         */
        fun firstLineIn(range: IntRange, predicate: (String) -> Boolean): Int {
            for (i in range) {
                if (i < lines.size && predicate(lines[i])) return i
            }
            return -1
        }

        /**
         * 解析函数体行号区间（从 `private suspend fun X` 到下一个
         * `private fun` / `private suspend fun` 之前），用于把 grep 锁定在函数体内
         * 避免跨函数串味命中。
         */
        fun functionRange(declaration: String): IntRange {
            val start = lines.indexOfFirst { it.contains(declaration) }
            require(start >= 0) { "$declaration not found" }
            val from = start + 1
            val tail = lines.drop(from)
            val nextOffset = tail.indexOfFirst { it.contains("private fun ") || it.contains("private suspend fun ") }
            val end = if (nextOffset >= 0) from + nextOffset else lines.size
            return start..end
        }

        // 形态 A (processFilteredData)
        val pfdRange = functionRange("private suspend fun processFilteredData")
        val p1 = firstLineIn(pfdRange) { it.contains("val sessionStartTs = telemetryRepository.activeSessionStartTs") }
        val p2 = firstLineIn(pfdRange) { it.contains("if (sessionStartTs != null)") }
        val p3 = firstLineIn(pfdRange) { it.contains("\"processFilteredData: missing activeSessionStartTs") }
        val p4 = firstLineIn(pfdRange) { it.contains("if (state.session.template.shouldEnd(filteredData.raw))") }

        assertTrue("processFilteredData 形态 A: P1 未命中", p1 >= 0)
        assertTrue("processFilteredData 形态 A: P2 未命中", p2 >= 0)
        assertTrue("processFilteredData 形态 A: P3 未命中", p3 >= 0)
        assertTrue("processFilteredData 形态 A: P4 (shouldEnd 检查) 未命中", p4 >= 0)
        assertTrue("processFilteredData 形态 A: P2 - P1 ∈ [0, 5] 不满足 (P1=$p1, P2=$p2)", p2 - p1 in 0..5)
        assertTrue("processFilteredData 形态 A: P3 - P2 ∈ [3, 15] 不满足 (P2=$p2, P3=$p3)", p3 - p2 in 3..15)
        assertTrue("processFilteredData 形态 A: P4 > P3 + 1 不满足 (P3=$p3, P4=$p4)——shouldEnd 必须出现在 else 块之后防 bare return regression", p4 > p3 + 1)

        // 形态 B (startTest preTrigger 回填)
        val stRange = functionRange("private suspend fun startTest")
        val q1 = firstLineIn(stRange) { it.contains("val sessionStartTs = telemetryRepository.activeSessionStartTs") }
        val q2 = firstLineIn(stRange) { it.contains("if (sessionStartTs != null)") }
        val q3 = firstLineIn(stRange) { it.contains("for (frame in lockedPreTriggerBuffer)") }
        val q4 = firstLineIn(stRange) { it.contains("\"startTest preTrigger backfill: missing activeSessionStartTs") }

        assertTrue("startTest 形态 B: q1 未命中", q1 >= 0)
        assertTrue("startTest 形态 B: q2 未命中", q2 >= 0)
        assertTrue("startTest 形态 B: q3 (for frame in lockedPreTriggerBuffer) 未命中", q3 >= 0)
        assertTrue("startTest 形态 B: q4 未命中", q4 >= 0)

        // 形态 C - 本 round scope 收紧的 FileLogger.e 命中数（排除 A round bridgeGpsToLapTiming）
        val scopedPattern = Regex(
            """FileLogger\.e\([\s\S]*?"(processFilteredData|startTest preTrigger backfill)[\s\S]*?missing activeSessionStartTs""",
        )
        val scopedMatches = scopedPattern.findAll(source).map { it.value }.toList()
        assertEquals(
            "本 round scope FileLogger.e 命中数应 == 2（processFilteredData + startTest preTrigger backfill 各 1）；实际：${scopedMatches.size}",
            2,
            scopedMatches.size,
        )
    }

    /**
     * case H：activeTestStartTs 字段两步赋值语义未被破坏。
     * 与生产实际两步赋值形态对齐：
     *   - 第一段：`val anchorTs = lockedPreTriggerBuffer.firstOrNull()?.timestamp ?: filteredData.timestamp` 在 startTest 内命中 1 次
     *   - 第二段：`activeTestStartTs = anchorTs` 紧随第一段（≤ 3 行内）
     *   - 第三段：`activeTestStartTs = null` 在 finishTest 内命中 1 次（清空保留）
     *
     * 防御本 round 误改 0-100 计时显示等下游派生（spec Scenario 8 锁死）。
     */
    @Test
    fun `case H - activeTestStartTs two-step assignment preserved`() {
        val lines = locateTestSessionViewModelFile().readLines()

        val firstStepIdx = lines.indexOfFirst {
            it.contains("val anchorTs = lockedPreTriggerBuffer.firstOrNull()?.timestamp ?: filteredData.timestamp")
        }
        assertTrue(
            "activeTestStartTs 第一段 (val anchorTs = lockedPreTriggerBuffer.firstOrNull()?.timestamp ?: filteredData.timestamp) 未命中",
            firstStepIdx >= 0,
        )

        val secondStepIdx = lines.indexOfFirst { it.trim() == "activeTestStartTs = anchorTs" }
        assertTrue("activeTestStartTs 第二段 (activeTestStartTs = anchorTs) 未命中", secondStepIdx >= 0)

        assertTrue(
            "活跃 anchor 两步赋值行号距离 secondStepIdx - firstStepIdx ∈ [0, 3] 不满足 (first=$firstStepIdx, second=$secondStepIdx)",
            secondStepIdx - firstStepIdx in 0..3,
        )

        val finishTestStart = lines.indexOfFirst { it.contains("private fun finishTest") }
        assertTrue("finishTest function not found", finishTestStart >= 0)
        val finishTestEnd = run {
            val from = finishTestStart + 1
            val tail = lines.drop(from)
            val nextOffset = tail.indexOfFirst { it.contains("private fun ") || it.contains("private suspend fun ") }
            if (nextOffset >= 0) from + nextOffset else lines.size
        }
        var nullIdx = -1
        for (i in finishTestStart..finishTestEnd) {
            if (i < lines.size && lines[i].trim() == "activeTestStartTs = null") {
                nullIdx = i
                break
            }
        }
        assertTrue("finishTest 内 activeTestStartTs = null 清空未命中", nullIdx >= 0)
    }

    /**
     * 跨模块定位 TestSessionViewModel.kt 源文件（与 A round case H 同 helper 模式）。
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

    /**
     * 解析 feature/test/src/main/java root（用于 case F 跨文件 grep gate）。
     * 从 ViewModel 文件上溯 6 次 .parentFile：viewmodel → test → feature → blazepush → com → java
     */
    private fun locateFeatureTestMainJavaRoot(): File {
        // 从 TestSessionViewModel.kt 上溯 6 次到达 feature/test/src/main/java：
        //   file -> viewmodel(1) -> test(2) -> feature(3) -> blazepush(4) -> com(5) -> java(6)
        var dir: File = locateTestSessionViewModelFile()
        repeat(6) { dir = requireNotNull(dir.parentFile) { "parentFile chain hit filesystem root" } }
        require(dir.absolutePath.endsWith("feature/test/src/main/java")) {
            "expected feature/test/src/main/java root, got ${dir.absolutePath}"
        }
        return dir
    }

    // --- Fake DAO 实现（与 A round BinaryLapTelemetryRoundTripTest 同款，保持一致以便维护）---

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

        override suspend fun deleteCrossingsBySessionId(sessionId: String) {
            crossings.removeIf { it.sessionId == sessionId }
        }
    }

    @Suppress("unused")
    private companion object
}