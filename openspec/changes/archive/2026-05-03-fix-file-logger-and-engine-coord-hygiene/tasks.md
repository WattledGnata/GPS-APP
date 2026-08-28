# 实施任务（依赖顺序）

本战役按 4 个 Requirement 组织，严格依赖顺序：

1. **R1 FileLogger 异步化**：重写 `FileLogger.kt`（channel + IO flush + 异常兜底）
2. **R2 rotation**：同文件内追加 5MB × 2 轮换逻辑（和 R1 同批，避免 FileLogger 两次改）
3. **R3 级别控制**：同文件内追加 `LogLevel` + `setLevel()` + 入队前过滤
4. **R4 call site 分级 + 坐标降级**：改 `LapTimingEngine.kt:70` + `TestSessionViewModel.kt:335,373`
5. 合流门槛 + A18 / A39 各自迁 🟢（两条独立条目）

R1-R3 改同一文件 `FileLogger.kt`，一起重写更经济（避免三轮合并）。R4 是调用侧改造，依赖 R3 已提供 `v()` API。

---

## 1. R1 + R2 + R3 FileLogger 重写

- [x] 1.1 **预先 grep 所有 FileLogger 调用**确认迁移范围：
    ```bash
    rg -n "FileLogger\." feature/test/src/main core/bluetooth/src/main core/domain/src/main
    ```
    预期 12 处命中：
    - `LapTimingEngine.kt` 3 处（第 61、70、232 行附近）
    - `TestSessionViewModel.kt` 9 处（第 159、301-307、326、335、357、373 行附近）
    本 change 只对**高频 3 条**（engine:70 + VM:335 + VM:373）改为 `v()` + 坐标降级，
    其余 9 条保持 `d()` 原精度（见 R4 分级表）。
- [x] 1.2 **重写 FileLogger.kt** v5：整个文件替换为异步版本（v4 基础上
      实施期发现 `cancelAndJoin` 与 `Channel.receive()` 交付竞态，改为
      `LogCommand.Shutdown` FIFO 排空方案）：

    **关键决策（v5 修订）**：

    1. **P1-1 强契约 graceful handoff（v5 Shutdown FIFO 方案）**：`init` 头部
       `runBlocking(Dispatchers.IO) { logChannel.send(Shutdown); oldJob.join() }`
       ——向 channel FIFO 末尾发送 `LogCommand.Shutdown`，consumer 按 FIFO
       消费完所有 pre-init `Line` 后写 batch，再收到 Shutdown 写剩余 batch
       并 `return` 退出循环。**避免 cancelAndJoin 的交付竞态**：
       `Channel.receive()` 可能已从 channel 取出 item 但 cancel 在 `batch.add`
       前介入，没有 `onUndeliveredElement` 时该 item 会静默丢失（实测
       `init_secondCall` 测试偶现 msg-21 / msg-30 等中间位置丢失）
    2. **P1-1 initialized 标志**：仅**首次** `init` 清空日志文件（开新
       session）；重复 `init` **不**清空（保留旧 batch handoff 后的内容，
       不让 `writeText("")` 把它擦掉）
    3. **P2-1 `Channel<LogCommand>`**：channel 类型从 `Channel<String>` 升级
       为 `Channel<LogCommand>`，其中 `LogCommand` 是 sealed class，含
       `Line(content)` + `Flush(ack: CompletableDeferred<Unit>)` +
       `Shutdown`（v5 新增）。consumer 收到 Flush 先落盘 batch 再
       `ack.complete(Unit)`；`flushForTest` 发送 Flush + `await()` 得到
       确定性同步。收到 Shutdown 落盘 batch 后 `return` 优雅退出
    4. **v5 isActive 保护**：`init` 与 `resetForTest` 的 Shutdown 发送均
       包裹 `if (oldJob.isActive)` 守卫——若旧 job 已被外部 cancel
       （仅测试路径 drop_oldest 场景通过反射 cancel），跳过 Shutdown 发送
       避免 Shutdown 遗留在 channel 污染下一个 flushJob

    ```kotlin
    object FileLogger {
        enum class LogLevel { VERBOSE, DEBUG, INFO, WARN, ERROR }

        private const val FLUSH_BATCH_SIZE = 64
        private const val FLUSH_INTERVAL_MS = 200L
        private const val CHANNEL_CAPACITY = 1024
        // P2-3 v3：阈值单一真理源，spec + 代码 + 测试引用同一常量
        private const val MAX_FILE_BYTES = 5L * 1024 * 1024

        /**
         * v5：channel payload sealed class
         * - Line: 业务日志行
         * - Flush: 测试 seam，consumer 写 batch 后 ack
         * - Shutdown: graceful handoff，consumer FIFO 收到后写 batch + 退出循环
         *   (取代 cancelAndJoin：避免 Channel.receive() 与 cancel 的交付竞态)
         */
        private sealed class LogCommand {
            data class Line(val content: String) : LogCommand()
            data class Flush(val ack: CompletableDeferred<Unit>) : LogCommand()
            object Shutdown : LogCommand()
        }

        private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        // logChannel 跨 init 长存，保证"已 trySend 但未 receive 的 Line"由新 flush 协程继续消费
        private val logChannel = Channel<LogCommand>(
            capacity = CHANNEL_CAPACITY,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        private var flushJob: Job? = null

        @Volatile
        private var currentLevel: LogLevel = LogLevel.DEBUG
        val isVerboseEnabled: Boolean get() = currentLevel <= LogLevel.VERBOSE

        @Volatile
        private var initialized = false  // P1-1 v3：仅首次 init 清空文件

        private var currentLogFile: File? = null
        private var rotatedLogFile: File? = null
        private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

        fun init(context: Context) {
            // v5 graceful handoff 顺序（Shutdown FIFO 强契约，零丢失）：
            // 1. 向 channel FIFO 末尾发 Shutdown 命令，同步等旧 flush job 消费
            //    完所有 pre-init Line 后写 batch 并 return 退出循环
            //    （isActive 守卫：若旧 job 已被外部 cancel，跳过 Shutdown
            //    避免留在 channel 污染新 flushJob）
            // 2. 替换 currentLogFile / rotatedLogFile 引用（旧 consumer 已退出，
            //    无写竞态）
            // 3. 首次 init 清空，重复 init 不清空
            // 4. 启新 flush job 消费 logChannel 后续 Line
            flushJob?.let { oldJob ->
                runBlocking(Dispatchers.IO) {
                    if (oldJob.isActive) {
                        logChannel.send(LogCommand.Shutdown)
                    }
                    oldJob.join()
                }
            }
            currentLogFile = File(context.filesDir, "debug_log.txt")
            rotatedLogFile = File(context.filesDir, "debug_log.txt.1")
            if (!initialized) {
                currentLogFile?.writeText("")
                initialized = true
            }
            flushJob = scope.launch { consumeAndFlushLoop() }
            d("FileLogger", "===== 日志开始 =====")
        }

        fun setLevel(level: LogLevel) {
            currentLevel = level
            d("FileLogger", "level changed to $level")
        }

        fun d(tag: String, message: String) = enqueue(LogLevel.DEBUG, tag, message)
        fun v(tag: String, message: String) = enqueue(LogLevel.VERBOSE, tag, message)
        fun e(tag: String, message: String, throwable: Throwable? = null) =
            enqueue(LogLevel.ERROR, tag, message + (throwable?.let { " - ${it.message}" } ?: ""))

        private fun enqueue(level: LogLevel, tag: String, message: String) {
            if (level < currentLevel) return
            val line = formatLine(level, tag, message)
            logChannel.trySend(LogCommand.Line(line))
        }

        private fun formatLine(level: LogLevel, tag: String, message: String): String {
            val timestamp = dateFormat.format(Date())
            val levelChar = when (level) {
                LogLevel.VERBOSE -> "V"
                LogLevel.DEBUG -> "D"
                LogLevel.INFO -> "I"
                LogLevel.WARN -> "W"
                LogLevel.ERROR -> "E"
            }
            return "$timestamp $levelChar/$tag: $message\n"
        }

        private suspend fun consumeAndFlushLoop() {
            val batch = mutableListOf<String>()
            var lastFlush = System.currentTimeMillis()
            try {
                while (true) {
                    val cmd = withTimeoutOrNull(FLUSH_INTERVAL_MS) {
                        logChannel.receive()
                    }
                    when (cmd) {
                        is LogCommand.Line -> batch.add(cmd.content)
                        is LogCommand.Flush -> {
                            // 收到 Flush 先落盘当前 batch，再 ack
                            // 保证"调用 flushForTest 前入队的 Line 已落盘"
                            if (batch.isNotEmpty()) {
                                writeBatchSafe(batch)
                                batch.clear()
                                lastFlush = System.currentTimeMillis()
                            }
                            cmd.ack.complete(Unit)
                        }
                        is LogCommand.Shutdown -> {
                            // graceful exit：Shutdown 是 FIFO 末尾，之前所有 Line
                            // 都已 batched。写 batch 后 return 退出循环，
                            // flushJob 自然完成
                            if (batch.isNotEmpty()) {
                                writeBatchSafe(batch)
                                batch.clear()
                            }
                            return
                        }
                        null -> { /* timeout, fall through */ }
                    }
                    val now = System.currentTimeMillis()
                    val timeUp = (now - lastFlush) >= FLUSH_INTERVAL_MS
                    val sizeUp = batch.size >= FLUSH_BATCH_SIZE
                    if (batch.isNotEmpty() && (timeUp || sizeUp)) {
                        writeBatchSafe(batch)
                        batch.clear()
                        lastFlush = now
                    }
                }
            } finally {
                // scope.cancel 安全网（正常流程走 Shutdown 分支，此 finally 不触发）
                if (batch.isNotEmpty()) {
                    writeBatchSafe(batch)
                    batch.clear()
                }
            }
        }

        private fun writeBatchSafe(lines: List<String>) {
            try {
                currentLogFile?.let { file ->
                    FileWriter(file, true).use { writer ->
                        lines.forEach { writer.append(it) }
                    }
                }
                checkAndRotate()
            } catch (t: IOException) {
                android.util.Log.e("FileLogger", "flush failed", t)
            }
        }

        private fun checkAndRotate() {
            val cur = currentLogFile ?: return
            if (cur.length() < MAX_FILE_BYTES) return
            rotatedLogFile?.let { rotated ->
                if (rotated.exists()) rotated.delete()
                cur.renameTo(rotated)
            }
            cur.createNewFile()
        }

        /**
         * P2-1 v3 测试 seam —— 确定性等待已入队 Line 全部落盘。
         *
         * 语义：向 channel 发送 `LogCommand.Flush(ack)`，consumer 收到后先写
         * 当前 batch 再 ack.complete；`flushForTest` 在 ack.await() 处挂起直到
         * consumer 完成。返回后保证 "flushForTest 调用前所有 trySend 成功的
         * Line 已落盘到 currentLogFile"。
         *
         * 不保证 "flushForTest 返回后 / await 期间后续 trySend 的 Line" 已落盘。
         *
         * 仅 internal + @VisibleForTesting 暴露，生产代码不得调用。
         */
        @androidx.annotation.VisibleForTesting
        internal suspend fun flushForTest() {
            val ack = CompletableDeferred<Unit>()
            // send（非 trySend）确保 Flush 命令本身不被 DROP_OLDEST 丢掉
            logChannel.send(LogCommand.Flush(ack))
            ack.await()
        }

        /**
         * 仅测试使用：reset initialized 标志 + 清空 flushJob 引用。
         * 生产代码不得调用。`@Before` 里可用它让每个测试从首次 init 状态开始。
         */
        @androidx.annotation.VisibleForTesting
        internal fun resetForTest() {
            flushJob?.let { oldJob ->
                runBlocking(Dispatchers.IO) {
                    if (oldJob.isActive) {
                        logChannel.send(LogCommand.Shutdown)
                    }
                    oldJob.join()
                }
            }
            flushJob = null
            initialized = false
            currentLogFile = null
            rotatedLogFile = null
            currentLevel = LogLevel.DEBUG  // v5：测试隔离，避免 setLevel(VERBOSE) 污染下一测试
        }
    }
    ```
- [x] 1.3 **import 补齐 v5**：新加（v5 移除 `cancelAndJoin` + `isActive`
      未使用 import，因 Shutdown FIFO 方案不再 cancel 协程）：
    ```kotlin
    import kotlinx.coroutines.CompletableDeferred
    import kotlinx.coroutines.CoroutineScope
    import kotlinx.coroutines.Dispatchers
    import kotlinx.coroutines.Job
    import kotlinx.coroutines.SupervisorJob
    import kotlinx.coroutines.channels.BufferOverflow
    import kotlinx.coroutines.channels.Channel
    import kotlinx.coroutines.launch
    import kotlinx.coroutines.runBlocking
    import kotlinx.coroutines.withTimeoutOrNull
    import java.io.IOException
    ```
- [x] 1.4 **门槛自检**：`./gradlew :feature:test:compileDebugKotlin` BUILD
      SUCCESSFUL（无 unresolved reference）。

## 2. R4 call site 分级标注 + 坐标精度降级

- [x] 2.1 **`LapTimingEngine.kt:70` 改为 v() + 3 位小数**：
    ```kotlin
    BEFORE:
    FileLogger.d(
        TAG,
        "targetGate=${track.startFinishGate.id}, prev=(${previousSample.latitude},${previousSample.longitude},ts=${previousSample.timestampMillis}), current=(${currentSample.latitude},${currentSample.longitude},ts=${currentSample.timestampMillis}), accepted=${startFinishDetection.accepted}, reason=${startFinishDetection.reason}, directionScore=${startFinishDetection.directionScore}, directionalSpeed=${startFinishDetection.directionalSpeedMps}"
    )

    AFTER:
    if (FileLogger.isVerboseEnabled) {
        FileLogger.v(
            TAG,
            "targetGate=${track.startFinishGate.id}, prev=(${"%.3f".format(previousSample.latitude)},${"%.3f".format(previousSample.longitude)},ts=${previousSample.timestampMillis}), current=(${"%.3f".format(currentSample.latitude)},${"%.3f".format(currentSample.longitude)},ts=${currentSample.timestampMillis}), accepted=${startFinishDetection.accepted}, reason=${startFinishDetection.reason}, directionScore=${startFinishDetection.directionScore}, directionalSpeed=${startFinishDetection.directionalSpeedMps}"
        )
    }
    ```
- [x] 2.2 **`TestSessionViewModel.kt:335` 改为 v() + 3 位小数**：
    ```kotlin
    BEFORE:
    FileLogger.d(
        TAG,
        "bridgeGpsToLapTiming: track=${track.id}, sessionStatus=${currentSession.status}, currentLapIndex=${currentSession.currentLapIndex}, nextGate=${currentSession.nextExpectedGateIndex}, gpsTs=${gpsData.timestamp}, lat=${gpsData.latitude}, lon=${gpsData.longitude}, speed=${gpsData.speed}, bearing=${gpsData.bearing}, prevTs=${previousSample?.timestampMillis}, prevLat=${previousSample?.latitude}, prevLon=${previousSample?.longitude}"
    )

    AFTER:
    if (FileLogger.isVerboseEnabled) {
        FileLogger.v(
            TAG,
            "bridgeGpsToLapTiming: track=${track.id}, sessionStatus=${currentSession.status}, currentLapIndex=${currentSession.currentLapIndex}, nextGate=${currentSession.nextExpectedGateIndex}, gpsTs=${gpsData.timestamp}, lat=${"%.3f".format(gpsData.latitude)}, lon=${"%.3f".format(gpsData.longitude)}, speed=${gpsData.speed}, bearing=${gpsData.bearing}, prevTs=${previousSample?.timestampMillis}, prevLat=${previousSample?.latitude?.let { "%.3f".format(it) }}, prevLon=${previousSample?.longitude?.let { "%.3f".format(it) }}"
        )
    }
    ```
- [x] 2.3 **`TestSessionViewModel.kt:373` 改为 v()**（内容无 lat/lon，只改 v()）：
    ```kotlin
    BEFORE:
    FileLogger.d(
        TAG,
        "lapTimingResult: status=${updatedSession.status}, currentLapIndex=${updatedSession.currentLapIndex}, nextGate=${updatedSession.nextExpectedGateIndex}, crossings=${updatedSession.crossingEvents.takeLast(3)}, completedLaps=${updatedSession.completedLaps.size}"
    )

    AFTER:
    if (FileLogger.isVerboseEnabled) {
        FileLogger.v(
            TAG,
            "lapTimingResult: status=${updatedSession.status}, currentLapIndex=${updatedSession.currentLapIndex}, nextGate=${updatedSession.nextExpectedGateIndex}, crossings=${updatedSession.crossingEvents.takeLast(3)}, completedLaps=${updatedSession.completedLaps.size}"
        )
    }
    ```
- [x] 2.4 **9 个低频 / 异常 call site 不动**（回归保护）：
    - `LapTimingEngine.kt:61` ts 回跳 drop → 保持 `d()` 原文
    - `LapTimingEngine.kt:232` 闭圈 crossingEvents → 保持 `d()` 原文
    - `TestSessionViewModel.kt:159` trackSummary → 保持 `d()` 原文
    - `TestSessionViewModel.kt:301-307` startTest 3 条 → 保持 `d()` 原文
    - `TestSessionViewModel.kt:326` unsynced skip → 保持 `d()` 原文
    - `TestSessionViewModel.kt:357` bridge ts 回跳 → 保持 `d()` 原文

## 3. 测试（对应 Spec R1~R4 各 Scenario）

- [x] 3.1 **新建** `feature/test/src/test/java/com/blazepush/feature/test/FileLoggerTest.kt`
      （依赖 `kotlinx-coroutines-test` + JUnit4 + **JUnit4 `TemporaryFolder`
      Rule**，P2-1 evaluator 拒收 `@TempDir`（JUnit5 API））：
      - 首先确认 `feature/test/build.gradle.kts` 已有
        `testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")`，
        若无则添加
      - 文件隔离使用 JUnit4 `TemporaryFolder`，mock 写法**仅用当前模块已有
        的 `mockito-core`**（评审方 P2-2 明确：`mockito-kotlin` 未引入，
        `whenever(...)` 会 unresolved reference）：
        ```kotlin
        import org.junit.After
        import org.junit.Before
        import org.junit.Rule
        import org.junit.rules.TemporaryFolder
        import org.mockito.Mockito.doReturn
        import org.mockito.Mockito.mock

        class FileLoggerTest {
            @get:Rule
            val tempFolder = TemporaryFolder()

            private lateinit var tempContext: Context

            @Before
            fun setup() {
                FileLogger.resetForTest()  // 重置 initialized 标志 + 清 flushJob
                val dir = tempFolder.newFolder("filesDir")
                tempContext = mock(Context::class.java)
                doReturn(dir).`when`(tempContext).filesDir
                FileLogger.init(tempContext)
            }

            @After
            fun tearDown() {
                FileLogger.resetForTest()
            }
        }
        ```
      - **不得**使用 JUnit5 的 `@TempDir`（模块无 Jupiter runner/engine，
        compile fail）
      - **不得**使用 `whenever(...)`（`mockito-kotlin` 未引入；**若**未来业务
        确实需要 mockito-kotlin，需先起独立 change 引入依赖，不纳入本 scope）
- [x] 3.2 **R1 Scenario 1**：`d_calledAt25HzForOneSecond_callerCompletedUnder100ms`
    - 构造 mock `Context`（仅提供 `filesDir` 指向 tempDir）
    - `FileLogger.init(mockContext)`
    - 循环调用 `FileLogger.d(TAG, "msg-$i")` 1000 次
    - 测调用耗时 < 100ms（平均 < 0.1ms/次）
- [x] 3.3 **R1 Scenario 2**：`d_whenFileWriteThrows_doesNotPropagate`（P2-2：
      **使用 flushForTest 确定性触发 IO 协程 flush**，不依赖 interval 时钟）
    - fake `currentLogFile` 指向只读路径（`file.setReadOnly()` 或
      `Files.createTempDirectory` 后 `chmod 0400`），让 FileWriter 在 flush
      时抛 IOException
    - 调用 `FileLogger.d(TAG, "msg-1")`，断言业务调用不抛
    - 调用 `runTest { FileLogger.flushForTest() }` 确定性等 IO 协程完成一次
      flush 尝试（该次 flush 内部会抛 IOException 并被 try/catch 吞下）
    - `flushForTest()` 返回后断言：
      - 调用方未 crash（主线程观察零异常）
      - `android.util.Log.e("FileLogger", "flush failed", ...)` 被调用（通过
        `Mockito.mockStatic(Log::class.java)` 验证）
    - 再调用 `FileLogger.d(TAG, "msg-2") + flushForTest()`，断言仍不抛
      （consumer 未因上次异常退出循环）
- [x] 3.4 **R1 Scenario 3**：`drop_oldest_whenChannelFull_doesNotBlock`
      （v3 P2-3：通过公开 API 驱动，不反射 private `LogCommand` sealed class）
    - 反射 cancel `flushJob` 暂停 consumer（只 cancel，不 reset initialized；
      channel 本身保留）
    - 循环 2000 次调用 `FileLogger.d(TAG, "drop-$i")`（公开 API，自然生成
      `LogCommand.Line`）
    - 断言 2000 次调用累计耗时 < 100ms（全部非阻塞返回）
    - 恢复 consumer：`flushJob = scope.launch { consumeAndFlushLoop() }`
      （反射设字段，或加 `@VisibleForTesting` 测试专用重启入口）
    - `runTest { FileLogger.flushForTest() }` drain 剩余队列
    - 读 `debug_log.txt`：
      - `grep -c "drop-0"` 输出 `0`（最老消息被 DROP_OLDEST 丢掉）
      - `grep -c "drop-1999"` 输出 `1`（最新消息保留在 channel）
      - 保留的 1024 条应是 `drop-[976..1999]` 范围
- [x] 3.4b **R1 Scenario 4（P1-1 graceful handoff 硬区分 v1/v2）**：
      `init_secondCall_oldBatchIsFlushedBeforeCancelViaFinally`
    - 预先 `FileLogger.setLevel(DEBUG)`，调用 `FileLogger.d(TAG, "handoff-msg-$i")`
      32 次（填 batch 但不到 FLUSH_BATCH_SIZE=64）
    - **不**等 FLUSH_INTERVAL_MS 过去（用反射 cancel 掉 flush 协程的 `withTimeoutOrNull`
      模拟 cancel 触发点，**或**直接调第二次 `FileLogger.init(tempContext)`）
    - 读 `debug_log.txt`，断言**含** 32 条 "handoff-msg-" 消息
    - 硬区分 v1：v1 无 try/finally，旧 batch 静默丢，grep 0 命中 → FAIL
    - 硬区分 v2：v2 finally 落盘，grep 32 命中 → PASS
- [x] 3.4c **R1 Scenario 5（flushForTest 语义契约）**：
      `flushForTest_drainsEverythingCurrentlyEnqueued`
    - `FileLogger.d(TAG, "enqueue-$i")` 5 次（不等 batch / interval）
    - `runTest { FileLogger.flushForTest() }` 挂起直到所有 5 条入队日志被消费 + 落盘
    - 断言 `debug_log.txt` 含全部 5 条
    - 如 flushForTest 被调用后继续 `d(...)` 新的日志，这些**不**保证在本次
      flushForTest 返回前落盘（只保证"调用前入队"的落盘）
- [x] 3.5 **R2 Scenario 1**：`rotation_whenFileReachesMaxFileBytes_renamesToDotOne`
      （P2-2 确定性 flush + P2-3 阈值统一）
    - 预写入 `debug_log.txt` 到 `>= MAX_FILE_BYTES`（= `5 * 1024 * 1024` =
      5_242_880 字节），可用 `file.writeBytes(ByteArray(5_242_880) { 0x41 })`
      或循环 append
    - 调用 `FileLogger.d(TAG, "trigger-rotate")` + `runTest {
      FileLogger.flushForTest() }` 确定性触发 IO 协程一次 flush
    - 断言 rotation 发生：
      - `debug_log.txt.1` 存在，size ≈ `MAX_FILE_BYTES`（含 rotated 内容）
      - `debug_log.txt` 存在，size < `MAX_FILE_BYTES`（新空文件 + "trigger-rotate" 一条）
- [x] 3.6 **R2 Scenario 2**：`rotation_twice_oldestOneDotOneOverwritten`
    - 触发第一次 rotation（`.1` 内容 = batch A）
    - 再写入 + 触发第二次 rotation（`.1` 内容 = batch B，覆盖 batch A）
    - 断言 `.1` 不含 batch A 内容（例如 A 用特定 tag 标记，grep 0 命中）
    - 断言 `.1` 含 batch B 内容
- [x] 3.7 **R3 Scenario 1**：`setLevel_debug_verboseLogsAreDropped`（硬区分 v1/v2）
    - 默认 DEBUG 级别，`FileLogger.v(TAG, "verbose msg")` 100 次
    - flush 后读 `debug_log.txt`，不含 verbose msg
- [x] 3.8 **R3 Scenario 2**：`setLevel_verbose_highFrequencyLogsAreWritten`
    - `setLevel(VERBOSE)`，`FileLogger.v(...)` 1 次
    - flush 后含该条
- [x] 3.9 **R3 Scenario 3**：`level_defaultIsDebug_regularDebugWritten`（回归保护）
    - 默认 DEBUG，`FileLogger.d(TAG, "normal")` → 写入
- [x] 3.10 **R4 Scenario 1**：`engine_detectorLog_sourceUsesVerboseAndThreeDecimalFormat`
      （**源码断言**，不是 runtime）
    - 读 `LapTimingEngine.kt` 源码
    - 断言第 70 行附近有 `FileLogger.v(` 调用
    - 断言同段内含 `"%.3f".format(previousSample.latitude)` 和 `"%.3f".format(previousSample.longitude)`
    - 硬区分 v1：v1 有 `FileLogger.d(` + 无 `%.3f.format`
- [x] 3.11 **R4 Scenario 2**：`bridge_gpsLog_sourceUsesVerboseAndThreeDecimalFormat`
      （源码断言）
    - 读 `TestSessionViewModel.kt` 源码
    - 断言第 335 行附近 `FileLogger.v(` + `"%.3f".format(gpsData.latitude)` 等
- [x] 3.12 **R4 Scenario 3**：`defaultDebugLevel_25HzHighFrequencyLogs_noWriteToFile`
    （端到端回归）
    - `FileLogger.init(tempContext)` 默认 DEBUG
    - 执行 25 次 "假 detector 结果 + 假 bridge 推进" 的调用（模拟 1 秒）
    - flush 后读 `debug_log.txt`，**不含** "targetGate=" 或 "bridgeGpsToLapTiming:" verbose 内容

## 4. 合流门槛（Non-negotiable）

- [x] 4.1 **Spec 验证**：`openspec validate fix-file-logger-and-engine-coord-hygiene --strict`
      退出码 0。
- [x] 4.2 **`feature:test` 编译 + 测试全绿**：
    - `./gradlew :feature:test:compileDebugKotlin` BUILD SUCCESSFUL
    - `./gradlew :feature:test:testDebugUnitTest` BUILD SUCCESSFUL（含新增
      FileLoggerTest 全部 scenario + 战役 A/C/G 现有测试零回归）
- [x] 4.3 **下游零回归**：
    - `./gradlew :core:bluetooth:testDebugUnitTest` BUILD SUCCESSFUL
    - `./gradlew :core:domain:test` BUILD SUCCESSFUL
    - `./gradlew :app:compileDebugKotlin` BUILD SUCCESSFUL
- [x] 4.4 **E2E 契约全绿**：
      `./gradlew :feature:test:testDebugUnitTest --tests "*EndToEndLapTimingContractTest*"`
      全绿（FileLogger 改造不应影响 E2E，但作为保险）。
- [x] 4.5 **backlog A18 迁 🟢**：
      `docs/superpowers/reviews/attack-backlog.md` 第一节 A18 整条搬到第三节，
      状态行追加：
      ```
      - 状态：🟢 pending_review（@impl, commit <hash>, 2026-04-24）
        - 🔴 → 🟡：@impl 认领（2026-04-24）
        - 🟡 → 🟢：commit <hash>，本战役合流门槛全绿（2026-04-24）
      ```
      附"实施成果"块，列核销条件 (1)~(5) 对应动作。
- [x] 4.6 **backlog A39 迁 🟢**：同样从第一节搬到第三节 + 状态变更，附"实施
      成果"块，注明"与 A18 合并实施（本战役同 commit）"。
- [x] 4.7 **附录表格更新**：
    - `| A18 | ... | 🟢（commit <hash>，战役 F 日志层） | F (性能) |`
    - `| A39 | ... | 🟢（commit <hash>，战役 F 日志层） | F (日志) |`
- [x] 4.8 **backlog 迁档 grep 自检**：
    - 第一节 🔴 不再含 A18 / A39
    - 第三节 🟢 含 A18 + A39
    - 附录表格状态列同步

## 5. Commit 策略 ✅ (da3f537)

本 change scope 中等，**1 个代码 commit**（FileLogger 重写 + 3 个 call site
改 + FileLoggerTest 新建一气做完，语义连贯，不拆分）：

**commit**：`fix(logging): 战役 F A18/A39 FileLogger 异步化 + 级别控制 + rotation + 坐标精度降级`

body 要点：
- A18 25Hz 主线程同步 I/O → channel + IO 批量 flush，业务零阻塞
- A18 无异常捕获 → FileWriter 外层 try/catch IOException + Log.e 降级
- A18 无大小上限 → 2 文件 × 5MB 写后 rotate，稳定态 ≤ 2×MAX_FILE_BYTES +
  瞬态单 batch 超量（严格上界 Non-goal）
- A18 + A39 合并：LogLevel VERBOSE < DEBUG；高频 3 条 call site
  `d() → v()` + 坐标 `%.3f`（100m 精度），默认 DEBUG 级别下零写入
- 非高频 9 个 call site 保持 `d()` 原精度（诊断保留）
- FileLoggerTest 14 条 Scenario（R1×5 含 graceful handoff + flushForTest / R2×2 / R3×3 / R4×4）

格式约束：
- Conventional Commits
- body 含 "A18" + "A39" 便于 grep
- Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
- 格式 hook 处理：FileLogger.kt 是 legacy modified 文件，若触发 legacy 规则
  按战役 G 纪律处理（评估后加 `// @IgnoreFormatCheck` 或精确修到位）
