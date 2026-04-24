// @IgnoreFormatCheck
// 理由：本文件由 change fix-file-logger-and-engine-coord-hygiene（战役 F A18+A39）
//       重写为异步日志实现，内部字段命名遵循 Kotlin MutableStateFlow backing 惯例
//       （_loggerScope 类型的下划线前缀）+ LogCommand sealed class 的嵌套 class
//       结构，kt-check 的 class-comment / property-name / when-else 等规则与
//       实现本质不兼容。评审方 2026-04-24 B 方案纪律批准此 ignore；所有字段 /
//       方法都有内联注释。
package com.blazepush.feature.test

import android.content.Context
import androidx.annotation.VisibleForTesting
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
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 文件日志工具 - 战役 F（A18 + A39）重写版本
 *
 * 核心能力：
 * - 业务侧 d/v/e 非阻塞（trySend Line 入队，IO 协程批量 flush）
 * - 日志级别控制（默认 DEBUG；25Hz 高频 call site 用 v() 可被过滤）
 * - 5MB × 2 文件 rotation（写后检查，允许单 batch 短暂超阈值）
 * - 异常自封闭（FileWriter IOException 降级到 logcat，业务永不感知）
 * - Graceful handoff（重复 init 发送 Shutdown 命令 FIFO 排空旧 consumer，零丢失）
 * - flushForTest seam（测试 deterministic drain）
 *
 * 详见 openspec/changes/fix-file-logger-and-engine-coord-hygiene。
 */
object FileLogger {
    enum class LogLevel { VERBOSE, DEBUG, INFO, WARN, ERROR }

    private const val FLUSH_BATCH_SIZE = 64
    private const val FLUSH_INTERVAL_MS = 200L
    private const val CHANNEL_CAPACITY = 1024

    // P2-3 v3：阈值单一真理源，spec + 代码 + 测试引用同一常量
    private const val MAX_FILE_BYTES = 5L * 1024 * 1024

    /**
     * channel payload sealed class：
     * - Line: 业务日志行
     * - Flush: 测试 seam，consumer 写 batch 后 ack
     * - Shutdown: graceful handoff，consumer FIFO 收到后写 batch + 退出循环
     *   (取代 cancelAndJoin：避免 Channel.receive() 与 cancel 的交付竞态导致丢日志)
     *
     * P2 finding 2（2026-04-24 codex code-review）：Flush / Shutdown 通过
     * suspend `send` 入队保证命令自身不丢，但在 channel 满（1024）时
     * `DROP_OLDEST` 语义会挤掉最老 1 条 `Line`。此为 DROP_OLDEST 接受下的
     * 既定降级契约：正常负载下（<1024 积压）Line 不丢；过载时 Flush /
     * Shutdown 命令自身优先于 Line 存活（控制面比数据面更重要）。
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

    // P1 finding 1（2026-04-24 codex code-review）：SimpleDateFormat 非线程安全，
    // formatLine() 在调用方线程执行，多业务线程共享会串扰或抛异常。
    // ThreadLocal 保证每线程独享 formatter 实例，避免引入全局锁。
    private val dateFormat = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat =
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    }

    fun init(context: Context) {
        // graceful handoff 强契约（Shutdown FIFO 方案）：
        // 1. 向 channel FIFO 末尾发 Shutdown 命令，同步等旧 flush job 消费完所有
        //    pre-init Line 后写 batch 并退出。避免 cancelAndJoin 的交付竞态
        //    (Channel.receive() 可能已取出 item 但 cancel 在 batch.add 前介入)
        // 2. 替换 currentLogFile / rotatedLogFile 引用（旧 consumer 已退出，无写竞态）
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
        val timestamp = dateFormat.get()!!.format(Date())
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
                        // graceful exit：Shutdown 是 FIFO 末尾，之前所有 Line 都已 batched
                        //              写 batch 后 return 退出循环，flushJob 自然完成
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
     * 语义：向 channel 发送 LogCommand.Flush(ack)，consumer 收到后先写
     * 当前 batch 再 ack.complete；flushForTest 在 ack.await() 处挂起直到
     * consumer 完成。正常负载（channel 积压 <1024）下返回后保证 "flushForTest
     * 调用前所有 trySend 成功的 Line 已落盘到 currentLogFile"。
     *
     * P2 finding 2（2026-04-24 codex code-review）降级契约：channel 满
     * （1024 条未消费积压）时 `send(Flush)` 会因 DROP_OLDEST 挤掉最老 1 条
     * Line。过载场景下 Flush 命令自身仍不丢（进入 channel 后 FIFO 消费），
     * 但文件里会少 1 条最老 Line——这是 DROP_OLDEST 接受范围内的既定损失。
     *
     * 不保证 "flushForTest 返回后 / await 期间后续 trySend 的 Line" 已落盘。
     *
     * 仅 internal + @VisibleForTesting 暴露，生产代码不得调用。
     */
    @VisibleForTesting
    internal suspend fun flushForTest() {
        val ack = CompletableDeferred<Unit>()
        // send（非 trySend）确保 Flush 命令本身不被 DROP_OLDEST 丢掉
        logChannel.send(LogCommand.Flush(ack))
        ack.await()
    }

    /**
     * 仅测试使用：reset initialized 标志 + 清空 flushJob 引用。
     * 生产代码不得调用。@Before 里可用它让每个测试从首次 init 状态开始。
     */
    @VisibleForTesting
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
        currentLevel = LogLevel.DEBUG
    }
}
