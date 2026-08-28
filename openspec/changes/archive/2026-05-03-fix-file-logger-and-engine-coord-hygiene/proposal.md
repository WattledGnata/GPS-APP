# fix-file-logger-and-engine-coord-hygiene

战役 F 日志层闭环：一次性修 **A18**（FileLogger 主线程 25Hz 同步写盘 /
ANR + GC 风险）+ **A39**（engine 日志含完整经纬度 / 隐私 + 体量，A39 核销
条件 (1) 明文 "与 A18 合并"）。两条技术领域不同但解法耦合 —— 日志异步化
消除 I/O 阻塞 + 日志级别 + 坐标精度降级三件事拆开做会互相拉开 scope，合并
一个 change 的 4 个 Requirement 更清晰。

核心决策摘要：

- **Round 1（本轮）归 Session 2（性能 / UI / 日志线）**：本 change 与战役 C
  所有条目正交（不动 engine 判圈逻辑 / filter / parser），可以与战役 C / H /
  altitude 等 session 并行推进
- **A22（UI haversine）不在本轮**：虽然核销条件也提"与 A18 叠加放大 UI 掉
  帧"，但 A22 改的是 `ActiveLap` 增量距离累积（数据层），与日志层完全正交；
  A22 排 Round 3 独立处理
- **保持向后兼容**：FileLogger 的 `d(tag, message)` 签名不变，call site（12
  处）零侵入；新加 `v(...)` / `setLevel(...)` / `init(...)` 是扩展不是改造

## Why

### A18 — FileLogger 主线程 25Hz 同步写盘 (§ 1.2 of review)

**证据**：`feature/test/src/main/java/com/blazepush/feature/test/FileLogger.kt:24-42`

```kotlin
object FileLogger {
    private var logFile: File? = null

    fun d(tag: String, message: String) {
        val timestamp = dateFormat.format(Date())
        val logLine = "$timestamp D/$tag: $message\n"
        logFile?.let {
            FileWriter(it, true).use { writer ->     // ← 每次同步 open + append + close
                writer.append(logLine)
            }
        }
    }
    // e() 同样模式
}
```

每次调用 `d()` 会**同步**执行 3 步：打开 `FileWriter` → 写一行 → `close()`（`use`
块自动 close）。

**高频调用统计**：grep 全仓 `FileLogger\.` 有 12 处，分层：

| 频率 | 文件 : 行号 | 调用场景 | 每秒估算 |
|---|---|---|---|
| 高频 × 25Hz | `LapTimingEngine.kt:70` | 每帧 detector 结果（含 lat/lon）| 25 |
| 高频 × 25Hz | `TestSessionViewModel.kt:335` | 每帧 bridge 推进（含 lat/lon）| 25 |
| 高频 × 25Hz | `TestSessionViewModel.kt:373` | 每帧 bridge 结果 | 25 |
| 低频 × 偶发 | `LapTimingEngine.kt:61` | processSample ts 回跳 drop | <1 |
| 低频 × 偶发 | `LapTimingEngine.kt:232` | 闭圈时 crossingEvents 更新 | <1 |
| 低频 × 偶发 | `TestSessionViewModel.kt:326` | unsynced frame skip | <1 |
| 低频 × 偶发 | `TestSessionViewModel.kt:357` | bridge ts 回跳 drop | <1 |
| 极低频 × 1 次 | `TestSessionViewModel.kt:159 / 301-307` | startTest / trackSummary | <<1 |

**25Hz × 3 条高频 = 75 次/秒主线程同步 I/O**（`gpsData.collect` 在 Main
dispatcher，TestSessionViewModel.kt:112-122 合约）。每次 `FileWriter.open +
append + close` 在 Android 上按 Nand flash 写入特性 ~1-5ms 延迟，75 × 平均 3ms
= 225ms/秒 主线程阻塞，**长期 ANR 边缘 + 明显 GC 压力**。

**叠加风险**：

1. 无 `try/catch IOException` → **磁盘满 /  filesDir 只读**直接中断整条
   GPS→Lap 链路
2. 无大小上限 → 长会话 50MB+/小时（A39 加剧），无限增长
3. 无 rotation → 单文件 `debug_log.txt` 持续追加到进程退出

### A39 — engine 日志含完整经纬度（§ 1.15 of review）

**证据**：上表高频两条 `LapTimingEngine.kt:70-73` 与 `TestSessionViewModel.kt:335-338`
都写入完整 lat/lon（`%.7f` 默认 Kotlin `Double.toString`）。

```kotlin
// LapTimingEngine.kt:70
FileLogger.d(TAG, "targetGate=..., prev=(${prev.latitude},${prev.longitude},ts=...), current=(${current.latitude},${current.longitude},ts=...), ...")

// TestSessionViewModel.kt:335
FileLogger.d(TAG, "bridgeGpsToLapTiming: ..., lat=${gpsData.latitude}, lon=${gpsData.longitude}, ..., prevLat=${previousSample?.latitude}, prevLon=${previousSample?.longitude}")
```

**攻击面**：

1. **隐私**：完整轨迹明文存在 `filesDir/debug_log.txt`。用户赛车路线 / 训练
   地点全部暴露在本地 log 里，一旦 ADB pull / 崩溃上报走到这文件就泄露
2. **体量**：25Hz × 长圈 session（2 小时） × 3-4 行/帧 × 200 字节 ≈ **108 MB**
   的文件 —— 与 A18 的主线程 I/O 叠加：ADB pull 变慢、崩溃上报被拒绝、debug
   pipeline 卡顿
3. **诊断无益**：实际查问题不需要完整 lat/lon 精度（7 位小数 ≈ 1cm 定位）；
   3-4 位小数（10-100m）就够看轨迹走势、判断是否接近 gate

### 两条联动 + 为什么合并一个 change

A39 核销条件 (1) 明文：

> 与 A18 合并：`FileLogger` 加日志级别控制 + 高频路径默认只在状态变化 / accepted /
> 异常时打

"日志级别" 是 A18 的一部分（异步化 + 有效限频），"高频路径改条件打印" 既是
A18 的限频手段也是 A39 减坐标泄露手段。两件事不分离有如下收益：

- **FileLogger 层契约一次性拍板**：异步化 + 级别 + 精度三件套同批引入，避免后续
  改接口
- **call site 只改一次**：高频 3 条调用从 `FileLogger.d(...)` 改为
  `FileLogger.v(...)`（verbose 默认不打）一次完成
- **测试覆盖单一**：4 个 Requirement 对应 14 条 Scenario（R1×5 + R2×2 + R3×3
  + R4×4）在一个 `FileLoggerTest` 里跑完

## What

本 change 引入 **4 个 Requirement**（R1~R4）+ **12 个 call site 分级标注**
的 codebase 级改动。

### R1 FileLogger 异步 I/O：channel + IO 协程批量 flush + 异常兜底

**文件**：`feature/test/src/main/java/com/blazepush/feature/test/FileLogger.kt`

```kotlin
BEFORE:
object FileLogger {
    private var logFile: File? = null
    private val dateFormat = SimpleDateFormat(...)

    fun init(context: Context) {
        logFile = File(context.filesDir, "debug_log.txt")
        logFile?.writeText("")
        d("FileLogger", "===== 日志开始 =====")
    }

    fun d(tag: String, message: String) {
        val timestamp = dateFormat.format(Date())
        val logLine = "$timestamp D/$tag: $message\n"
        logFile?.let {
            FileWriter(it, true).use { writer ->
                writer.append(logLine)
            }
        }
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) { /* 同 d() */ }
}

AFTER（概念骨架，**完整实现以 `tasks.md §1.2` 为准**）:
object FileLogger {
    // 常量与 state（见 tasks.md §1.2 完整声明）
    //   FLUSH_BATCH_SIZE = 64 / FLUSH_INTERVAL_MS = 200 / CHANNEL_CAPACITY = 1024
    //   MAX_FILE_BYTES = 5 * 1024 * 1024
    //   sealed class LogCommand { Line(content) / Flush(ack) / Shutdown }  // v5 新增 Shutdown
    //   Channel<LogCommand>(capacity=1024, onBufferOverflow = DROP_OLDEST)
    //   @Volatile var initialized = false  // 首次 init 清空、重复 init 不清空
    //   @Volatile var currentLevel = LogLevel.DEBUG

    fun init(context: Context) {
        // v5 强契约 graceful handoff（Shutdown FIFO 方案）：向 channel 发 Shutdown
        // 命令让旧 consumer FIFO 消费完所有 pre-init Line 再退出，然后替换文件引用，
        // 启新 flush job。v5 不用 cancelAndJoin：Channel.receive 与 cancel 存在交付
        // 竞态，可能已取出 item 但 cancel 在 batch.add 前介入 → 静默丢中间项
        flushJob?.let { oldJob ->
            runBlocking(Dispatchers.IO) {
                if (oldJob.isActive) logChannel.send(LogCommand.Shutdown)
                oldJob.join()
            }
        }
        currentLogFile = File(context.filesDir, "debug_log.txt")
        rotatedLogFile = File(context.filesDir, "debug_log.txt.1")
        if (!initialized) { currentLogFile?.writeText(""); initialized = true }
        flushJob = scope.launch { consumeAndFlushLoop() }
        d("FileLogger", "===== 日志开始 =====")
    }

    fun d(tag: String, message: String) = enqueue(LogLevel.DEBUG, tag, message)
    fun v(tag: String, message: String) = enqueue(LogLevel.VERBOSE, tag, message)
    fun e(tag: String, message: String, throwable: Throwable? = null) = /* 同 d */

    private fun enqueue(level: LogLevel, tag: String, message: String) {
        if (level < currentLevel) return  // R3 级别过滤
        logChannel.trySend(LogCommand.Line(formatLine(level, tag, message)))
    }

    // consumeAndFlushLoop 用 while(true) + Shutdown return（v5，不再依赖 scope.isActive）
    //   when (cmd) { is Line -> batch.add(...); is Flush -> writeBatchSafe(batch); ack.complete()
    //                is Shutdown -> writeBatchSafe(batch); return }
    //   batch / timeout 满 → writeBatchSafe(batch) + checkAndRotate()
    //   finally 块作为 scope.cancel 安全网（正常流程走 Shutdown 分支不触发）

    // writeBatchSafe 外层 try/catch IOException，catch 后 Log.e 降级不 throw

    @VisibleForTesting internal suspend fun flushForTest() {
        val ack = CompletableDeferred<Unit>()
        logChannel.send(LogCommand.Flush(ack))  // send 非 trySend，Flush 不被 DROP_OLDEST 吞
        ack.await()
    }
}
```

上述仅为概念骨架（展示 API 形状与 flow）。**完整代码（含 imports / Channel
payload sealed class / try/finally / resetForTest 等）请看 `tasks.md §1.2`**。

**核心契约**：

- `d()` / `v()` / `e()` **非阻塞**（最坏 `trySend` 失败 + `DROP_OLDEST`），**不 suspend**
- IO 协程内部 `FileWriter` 外层 try/catch `IOException`，永不传播给调用者
- channel capacity 1024，满时 drop 最老 log 而不是 drop 最新（诊断价值一般最新最高）
- `init(context)` **强契约 graceful handoff**（v2 P1-1 拍板 + v5 Shutdown FIFO
  修订）：头部 `runBlocking(Dispatchers.IO) { logChannel.send(Shutdown);
  oldJob.join() }` 让旧 flush job 按 FIFO 消费完所有 pre-init `Line` 后写
  batch 再退出循环，然后才替换 file 引用；`initialized` 标志让**首次**
  init 清空文件、**重复** init 不清空，保证 graceful handoff 的旧 batch
  不被 `writeText("")` 擦掉。v5 不用 `cancelAndJoin`：`Channel.receive()`
  与 cancel 存在交付竞态，可能已取出 item 但 cancel 在 `batch.add` 前介入
  → 静默丢中间项（实测偶现漏 msg-21 / msg-30）
- `flushForTest()` 测试入口（v2 P2-1 拍板）：Channel payload 升级为
  `LogCommand`（sealed `Line` / `Flush(ack)`），consumer 收到 `Flush` 先
  写 batch 再 `ack.complete(Unit)`；`flushForTest` 发 `Flush` + `await()`
  得到确定性同步点

> **完整 FileLogger 实现代码见 `tasks.md §1.2`**，本 proposal 以上概念骨架，
> 与 spec + tasks v5 统一（v5 相对 v4 核心迭代：graceful handoff 从
> `cancelAndJoin` 改为 `LogCommand.Shutdown` FIFO 排空方案，实测闭合
> `init_secondCall` 竞态）。

### R2 FileLogger rotation：2 文件各 5MB 写后轮换（允许单 batch 短暂超阈值）

```kotlin
private const val MAX_FILE_BYTES = 5L * 1024 * 1024  // 5 MB（spec + 代码 + 测试单一真理源）

private fun checkAndRotate() {
    val cur = currentLogFile ?: return
    if (cur.length() < MAX_FILE_BYTES) return
    // rotation: debug_log.txt → debug_log.txt.1（覆盖旧的 .1）
    rotatedLogFile?.let { rotated ->
        if (rotated.exists()) rotated.delete()
        cur.renameTo(rotated)
    }
    cur.createNewFile()  // 新空文件继续写
}
```

**契约（v2 P2-3 拍板 "写后 rotate + 瞬态超阈值允许"）**：

- `debug_log.txt` 达 `MAX_FILE_BYTES = 5 * 1024 * 1024` 字节时 → rename 为
  `debug_log.txt.1`（覆盖旧 rotated）
- 稳定态磁盘占用 ≤ `2 × MAX_FILE_BYTES`（当前活跃 + 1 份历史），约 10MB
- 允许单 batch 瞬态超量（64 行 ≈ 12KB）：rotation 在 flush 批次写完后检查，
  写入期间可能短暂达到 `MAX_FILE_BYTES + 一 batch`
- 严格总上界是本 spec 的 **Non-goal**（预 rotate 拒收理由见 Alternatives § E）
- rotation 在 IO 线程 flush 批次结束后检查，不阻塞业务

### R3 日志级别 + 高频路径条件打印

```kotlin
enum class LogLevel { VERBOSE, DEBUG, INFO, WARN, ERROR }

private var currentLevel: LogLevel = LogLevel.DEBUG  // 默认 DEBUG（含异常 / 状态变化）

fun setLevel(level: LogLevel) {
    currentLevel = level
    d("FileLogger", "level changed to $level")
}
```

**调用侧改造（12 处分级标注）**：

| 调用点 | 原等级 | 新等级 | 理由 |
|---|---|---|---|
| `LapTimingEngine.kt:61` ts 回跳 drop | d | d | 异常场景，保持 DEBUG 默认打 |
| `LapTimingEngine.kt:70` 每帧 detector 结果 | d | **v** | 25Hz 高频，VERBOSE 模式才打 |
| `LapTimingEngine.kt:232` 闭圈 crossingEvents | d | d | 状态变化，保持 |
| `TestSessionViewModel.kt:159` trackSummary | d | d | 1 次，保持 |
| `TestSessionViewModel.kt:301-307` startTest 3 条 | d | d | 1 次，保持 |
| `TestSessionViewModel.kt:326` unsynced skip | d | d | 偶发，保持（异常路径） |
| `TestSessionViewModel.kt:335` 每帧 bridge 推进 | d | **v** | 25Hz 高频，VERBOSE 才打 |
| `TestSessionViewModel.kt:357` bridge ts 回跳 | d | d | 偶发，保持 |
| `TestSessionViewModel.kt:373` 每帧 bridge 结果 | d | **v** | 25Hz 高频，VERBOSE 才打 |

**默认级别 DEBUG**，只打异常 + 状态变化，**高频 3 条日志 verbose 不打** →
每秒日志量从 75+ 条降到 ~1-5 条（仅异常 / 闭圈）。

### R4 高频路径坐标精度降级

高频 VERBOSE 日志（line 70 + line 335）当真的开启时，坐标用 3 位小数
`String.format("%.3f", latitude)` 代替默认 `%.7f`。

```kotlin
BEFORE（verbose 模式打开时）:
"prev=(${prev.latitude},${prev.longitude},...), current=(${current.latitude},${current.longitude},...)"
// 输出示例：prev=(30.5826543,104.0673214,...)，7 位小数 ≈ 1cm 精度

AFTER:
"prev=(${"%.3f".format(prev.latitude)},${"%.3f".format(prev.longitude)},...)"
// 输出示例：prev=(30.583,104.067,...)，3 位小数 ≈ 100m 精度
```

**精度对照**：

| 小数位数 | 精度 | 用途 |
|---|---|---|
| 7（原）| ~1cm | 过度泄露 |
| **4** | ~10m | gate 附近位置判断够用 |
| **3（选用）** | ~100m | 轨迹走势、跨街区定位够用；隐私更好 |
| 2 | ~1km | 不够诊断 |

选 **3 位小数** 是"诊断最低可用" + "定位最粗"的平衡点。

**低频 DEBUG 日志（startTest / trackSummary / ts 回跳）不做精度降级** —— 它们
1. 频率低（<<1/s），体量可控
2. 真正出问题时需要完整精度做定位复盘
3. 默认就不会触及隐私（Verbose 关闭时这些照常完整打出，但它们不在 verbose 通道）

## Impact

### 协议与数据模型

- **不改** BLE 协议 / GpsData 字段 / LapSession 任何数据模型
- **不改** engine / filter / parser / BLE 连接链路任何业务逻辑
- `FileLogger` 的 `d(tag, message) / e(tag, message, throwable?)` 公共签名保持，
  新增 `v(tag, message)` / `setLevel(LogLevel)` 为附加能力

### 受影响模块

| 模块 | 文件 | 动作 |
|---|---|---|
| FileLogger | `feature/test/src/main/java/com/blazepush/feature/test/FileLogger.kt` | 重写（channel + IO flush + rotation + level + 异常兜底），公共 API 向后兼容 |
| engine 调用点 | `feature/test/src/main/java/com/blazepush/feature/test/usecase/LapTimingEngine.kt` | 第 70 行高频 detector 日志 `d` → `v` + 坐标 `%.3f` 降级；其他 2 条（61, 232）保持 |
| bridge 调用点 | `feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt` | 第 335 / 373 行高频日志 `d` → `v` + 坐标 `%.3f` 降级；其他 6 条保持 |
| 测试 | `feature/test/src/test/java/com/blazepush/feature/test/FileLoggerTest.kt`（新建）| R1 异步 + R2 rotation + R3 级别 + R4 精度降级，共 14 条 Scenario（R1×5 + R2×2 + R3×3 + R4×4）|

### 行为变更

| 场景 | Before | After |
|---|---|---|
| 25Hz 数据采集 | 主线程每秒 75+ 次同步 `FileWriter` open/write/close，ANR 边缘 + GC 压力 | 业务线程 `trySend` 非阻塞，IO 协程 200ms 批量 flush；主线程零 I/O |
| 磁盘满 / filesDir 只读 | `FileWriter` 抛 `IOException` 中断 GPS→Lap 业务链路 | try/catch 吞异常 + `Log.e` 输出到 logcat，业务流不受影响 |
| 长 session 日志 | 单文件无限追加，2 小时可达 108 MB | 2 文件各 5MB 写后轮换，稳定态 ≤ 2×MAX_FILE_BYTES（≈10MB）+ 瞬态单 batch 超量 |
| 高频路径日志 | 每帧完整 lat/lon + detector 详情 54MB/小时 | 默认级别 DEBUG 下，verbose 不打；verbose 打开时坐标 `%.3f`（10-100m 精度） |
| 低频异常日志 | 保留 | 保留，不改精度（诊断需要） |
| 坐标泄露隐私 | `%.7f` ≈ 1cm 明文 | verbose 打开也只 `%.3f` ≈ 100m 粒度；默认不打 |

### 性能预估

**实测基线**（未实施时，需要实施方验证）：

| 指标 | 当前 | 目标 | 测试方式 |
|---|---|---|---|
| 25Hz 采集时主线程阻塞 / 秒 | 估 200-300ms（75 次 × 3-5ms）| < 10ms | Android Systrace / `Choreographer` 掉帧计数 |
| 单次 `FileLogger.d()` 延迟 | 1-5ms（阻塞）| < 0.1ms（`trySend`）| 性能回归测试 `d_called1000TimesIn1s_finishesWithinBudget` |
| 10 分钟 session 日志大小 | ~9MB 增长 | 按 DEBUG 默认级别 ~100KB | 实机运行后 ls 文件 |

### 风险与缓解

| 风险 | 缓解 |
|---|---|
| `DROP_OLDEST` 丢日志导致诊断困难 | channel capacity 1024 + 批量 flush 200ms = 业务每秒灌 > 5000 条才触发 drop，远超实际（75 条/s）；真丢也是最老 log，诊断关注最新为主 |
| IO 协程异常终止 | `scope = Dispatchers.IO + SupervisorJob()`，其他协程失败不影响 flush job；flush job 内部 try/catch，任何 IOException 只 Log.e 不 crash |
| `init` 重复调用（app 重启 / 进程重用）| cancel 旧 `flushJob` + 启新的；`logChannel` 是 object 级别，旧的 enqueue 会被新 flush job 消费（跨 lifecycle 安全） |
| 测试难度（异步 + channel + 文件 I/O）| 用 `runTest` + `TestScope` 控制虚拟时钟；用 JUnit4 `TemporaryFolder` 隔离文件（**不用 JUnit5 `@TempDir`**，本模块无 Jupiter runner）；Mockito `doReturn(...).\`when\`(ctx).filesDir`（**不用 `whenever`**，mockito-kotlin 未引入）；按战役 G `BleConnectionTest` 模式反射私有字段 + `flushForTest()` 做 deterministic drain |
| `setLevel(LogLevel.VERBOSE)` 被误在生产开启 → 高频日志爆 | verbose 仅在 debug build + 开发者手动开启；默认 DEBUG；call 侧注释说明"仅调试用" |
| 高频路径 `v()` 即使级别过滤掉，`String.format` / 字符串插值仍执行 | `enqueue` 入口早退（`if (level < currentLevel) return`），但参数 format 在调用方已执行。R3 改造中高频 3 条日志整体改为 **inline lambda** 模式或 **预判 isVerboseEnabled**：`if (FileLogger.isVerboseEnabled) FileLogger.v(TAG, "...")` —— 避免无用字符串构造 |

### 回归保护要求

每个 Requirement 至少 2 条硬区分 v1/v2 的测试：

- **R1 × 3 条**：
  - `d_doesNotBlockCallerThread_measuredUnder0_1ms`（性能回归）
  - `d_whenFileWriteThrows_doesNotPropagate`（异常吞下）
  - `d_calledAt25HzForOneSecond_callerCompletedUnder10ms`（高频回归）
- **R2 × 2 条**：
  - `rotation_whenFileReaches5MB_renamesToDotOne`
  - `rotation_magneticAfterTwoRolls_oldestDropped`（第二次 rotation 时 `.1` 被覆盖）
- **R3 × 3 条**：
  - `setLevel_verbose_highFrequencyLogsAreWritten`
  - `setLevel_debug_verboseLogsAreDropped`（硬区分 v1 / v2 高频日志）
  - `level_defaultIsDebug_verboseLogsNotWritten`
- **R4 × 2 条**：
  - `engine_detectorLog_coordinatesAreThreeDecimalPrecision`（源码断言 + verbose 时实际输出检查）
  - `bridge_prevLatLon_coordinatesAreThreeDecimalPrecision`

## Alternatives

### A：只做 R1 异步（不做 R2 rotation / R3 级别 / R4 精度）

**拒收理由**：
- A39 核销条件 (1) 明文要求"与 A18 合并：FileLogger 加日志级别控制 + 高频路径
  默认只在状态变化 / accepted / 异常时打" —— 级别控制是 A39 + A18 共同需求
- 只做异步会让单文件仍然无限增长（54MB/h）、完整 lat/lon 仍然泄露隐私；
  "异步化" 本身只解决 CPU 阻塞，不解决体量 / 隐私两大问题
- 4 个 Requirement 共用 FileLogger 这一个契约，分拆做 = 两次改 FileLogger 公共
  API，调用方迁移两次

### B：日志级别改为 "可选宏"（如 `#ifdef VERBOSE`）

**拒收理由**：Kotlin 没有 preprocessor，"宏"只能用 `const val VERBOSE =
false` + `if (VERBOSE) FileLogger.d(...)` 模拟。缺点：
- 编译时决定 vs 运行时决定：一次打包就锁死 verbose，改级别需重编
- 与战役 G `BleConnection.kt` + `RaceChronoParser.kt` 里的运行时级别 / 开关
  设计不一致

R3 的 `setLevel(...)` 运行时 API 更灵活。

### C：A39 拆到独立 change（命名 `fix-engine-log-coord-privacy`）

**拒收理由**：A39 核销条件 (1) 的"与 A18 合并"是**文字约定**，不是建议。拆
开做会导致：
- FileLogger 契约拆两次改（异步化 + 坐标精度降级）
- engine / bridge 的 12 个 call site 迁移两次（level + format）
- 总 scope 反而大

合并 scope 仅从 "改 FileLogger 60 行" 扩到 "FileLogger 150 行 + 12 call site
分级标注"，增量有限。

### D：log 写到 Android `Log.d(...)` 不写文件

**拒收理由**：
- logcat 有系统级 ring buffer（~4MB 全机共享，被其他 app 淹），不适合长期保留
- 崩溃上报 / ADB pull 用 `filesDir/debug_log.txt` 作为证据链来源，迁到 logcat
  = 丢证据
- 本战役目的是"让 FileLogger 可持续高频安全运行"，不是"用 logcat 替代
  FileLogger"

### E：预 rotate（写入前检查，严格不超阈值）

**拒收理由**（P2-3 evaluator 拍板：选 "写后 rotate 允许单 batch 短暂超"）：
- 预 rotate 要求在 `writeBatchSafe` 前按 `file.length() + pendingBytes >=
  MAX_FILE_BYTES` 判断，pendingBytes 需要序列化整个 batch 算字节长度，然后还要
  分片 rotate（如果 batch 横跨阈值）—— 实现复杂，测试面扩大
- 写后 rotate 允许**稳定态**磁盘 ≤ 10MB + **瞬态**一个 batch（64 行 × ~200 字节
  ≈ 12KB）超出，相对 MAX_FILE_BYTES 5MB 超出 < 0.25%，工程上完全可接受
- 简单性换确定性上界：本战役选择简单路径，把"严格上界"作为 Non-goal

## Non-goals

### 不改的代码 / 模块

- **不改** BLE 协议 / `GpsData` 字段 / `ConnectionState` / 任何数据模型
- **不改** engine 判圈逻辑 / filter 异常判定 / parser 解码 / BLE 连接
- **不改** UI 日志展示（本 change 是 FileLogger，不涉及屏显）
- **不触碰** logcat `Log.d/e` 的 Android 标准调用（FileLogger 独立于 logcat）

### 不做的功能

- **不实现** 日志上报 / 云端收集 / 加密存储（隐私进一步保护留给产品层决策）
- **不改** `filesDir/debug_log.txt` 路径（路径是 app-private，保持）
- **不做** JSON 结构化日志（scope 爆炸，当前文本行够诊断）
- **不做** 日志查询 UI / debug panel
- **不处理** A22 UI haversine 性能（Round 3 独立 change）
- **不处理** A28 frequency 统计退化（Round 2）
- **不处理** A37 TrackCatalog lazy 主线程 I/O（Round 2）
- **不处理** A30 孤岛类删除（Round 4）
- **不处理** A35 UI currentLap 显示（Round 5）
- **不处理** A17 DI fallback（Round 4）
- **不处理** A16b altitude（另一 session）
- **不处理** A26 / A41（战役 H，其他 session / 后续）
