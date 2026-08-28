# Spec Delta: file-logger-and-coord-hygiene

> Capability: **FileLogger 异步化 + 文件轮换 + 级别控制 + 高频路径坐标精度降级**。
> 覆盖 `feature/test/src/main/java/com/blazepush/feature/test/FileLogger.kt`
> 的契约升级（同步 I/O → 异步 channel / 无上限 → 5MB × 2 轮换 / 无级别 →
> 运行时可配级别）+ 三个高频调用点（engine / bridge）的分级标注与坐标精度
> 降级。
>
> 核心原则：
>
> 1. **业务线程零 I/O**：`FileLogger.d/v/e` 是非阻塞 `trySend`，IO 协程内部
>    批量 flush。任何调用方的 25Hz 高频路径主线程阻塞时间 < 0.1ms
> 2. **异常自封闭**：IO 协程内的 `FileWriter` `IOException` 由 FileLogger
>    吞掉 + 降级到 `android.util.Log.e`，**绝不**传播到业务调用方
> 3. **磁盘有界（写后 rotate 策略）**：稳定态 `debug_log.txt` + `debug_log.txt.1`
>    ≤ `2 × MAX_FILE_BYTES = 10_485_760` 字节；允许单 batch（≤ 64 行 ≈ 12KB）
>    瞬态超量（写后检查，本 spec 接受）；严格上界是 Non-goal
> 4. **级别默认 DEBUG**：VERBOSE 日志仅在开发者显式 `setLevel(VERBOSE)` 后
>    才入队，默认 25Hz 高频路径零写入
> 5. **坐标精度隐私降级**：verbose 模式下的 25Hz 高频日志 lat/lon 用 3 位
>    小数（~100m 精度），够诊断轨迹走势不够精确定位
>
> 本 spec 不涉及 engine 判圈逻辑 / filter / parser / BLE 连接等业务层契约
> —— 只动 FileLogger 本身 + 3 处高频 call site 标注。

## ADDED Requirements

### Requirement: FileLogger 业务侧调用非阻塞（异步 channel + 批量 flush）

MUST 保证 `FileLogger.d(tag, message)` / `FileLogger.v(tag, message)` /
`FileLogger.e(tag, message, throwable?)` 在任何业务线程调用时立即返回，
MUST NOT 执行同步文件 I/O。调用线程上观察到的每次调用耗时 < 0.1ms（除字符串
format 以外的 FileLogger 自身开销）。

FileLogger 内部 MUST 持有**结构性异步 channel**：容量 1024、`onBufferOverflow
= DROP_OLDEST`；**payload MUST 支持两类命令** —— 普通日志行 + 测试 flush 控制
命令。推荐实现为 `Channel<LogCommand>`（sealed class），含 `Line(content)`
和 `Flush(ack: CompletableDeferred<Unit>)` 两个 data class。MUST 启动**唯一**
一个 IO 协程（`Dispatchers.IO + SupervisorJob()`）消费 channel，按 64 行或
200ms 批量 flush 到磁盘。

payload 契约：

- `Line` 承载业务日志行（`d()` / `v()` / `e()` 入队用 `trySend(Line)`，
  **非阻塞**，可被 DROP_OLDEST 策略丢）
- `Flush(ack)` 承载测试 deterministic drain 控制信号（`flushForTest()` 入队
  用 `send(Flush)`，保证控制命令不被 DROP_OLDEST 吞；consumer 收到后先落盘
  当前 batch 再 `ack.complete(Unit)`）

`FileWriter` 外层 MUST 套 `try/catch IOException`，catch 后 MUST 调用
`android.util.Log.e("FileLogger", "flush failed", t)` 并**不**再 throw。业务
链路永远看不到 FileLogger 的 IO 异常。

关键属性：

- **业务热路径非阻塞**：业务侧 `d()` / `v()` / `e()` 用 `trySend(Line)`
  （非 suspending），capacity 1024 满时走 `DROP_OLDEST` 策略（丢最老一条
  `Line`，不阻塞业务）
- **自愈**：IO 协程内部捕获 `IOException` + `Log.e` 降级，flush loop 继续
  工作（磁盘恢复后新 log 能写入）
- **幂等 init + graceful handoff（v5 Shutdown FIFO 方案，P1-1 evaluator v2 拍板：强契约）**：
  `FileLogger.init(context)` 重复调用 MUST 做到三件事：
  1. **旧 flush 协程 FIFO 排空后退出**：`init` 头部 MUST 同步等旧 job 消费
     完 channel 中所有 pre-init `Line`、写 batch 后退出（推荐实现：向 channel
     FIFO 末尾发 `LogCommand.Shutdown`，consumer 按 FIFO 消费所有 `Line` 后
     收到 Shutdown 写 batch + `return` 退出循环；`runBlocking(Dispatchers.IO)
     { channel.send(Shutdown); oldJob.join() }`）。**重复 init MAY 短暂阻塞
     调用方**（等 IO 落盘，量级几到几十 ms）——该阻塞只发生在 init 生命
     周期路径，**不影响 `d/v/e` 的业务热路径非阻塞契约**。v5 不用
     `cancelAndJoin`：`Channel.receive()` 可能已取出 item 但 cancel 在
     `batch.add` 前介入，无 `onUndeliveredElement` 时会静默丢失中间项；
     Shutdown FIFO 排空方案在**正常负载下**（channel 积压 <1024）确保零丢失
  2. 旧 batch 落盘到**旧** `currentLogFile`（Shutdown 处理完成前 file 引用
     尚未替换）
  3. channel 生命周期跨越 init，不重建；旧 job 排空退出后，新 flush 协程
     消费后续 `Line`（含 init 尾部 `d("==日志开始==")` 和业务日志）

  "**init 返回后旧 batch 已同步落盘**"是 graceful handoff 的核心可测契约；
  测试可直接读 `debug_log.txt` 断言内容，无需 eventually / polling。

- **满 buffer 降级契约（P2 finding 2，2026-04-24 codex code-review 拍板）**：
  `LogCommand.Flush` / `LogCommand.Shutdown` 命令通过 suspend `send` 入队。
  channel 内部按 `BufferOverflow.DROP_OLDEST` 语义工作：正常负载（积压
  <1024）下 Line 不丢；**当 channel 满（1024 条未消费积压）时，send(Flush
  / Shutdown) 会挤掉最老 1 条 Line** —— 控制命令自身不丢（进入 channel
  后 FIFO 消费），但文件里会少最老的那 1 条 Line。此为 DROP_OLDEST 接受
  范围内的**既定降级**（控制面 > 数据面）；"flushForTest 调用前所有
  trySend 成功的 Line 已落盘"与"graceful handoff 零丢失"的契约仅在
  channel 未满时适用，过载时允许漏 1 条 Line。**独立控制 channel 方案
  被显式拒收**：复杂度与诊断收益不匹配（过载本身已是异常信号，丢 1 条
  最老 Line 不会放大误诊）。

#### Scenario: d() / v() / e() 调用主线程阻塞 < 0.1ms（硬区分 v1/v2）

- **GIVEN** `FileLogger.init(context)` 已完成
- **WHEN** 调用线程连续调用 `FileLogger.d(tag, message)` 1000 次，不插入任何 delay
- **THEN** 1000 次调用累计耗时 < 100ms（平均 < 0.1ms/次）
- **AND 硬区分 v1**：v1 每次 `FileWriter.use { ... }` 同步 open/write/close
  1-5ms，1000 次累计 1000-5000ms 必 FAIL 本断言
- **AND 硬区分 v2**：v2 `trySend` 非阻塞，1000 次累计 < 100ms

#### Scenario: FileWriter 抛 IOException 时业务调用方不感知

- **GIVEN** `FileLogger.init(context)` 已完成，IO flush 协程已启动
- **AND** 模拟 `filesDir` 满 / 只读，FileWriter 在 flush 时抛 `IOException`
- **WHEN** 业务线程调用 `FileLogger.d(tag, message)` 1 次
- **THEN** `FileLogger.d` 调用**不抛异常**，返回 Unit 正常
- **AND** 下一次 `FileLogger.d` 调用也**不**抛
- **AND** `android.util.Log.e("FileLogger", "flush failed", t)` 被调用记录到 logcat
- **AND 硬区分 v1**：v1 `FileWriter.use` 在主线程调用栈内抛 IOException →
  business code 看到 crash / 中断；v2 异常被 IO 协程吞下

#### Scenario: channel capacity 满时 DROP_OLDEST（非阻塞保证）

- **GIVEN** `FileLogger.init(context)` 已完成
- **AND** 模拟 IO 协程被阻塞（例如 FileWriter 调用挂起），channel 不消费
- **WHEN** 业务线程连续 `FileLogger.d` 2000 次（超过 capacity 1024）
- **THEN** 2000 次调用全部立即返回，**无**业务线程阻塞
- **AND** 最老的约 976 条 log 被 drop，最新的 1024 条保留在 channel 里

#### Scenario: 重复 init graceful handoff：init 返回后旧 batch 已同步落盘（硬区分 v1/v2）

- **GIVEN** `FileLogger.init(context)` 已完成，flush 协程启动
- **AND** 业务线程调用 `FileLogger.d` 若干次填满本地 batch（例如 32 条），
  但尚未到 `FLUSH_BATCH_SIZE=64` / `FLUSH_INTERVAL_MS=200` 触发 flush
- **WHEN** 业务线程再次调用 `FileLogger.init(context)`；`init` 头部向 channel
  发送 `LogCommand.Shutdown` 命令 + `runBlocking(Dispatchers.IO) {
  logChannel.send(Shutdown); oldJob.join() }` 同步等旧 job FIFO 排空 channel
  并优雅退出
- **THEN** **`init` 调用返回后**（同步阻塞结束），`debug_log.txt` **已经**
  含这 32 条日志（无需 polling / eventually 等待）
- **AND** 新 flush 协程启动并继续从同一 `logChannel` 消费后续日志（channel
  不重建，init 尾部 `d("==日志开始==")` 及业务日志由新 job 消费）
- **AND 硬区分 v1**：v1 `flushJob?.cancel()` 非 join 式 + 无 try/finally
  落盘，旧 batch 的 32 条静默丢失（init 返回后 `debug_log.txt` 找不到它们）
- **AND 硬区分 v5 Shutdown**：v5 Shutdown FIFO 排空确保 channel 中所有
  pre-init `Line` 被顺序消费后才退出循环，32 条在 init 返回时必已在
  `debug_log.txt` 里（v4 的 `cancelAndJoin` 存在 `Channel.receive` 交付竞态
  可能丢中间项，实测会偶现漏 msg-21 / msg-30 等）

#### Scenario: flushForTest 测试入口确定性刷新（P2-2 evaluator 要求）

- **GIVEN** `FileLogger.init(context)` 已完成
- **AND** 业务线程调用 `FileLogger.d(...)` 5 次（刚入队，未触发 batch / interval flush）
- **WHEN** 测试线程调用 `FileLogger.flushForTest()`（`@VisibleForTesting
  internal suspend fun`）
- **THEN** 该函数挂起直到当前 channel 里所有**已入队**的日志被 consumer
  `receive()` 并 `writeBatchSafe()` 落盘，然后返回
- **AND** `debug_log.txt` 含这 5 条日志
- **作用**：消除测试对 `delay(FLUSH_INTERVAL_MS)` 的时钟依赖，IOException /
  rotation 等场景的测试不再因 JVM / macOS / Android 时钟差异 flaky

#### Scenario: ThreadLocal formatter 保证多线程并发调用安全（P1 finding 1）

- **GIVEN** `FileLogger.init(context)` 已完成
- **AND** `dateFormat` 实现为 `ThreadLocal<SimpleDateFormat>`（每线程独享实例，
  避免 `SimpleDateFormat` 非线程安全的 `Calendar`/`NumberFormat` 内部状态
  在多业务线程间串扰）
- **WHEN** 多个协程（例如 16 个）并发连续调用 `FileLogger.d("TAG", "msg-$i")`
  若干次（例如每个协程 100 次）
- **THEN** 所有调用返回 Unit，**无**异常（任何线程都不抛
  `ArrayIndexOutOfBoundsException` / `NumberFormatException` 等
  SimpleDateFormat 并发经典错误）
- **AND** `flushForTest()` 后 `debug_log.txt` 行数 = 协程数 × 每协程调用数
  （加上 init 自带的 "===== 日志开始 =====" 一条）
- **AND 硬区分 v1**：v1 用共享 `SimpleDateFormat` 实例，多线程并发时概率性
  抛异常或产生格式错乱的 timestamp

#### Scenario: 满 buffer 降级契约 —— flushForTest 在 channel 满时挤掉最老 1 条 Line（P2 finding 2）

- **GIVEN** `FileLogger.init(context)` 已完成
- **AND** 通过反射 cancel flushJob 让 consumer 停摆（模拟过载 / IO 被堵塞）
- **AND** 业务线程 `FileLogger.d(...)` 1024 次把 channel 灌满
  （message "old-0" .. "old-1023" FIFO 占位，channel 满）
- **WHEN** 测试线程调用 `FileLogger.flushForTest()`（`send(Flush)` 入队时
  channel 已满，DROP_OLDEST 挤掉最老 "old-0"），后恢复 consumer
- **THEN** `flushForTest()` 正常返回（Flush 命令自身不丢）
- **AND** 恢复 consumer 后 `debug_log.txt` 含 "old-1" .. "old-1023" 共 1023 条
- **AND** **不含** "old-0"（被 send(Flush) 挤掉）
- **作用**：documented tradeoff，明确 DROP_OLDEST 压力下 Flush / Shutdown
  命令自身优先于 Line 存活；核销方基于此 Scenario 而非文字描述判定契约

---

### Requirement: FileLogger 写后 rotation 到 `.1` 辅文件（允许单 batch 短暂超阈值）

MUST 在 IO 协程每次 `writeBatchSafe(batch)` 写盘完成后检查
`currentLogFile.length()`，当 ≥ `MAX_FILE_BYTES`（定义为
`5 * 1024 * 1024 = 5_242_880` 字节）时执行 rotation：

1. 若 `rotatedLogFile`（`debug_log.txt.1`）已存在，`delete()` 它
2. `currentLogFile.renameTo(rotatedLogFile)`（把当前 log 移到 `.1`）
3. 创建新的空 `currentLogFile` 继续写

磁盘占用契约（**写后 rotate 策略，允许单 batch 短暂超阈值**，P2-3 evaluator
拍板）：

- **稳定态**：`debug_log.txt`（当前活跃） + `debug_log.txt.1`（最近 rotated）
  合计 ≤ `2 × MAX_FILE_BYTES = 10_485_760` 字节（≈ 10MB）
- **瞬态超阈值允许**：由于 rotation 在批次写完后检查，当前文件可能在 rotation
  触发前短暂达到 `MAX_FILE_BYTES + FLUSH_BATCH_SIZE × 平均行长 ≈ 5MB +
  12KB`；这是"写后 rotate"策略的固有代价，用简单性换严格上界，被本 spec
  接受
- **历史保留 1 份**：rotation 丢的是**倒数第二老**的 log（已经在 `.1` 里），
  最老的 `.1` 被覆盖
- **rotation 在 IO 协程内**：不阻塞业务

关键属性：

- **阈值单一真理源**：`MAX_FILE_BYTES = 5 * 1024 * 1024`（字节），spec 所有
  Scenario + tasks + 实现代码引用同一常量，避免 `5_000_000` vs
  `5 * 1024 * 1024` 不一致
- **写前检查被显式拒收**：预 rotate 策略（写入前按 `file.length() +
  pendingBytes >= MAX_FILE_BYTES` 提前 rotate）能做到单文件永不超阈值，但
  实现复杂（要计算 batch 序列化后字节长度、分片 rotate）；本 spec 选择写后
  rotate + 允许单 batch 短暂超阈值，见 Alternatives（proposal）说明

#### Scenario: 当前文件达 MAX_FILE_BYTES 时 rotate 到 .1

- **GIVEN** `FileLogger.init(context)` 完成
- **AND** 模拟写入让 `debug_log.txt.length() >= 5 * 1024 * 1024`（= 5_242_880 字节）
- **WHEN** 下一次 `writeBatchSafe` 批次完成后调用 `checkAndRotate()`（或
  通过 `flushForTest()` 确定性触发）
- **THEN** `debug_log.txt` 存在，大小 < `MAX_FILE_BYTES`（新空文件）
- **AND** `debug_log.txt.1` 存在，大小 ≈ `MAX_FILE_BYTES`（rotated 的旧内容；
  可能略超阈值一个 batch，因为 rotation 在 batch 写后检查）

#### Scenario: 第二次 rotation 时 .1 被覆盖（旧 rotated 被丢）

- **GIVEN** 已发生过 1 次 rotation（`debug_log.txt.1` 存在且内容 = 第一批
  ≈ `MAX_FILE_BYTES` 字节）
- **AND** 当前 `debug_log.txt` 又写满到 ≥ `MAX_FILE_BYTES`
- **WHEN** 第二次 rotation 触发
- **THEN** `debug_log.txt.1` 被覆盖为第二批内容，第一批丢失
- **AND** 磁盘总占用 ≤ `2 × MAX_FILE_BYTES + 单 batch 短暂超量`

---

### Requirement: FileLogger 级别过滤 MUST 在入队前生效（25Hz 高频默认不写）

`FileLogger` MUST 维护 `private var currentLevel: LogLevel`，默认值
`LogLevel.DEBUG`。提供 `fun setLevel(level: LogLevel)` 运行时修改（仅开发者
调试用）。

`enqueue(level, tag, message)` MUST 在检查 `level >= currentLevel` 通过后才
格式化 log line 并 `trySend` 到 channel；若级别不足，MUST 立即返回，不执行
任何后续处理（零 I/O，零字符串构造开销除调用方已传入的 message string）。

LogLevel ordinal 顺序：`VERBOSE < DEBUG < INFO < WARN < ERROR`。

关键属性：

- **默认 DEBUG**：25Hz 高频路径 3 条日志改为 VERBOSE 后，默认不写入，彻底
  消除每秒 75 次 I/O 源头
- **setLevel 运行时**：开发者可以在 debug build 里 `FileLogger.setLevel(VERBOSE)`
  临时开启全量日志做 bug 复现
- **非级别过滤的其他 log 不受影响**：低频 DEBUG（偶发异常 + 1 次性 startup）
  继续 100% 打印

#### Scenario: 默认级别 DEBUG 下 verbose 日志不写入（硬区分 v1/v2）

- **GIVEN** `FileLogger.init(context)` 完成（默认 `currentLevel == DEBUG`）
- **WHEN** 调用 `FileLogger.v(tag, message)` 100 次
- **AND** flush 批次完成
- **THEN** `debug_log.txt` 内容**不含**这 100 条 verbose 消息
- **AND** channel 里没有 verbose log（级别过滤在 `enqueue` 入口就退）
- **AND 硬区分 v1**：v1 无级别概念，`d()` 一律写盘；v2 verbose 在级别过滤层
  被拦下

#### Scenario: setLevel(VERBOSE) 后 verbose 日志正常写入

- **GIVEN** `FileLogger.init(context)` 完成
- **AND** 调用 `FileLogger.setLevel(LogLevel.VERBOSE)`
- **WHEN** 调用 `FileLogger.v(tag, message)` 1 次
- **AND** flush 批次完成
- **THEN** `debug_log.txt` 含这一条 verbose 消息

#### Scenario: DEBUG 日志在任意级别 ≥ DEBUG 时都写入（回归保护）

- **GIVEN** `FileLogger.init(context)` 完成（默认 DEBUG）
- **WHEN** 调用 `FileLogger.d(tag, "normal path")` 1 次
- **AND** flush 批次完成
- **THEN** `debug_log.txt` 含这条 debug 消息
- **AND** 作用：锁定本 change 不破坏已有 DEBUG 日志路径

---

### Requirement: 25Hz 高频 call site 改用 FileLogger.v() + 坐标 3 位小数

MUST 把 `LapTimingEngine.kt:70`（每帧 detector 结果） + `TestSessionViewModel.kt:335`
（每帧 bridge 推进） + `TestSessionViewModel.kt:373`（每帧 bridge 结果）三个
25Hz 高频 call site 改为调用 `FileLogger.v(...)`（而非 `FileLogger.d(...)`），
并且 MUST 把日志内容里的 `latitude` / `longitude` 字段（含 `prev.latitude` /
`prev.longitude`）用 `"%.3f".format(...)` 格式化（3 位小数，~100m 精度）。具体
满足：

1. 调用 `FileLogger.v(...)` 而非 `FileLogger.d(...)`
2. 日志内容中的 `latitude` / `longitude`（含 `prev.latitude` / `prev.longitude`）
   MUST 用 `String.format("%.3f", latitude)` 格式化（3 位小数，~100m 精度）
3. **不**改变日志的其他字段（`targetGate` / `ts` / `accepted` / `reason` 等
   保持原精度）
4. MUST 在 call site 前加 `if (FileLogger.isVerboseEnabled)` 早退守卫，避免
   verbose 关闭时仍执行昂贵字符串插值

关键属性：

- **隐私保护**：verbose 打开时泄露精度从 ~1cm 降到 ~100m，够看轨迹走势，不够
  精确定位
- **诊断可用**：3 位小数能看出是否接近 gate / 是否穿过某街区；诊断场景够用
- **性能优化 + 级别过滤 + 精度降级三合一**：一个 call site 一次改完

其他 9 个 FileLogger 调用点（低频 / 偶发异常 / 一次性 startup）**不**做精度
降级，保持原 `FileLogger.d(tag, ...)`（发生时需要完整精度做定位复盘）。

#### Scenario: LapTimingEngine.kt:70 detector 日志用 v() + 3 位小数

- **GIVEN** `FileLogger.setLevel(LogLevel.VERBOSE)` + `FileLogger.init(context)`
- **WHEN** `engine.processSample(session, track, prevSample, currentSample)`
  执行，其中 `prevSample.latitude == 30.5826543`（完整精度）
- **AND** flush 批次完成
- **THEN** `debug_log.txt` 含一条 VERBOSE 日志，其中 `prev=(30.583,...)` 而
  **非** `prev=(30.5826543,...)`
- **AND 硬区分 v1**：v1 `FileLogger.d("... prev=(${prev.latitude},...)")`
  输出完整 7 位小数；v2 `FileLogger.v("... prev=(${"%.3f".format(prev.latitude)},...)")`
  输出 3 位小数

#### Scenario: TestSessionViewModel.kt:335 bridge 日志用 v() + 3 位小数

- **GIVEN** `FileLogger.setLevel(LogLevel.VERBOSE)` + `FileLogger.init(context)`
- **WHEN** `bridgeGpsToLapTiming(gpsData)` 执行，`gpsData.latitude == 30.5826543`
- **AND** flush 批次完成
- **THEN** `debug_log.txt` 含 VERBOSE 日志，`lat=30.583` 而非 `lat=30.5826543`

#### Scenario: 默认 DEBUG 级别下 25Hz 3 条高频日志零写入（回归保护）

- **GIVEN** `FileLogger.init(context)` 默认 DEBUG
- **WHEN** 模拟 25Hz 采集运行 1 秒（25 帧 × 3 条高频 = 75 条 `FileLogger.v()` 调用）
- **AND** flush 批次完成
- **THEN** `debug_log.txt` **不含**这 75 条 verbose 消息
- **AND** 即使其他低频 DEBUG 日志（例如 ts 回跳 drop）写入，也不会含 verbose 内容

#### Scenario: 低频异常日志保持原 d() 和原精度（回归保护）

- **GIVEN** `FileLogger.init(context)` 默认 DEBUG
- **WHEN** 触发 `LapTimingEngine.kt:61` ts 回跳 drop（单次）
- **AND** flush 批次完成
- **THEN** `debug_log.txt` 含该条 DEBUG 日志，prevTs / curTs 字段保持完整 Long
  精度
- **AND** 作用：锁定本 change 不误改低频异常日志格式
