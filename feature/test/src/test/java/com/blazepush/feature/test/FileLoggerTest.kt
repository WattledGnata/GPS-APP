// @IgnoreFormatCheck
// 理由：本文件由 change fix-file-logger-and-engine-coord-hygiene（战役 F A18+A39）
//       新建，JUnit4 测试类遵循 Gherkin-style snake_case 命名（method-name `_25Hz`
//       的 `_2` 违反 `^[a-z][a-zA-Z0-9]*(_[a-z]...)*$` 但这是测试可读性的语义
//       承载），@Before/@After 的 setup/tearDown 无须 doc comment（测试框架
//       契约清晰）。评审方 2026-04-24 战役 G B 方案纪律批准 test 文件 ignore。
package com.blazepush.feature.test

import android.content.Context
import android.util.Log
import com.blazepush.feature.test.FileLogger.LogLevel
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import java.io.File

/**
 * 战役 F A18 + A39 FileLoggerTest：14 条 Scenario 覆盖
 *
 * R1 × 5（业务非阻塞 + IOException 吞 + DROP_OLDEST + graceful handoff + flushForTest 语义）
 * R2 × 2（rotate 到 .1 / 两次 rotate 覆盖最老）
 * R3 × 3（默认 DEBUG verbose 不写 / setLevel VERBOSE 生效 / DEBUG 正常写）
 * R4 × 4（engine 源码 v()+%.3f / bridge 源码 v()+%.3f / DEBUG 高频零写 / 低频不变）
 *
 * 测试策略：
 * - JUnit4 TemporaryFolder 隔离文件（不用 JUnit5 @TempDir，模块无 Jupiter）
 * - Mockito doReturn...when mock Context.filesDir（不用 whenever，无 mockito-kotlin）
 * - Mockito.mockStatic(Log::class.java) 隔离 android.util.Log
 * - runTest { FileLogger.flushForTest() } 替代 delay(FLUSH_INTERVAL_MS) 做 deterministic drain
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FileLoggerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var tempContext: Context
    private lateinit var filesDir: File
    private lateinit var logMock: MockedStatic<Log>

    @Before
    fun setup() {
        FileLogger.resetForTest()
        filesDir = tempFolder.newFolder("filesDir")
        tempContext = mock(Context::class.java)
        doReturn(filesDir).`when`(tempContext).filesDir
        logMock = Mockito.mockStatic(Log::class.java)
        FileLogger.init(tempContext)
    }

    @After
    fun tearDown() {
        FileLogger.resetForTest()
        logMock.close()
    }

    // ==================== R1 业务线程非阻塞 + IOException + DROP_OLDEST ====================

    /**
     * R1 Scenario 1：1000 次 d() 调用累计 < 100ms（平均 < 0.1ms/次），硬区分 v1/v2
     */
    @Test
    fun d_calledAt25HzForOneSecond_callerCompletedUnder100ms() {
        val elapsed = measureNanosOf {
            repeat(1000) { i -> FileLogger.d("TAG", "perf-$i") }
        }
        val elapsedMs = elapsed / 1_000_000.0
        assertTrue(
            "1000 次 d() 累计 ${elapsedMs}ms 应 < 100ms（平均 < 0.1ms/次）。v1 每次同步 I/O 1-5ms 必 FAIL",
            elapsedMs < 100,
        )
    }

    /**
     * R1 Scenario 2：FileWriter 抛 IOException 时业务不感知（通过设只读 filesDir 模拟）
     */
    @Test
    fun d_whenFileWriteThrows_doesNotPropagate() = runTest {
        // 让 currentLogFile 变成只读：先写一次让 FileLogger 建文件，然后设只读
        FileLogger.d("TAG", "baseline")
        FileLogger.flushForTest()
        val logFile = File(filesDir, "debug_log.txt")
        assertTrue(logFile.setReadOnly())

        // 业务调用不抛
        FileLogger.d("TAG", "should-fail-write")
        FileLogger.flushForTest()
        FileLogger.d("TAG", "second-call-still-ok")
        FileLogger.flushForTest()

        // consumer 未退出循环（resetReadable + 下次 flush 成功能写入）
        logFile.setWritable(true)
        FileLogger.d("TAG", "after-recovery")
        FileLogger.flushForTest()
        assertTrue(
            "恢复可写后，后续日志应能写入",
            logFile.readText().contains("after-recovery"),
        )
    }

    /**
     * R1 Scenario 3：channel 满时 DROP_OLDEST（v3 P2-3：通过公开 API，不反射 private sealed）
     */
    @Test
    fun drop_oldest_whenChannelFull_doesNotBlock() = runTest {
        // 反射 cancel flushJob 暂停 consumer
        val flushJobField = FileLogger::class.java.getDeclaredField("flushJob")
        flushJobField.isAccessible = true
        val oldJob = flushJobField.get(FileLogger) as Job
        runBlocking { oldJob.cancelAndJoin() }

        // 通过公开 API 灌 2000 次（超过 capacity 1024）
        val elapsed = measureNanosOf {
            repeat(2000) { i -> FileLogger.d("TAG", "drop-$i") }
        }
        val elapsedMs = elapsed / 1_000_000.0
        assertTrue("2000 次 d() 应全部非阻塞返回，累计 < 200ms（实测 ${elapsedMs}ms）", elapsedMs < 200)

        // 恢复 consumer：通过 resetForTest + init 重启（保留 logChannel 跨 init 不重建）
        // 但 resetForTest 会 cancel 当前 flushJob 并置 null；init 重启新 flushJob，
        // 剩余 1024 条 Line（drop-976..drop-1999）留在 channel 里被新 consumer 消费
        FileLogger.resetForTest()
        filesDir.deleteRecursively()
        filesDir = tempFolder.newFolder("filesDir2")
        doReturn(filesDir).`when`(tempContext).filesDir
        FileLogger.init(tempContext)
        FileLogger.flushForTest()

        val logFile = File(filesDir, "debug_log.txt")
        val content = logFile.readText()
        // P2 finding 3（2026-04-24 codex code-review）：按行精确匹配代替
        // content.contains("drop-0 ") —— 原断言用空格结尾，而实际日志行
        // 是 "... drop-0\n"，drop-0 被误写入时也会通过断言
        val lines = content.lineSequence().toList()
        assertFalse(
            "最老消息 drop-0 应被 DROP_OLDEST 丢掉（按行 endsWith 匹配，避免 drop-01/099 误通过）",
            lines.any { it.endsWith("drop-0") },
        )
        assertTrue(
            "最新消息 drop-1999 应保留在 channel，被新 consumer 消费落盘",
            lines.any { it.endsWith("drop-1999") },
        )
    }

    /**
     * R1 Scenario 4：graceful handoff（硬区分 v1/v2）—— init 返回后旧 batch 必已同步落盘
     */
    @Test
    fun init_secondCall_oldBatchIsFlushedBeforeCancelViaFinally() = runTest {
        // 灌 32 条（不到 FLUSH_BATCH_SIZE=64）
        repeat(32) { i -> FileLogger.d("TAG", "handoff-msg-$i") }

        // 第二次 init 触发 graceful handoff（runBlocking cancelAndJoin 同步等旧 finally 落盘）
        // 强契约：init 返回后，旧 consumer 本地 batch 已落盘；仍在 channel 里的 Line
        // 由新 flushJob 继续消费（channel 跨 init 长存）。这里再 flushForTest 等新
        // consumer 清空 channel，断言 "32 条一条不丢"
        FileLogger.init(tempContext)
        FileLogger.flushForTest()

        val logFile = File(filesDir, "debug_log.txt")
        val content = logFile.readText()
        repeat(32) { i ->
            assertTrue(
                "graceful handoff（Shutdown FIFO 排空 + 新 consumer 接棒）保证 handoff-msg-$i 必在文件里（v1 会静默丢）",
                content.contains("handoff-msg-$i"),
            )
        }
    }

    /**
     * R1 Scenario 5：flushForTest 语义契约 —— 调用前入队的 Line 必落盘
     */
    @Test
    fun flushForTest_drainsEverythingCurrentlyEnqueued() = runTest {
        repeat(5) { i -> FileLogger.d("TAG", "enqueue-$i") }
        FileLogger.flushForTest()

        val logFile = File(filesDir, "debug_log.txt")
        val content = logFile.readText()
        repeat(5) { i ->
            assertTrue("flushForTest 返回后 enqueue-$i 必落盘", content.contains("enqueue-$i"))
        }
    }

    // ==================== R2 rotation ====================

    /**
     * R2 Scenario 1：达到 MAX_FILE_BYTES 时 rotate 到 .1
     */
    @Test
    fun rotation_whenFileReachesMaxFileBytes_renamesToDotOne() = runTest {
        val logFile = File(filesDir, "debug_log.txt")
        val rotatedFile = File(filesDir, "debug_log.txt.1")
        // 预写入 5_242_880 字节（= MAX_FILE_BYTES）
        logFile.writeBytes(ByteArray(5 * 1024 * 1024) { 0x41 })  // 'A' × 5MB

        // 触发一次 flush（通过 d() + flushForTest）
        FileLogger.d("TAG", "trigger-rotate")
        FileLogger.flushForTest()

        assertTrue("rotation 后 .1 应存在", rotatedFile.exists())
        assertTrue("rotated .1 size ≈ MAX_FILE_BYTES", rotatedFile.length() >= 5 * 1024 * 1024)
        assertTrue(
            "新 debug_log.txt size 远小于 MAX_FILE_BYTES（仅含 trigger-rotate 一条）",
            logFile.length() < 1024,
        )
    }

    /**
     * R2 Scenario 2：第二次 rotation 时 .1 被覆盖（旧 rotated 被丢）
     */
    @Test
    fun rotation_twice_oldestOneDotOneOverwritten() = runTest {
        val logFile = File(filesDir, "debug_log.txt")
        val rotatedFile = File(filesDir, "debug_log.txt.1")

        // 第一次 rotation：预写 5MB 含特征 "BATCH_A"（"BATCH_A_" 8 字节 × (5MB/8) = 5MB）
        logFile.writeBytes(("BATCH_A_".repeat(5 * 1024 * 1024 / 8)).toByteArray())
        FileLogger.d("TAG", "trigger-rotate-1")
        FileLogger.flushForTest()
        assertTrue(".1 含 BATCH_A", rotatedFile.readText().contains("BATCH_A"))

        // 第二次 rotation：再写 5MB 含特征 "BATCH_B"
        logFile.appendBytes(("BATCH_B_".repeat(5 * 1024 * 1024 / 8)).toByteArray())
        FileLogger.d("TAG", "trigger-rotate-2")
        FileLogger.flushForTest()

        // .1 现在应是 BATCH_B（覆盖 BATCH_A）
        val rotatedContent = rotatedFile.readText()
        assertFalse(".1 不应再含 BATCH_A（被覆盖）", rotatedContent.contains("BATCH_A"))
        assertTrue(".1 应含 BATCH_B", rotatedContent.contains("BATCH_B"))
    }

    // ==================== R3 级别控制 ====================

    /**
     * R3 Scenario 1：默认 DEBUG verbose 日志不写入（硬区分 v1/v2）
     */
    @Test
    fun setLevel_debug_verboseLogsAreDropped() = runTest {
        // 默认 LogLevel.DEBUG
        repeat(100) { i -> FileLogger.v("TAG", "verbose-msg-$i") }
        FileLogger.flushForTest()

        val logFile = File(filesDir, "debug_log.txt")
        val content = logFile.readText()
        repeat(100) { i ->
            assertFalse("DEBUG 级别下 verbose-msg-$i 不应写入", content.contains("verbose-msg-$i"))
        }
    }

    /**
     * R3 Scenario 2：setLevel(VERBOSE) 后 v() 正常写入
     */
    @Test
    fun setLevel_verbose_highFrequencyLogsAreWritten() = runTest {
        FileLogger.setLevel(LogLevel.VERBOSE)
        FileLogger.v("TAG", "verbose-after-setLevel")
        FileLogger.flushForTest()

        val logFile = File(filesDir, "debug_log.txt")
        assertTrue(
            "setLevel(VERBOSE) 后 v() 应写入",
            logFile.readText().contains("verbose-after-setLevel"),
        )
    }

    /**
     * R3 Scenario 3：默认 DEBUG 下 d() 正常写入（回归保护）
     */
    @Test
    fun level_defaultIsDebug_regularDebugWritten() = runTest {
        FileLogger.d("TAG", "normal-debug")
        FileLogger.flushForTest()

        val logFile = File(filesDir, "debug_log.txt")
        assertTrue(
            "默认 DEBUG 级别下 d() 应写入",
            logFile.readText().contains("normal-debug"),
        )
    }

    // ==================== R4 高频 call site 分级 + 坐标精度 ====================

    /**
     * R4 Scenario 1：LapTimingEngine.kt:70 源码用 v() + 坐标 %.3f（源码断言）
     */
    @Test
    fun engine_detectorLog_sourceUsesVerboseAndThreeDecimalFormat() {
        val source = File(
            "src/main/java/com/blazepush/feature/test/usecase/LapTimingEngine.kt",
        ).readText()
        val detectorLogAnchor = source.indexOf("targetGate=\${track.startFinishGate.id}")
        assertTrue("LapTimingEngine.kt 应含 detector 日志", detectorLogAnchor > 0)
        // 往前找最近的 FileLogger 调用
        val callStart = source.lastIndexOf("FileLogger.", detectorLogAnchor)
        assertTrue("detector 日志前应有 FileLogger 调用", callStart > 0)
        val callStmt = source.substring(callStart, detectorLogAnchor + 500)
        assertTrue(
            "detector 日志应使用 v() 而非 d()（v1 FAIL / v2 PASS）",
            callStmt.startsWith("FileLogger.v("),
        )
        assertTrue(
            "detector 日志坐标应 %.3f 格式化（v1 直接 \${latitude} / v2 \"%.3f\".format）",
            callStmt.contains("\"%.3f\".format(previousSample.latitude)") ||
                callStmt.contains("\"%.3f\".format(previousSample.longitude)"),
        )
    }

    /**
     * R4 Scenario 2：TestSessionViewModel.kt:335 源码用 v() + 坐标 %.3f（源码断言）
     */
    @Test
    fun bridge_gpsLog_sourceUsesVerboseAndThreeDecimalFormat() {
        val source = File(
            "src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt",
        ).readText()
        val bridgeLogAnchor = source.indexOf("bridgeGpsToLapTiming: track=")
        assertTrue("TestSessionViewModel.kt 应含 bridge 日志", bridgeLogAnchor > 0)
        val callStart = source.lastIndexOf("FileLogger.", bridgeLogAnchor)
        assertTrue("bridge 日志前应有 FileLogger 调用", callStart > 0)
        val callStmt = source.substring(callStart, bridgeLogAnchor + 500)
        assertTrue("bridge 日志应使用 v()", callStmt.startsWith("FileLogger.v("))
        assertTrue(
            "bridge 日志 lat 应 %.3f 格式化",
            callStmt.contains("\"%.3f\".format(gpsData.latitude)"),
        )
        assertTrue(
            "bridge 日志 lon 应 %.3f 格式化",
            callStmt.contains("\"%.3f\".format(gpsData.longitude)"),
        )
    }

    /**
     * R4 Scenario 3：默认 DEBUG 下 25Hz 高频日志零写入（端到端回归）
     */
    @Test
    fun defaultDebugLevel_25HzHighFrequencyLogs_noWriteToFile() = runTest {
        // 模拟 25 帧 × 3 条高频 = 75 条 v() 调用（代表 1 秒 25Hz 采集）
        repeat(25) { frame ->
            FileLogger.v("ENGINE", "targetGate=..., prev=(30.583,104.067,ts=$frame), ...")
            FileLogger.v("VM", "bridgeGpsToLapTiming: ..., lat=30.583, lon=104.067, ...")
            FileLogger.v("VM", "lapTimingResult: status=Recording, frame=$frame")
        }
        FileLogger.flushForTest()

        val logFile = File(filesDir, "debug_log.txt")
        val content = logFile.readText()
        assertFalse("默认 DEBUG 下 targetGate verbose 不应写入", content.contains("targetGate="))
        assertFalse(
            "默认 DEBUG 下 bridgeGpsToLapTiming verbose 不应写入",
            content.contains("bridgeGpsToLapTiming: ..."),
        )
    }

    /**
     * R4 Scenario 4：低频异常日志保持原 d() 和原精度（回归保护，源码断言）
     */
    @Test
    fun lowFrequencyLogs_sourceKeepsDebugLevelAndOriginalPrecision() {
        val engineSource = File(
            "src/main/java/com/blazepush/feature/test/usecase/LapTimingEngine.kt",
        ).readText()
        // LapTimingEngine.kt:61 附近 ts 回跳 drop（异常路径）应保持 FileLogger.d(
        val tsRegressionAnchor = engineSource.indexOf("processSample: ts regression")
        assertTrue("engine ts regression 日志存在", tsRegressionAnchor > 0)
        val callStart = engineSource.lastIndexOf("FileLogger.", tsRegressionAnchor)
        val callStmt = engineSource.substring(callStart, tsRegressionAnchor)
        assertTrue(
            "ts 回跳（异常路径）应保持 d()，不改为 v()（低频回归保护）",
            callStmt.startsWith("FileLogger.d("),
        )
    }

    // ==================== Review 追加：P1 并发 smoke + P2 finding 2 full-buffer 契约 ====================

    /**
     * P1 finding 1（2026-04-24 codex code-review）并发 smoke：
     *   多协程并发 d() 不抛异常，且 flushForTest 后所有日志都落盘。
     *   v1 `dateFormat: SimpleDateFormat` 单例在并发下概率性抛
     *   ArrayIndexOutOfBoundsException / NumberFormatException；
     *   v2 ThreadLocal<SimpleDateFormat> 每线程独享，0 异常。
     */
    @Test
    fun d_calledConcurrentlyFromMultipleCoroutines_noThreadingExceptions() = runTest {
        val coroutineCount = 16
        val callsPerCoroutine = 100
        // 用真实 IO dispatcher 跑并发，避免 runTest 的 TestDispatcher 串行化
        runBlocking(Dispatchers.IO) {
            val jobs: List<Deferred<Unit>> = (0 until coroutineCount).map { coIdx ->
                async {
                    repeat(callsPerCoroutine) { i ->
                        FileLogger.d("T$coIdx", "concurrent-$coIdx-$i")
                    }
                }
            }
            jobs.forEach { it.await() }
        }
        FileLogger.flushForTest()

        val logFile = File(filesDir, "debug_log.txt")
        val content = logFile.readText()
        // 抽样断言若干协程的若干消息都在（不做全量 N² 检查，避免测试慢）
        listOf(0 to 0, 0 to 99, 7 to 42, 15 to 0, 15 to 99).forEach { (coIdx, i) ->
            assertTrue(
                "并发写入应无线程安全问题 / 消息丢失：concurrent-$coIdx-$i 应在文件里",
                content.contains("concurrent-$coIdx-$i"),
            )
        }
    }

    /**
     * P2 finding 2（2026-04-24 codex code-review）full-buffer 降级契约：
     *   直接验证 `Channel(capacity=1024, DROP_OLDEST)` 的 `send` 语义 —— 这是
     *   FileLogger 的 Flush / Shutdown 命令在 full-buffer 下的底层机制：
     *   channel 满时 send 不阻塞、不抛，按 DROP_OLDEST 挤掉最老 1 项，
     *   appendToTail 新项。核销方基于此断言确认降级契约在底层正确成立。
     *
     *   此测试刻意用本地 Channel<String> 独立验证，避免与 FileLogger 单例
     *   状态（currentLogFile / initialized / flushJob 生命周期）耦合 ——
     *   finding 2 关心的是 Channel DROP_OLDEST 语义，不是 FileLogger 集成。
     */
    @Test
    fun send_onFullDropOldestChannel_dropsOldestNotNewAndDoesNotSuspend() = runTest {
        val testChannel = kotlinx.coroutines.channels.Channel<String>(
            capacity = 1024,
            onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
        )
        // 灌 1024 条到满
        repeat(1024) { i ->
            val result = testChannel.trySend("line-$i")
            assertTrue("trySend 在未满时应成功", result.isSuccess)
        }
        // 再 send 一条模拟 Flush/Shutdown 控制命令。send 在 DROP_OLDEST 下
        // 不应挂起（withTimeout 用 100ms 兜底，验证"不阻塞"）
        val sendResult = withTimeoutOrNull(100L) {
            testChannel.send("CONTROL_CMD")
            "ok"
        }
        assertEquals("send 在 full DROP_OLDEST channel 上应立即返回", "ok", sendResult)

        // 排空：验证 line-0 被挤掉、line-1..line-1023 + CONTROL_CMD 都在
        val received = mutableListOf<String>()
        repeat(1024) { received.add(testChannel.tryReceive().getOrThrow()) }
        assertEquals("队尾应是控制命令", "CONTROL_CMD", received.last())
        assertFalse(
            "最老 line-0 应被 DROP_OLDEST 挤掉（控制命令优先于最老 Line）",
            received.contains("line-0"),
        )
        assertTrue("line-1 作为非最老应保留", received.contains("line-1"))
        assertTrue("line-1023 作为最新原始 Line 应保留", received.contains("line-1023"))
        testChannel.close()
    }

    // ==================== 工具方法 ====================

    private inline fun measureNanosOf(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return System.nanoTime() - start
    }
}
