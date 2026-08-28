# fix-file-logger-and-engine-coord-hygiene · 第 1 轮 spec/tasks review

- **日期**：2026-04-24
- **评审对象**：
  - `openspec/changes/fix-file-logger-and-engine-coord-hygiene/proposal.md`（434 行）
  - `openspec/changes/fix-file-logger-and-engine-coord-hygiene/specs/file-logger-and-coord-hygiene/spec.md`（226 行，4 Requirement / 12 Scenario）
  - `openspec/changes/fix-file-logger-and-engine-coord-hygiene/tasks.md`（310 行）
- **覆盖攻击点**：A18 + A39
- **评审方**：codex session
- **实施方**：session 2
- **轮次**：第 1 轮结构化 review
- **结论**：🔴 拒绝进入 `/opsx:apply`
- **前置条件**：P1/P2 全部修订并重新 `openspec validate fix-file-logger-and-engine-coord-hygiene --strict`

## 0. 结论摘要

整体方向认可：A18 与 A39 合并是合理的，`LogLevel` + 高频坐标降级到 `v()` + 默认 DEBUG 早退，能直接砍掉 25Hz 热路径日志写入。

但当前 spec/tasks 还有 1 个契约漏洞和 3 个可执行性问题：重复 `init` 的“不丢已入队日志”与取消旧 consumer 的实现草案不相容；测试任务使用 JUnit5 的 `@TempDir` 但模块仍是 JUnit4；IOException / rotation 测试缺少 deterministic flush/test hook；rotation 上界声明与“写完批次后再检查”矛盾。

## 1. 🔴 P0 / P1

### P1-1 · 重复 init 的不丢日志契约与取消旧 flush job 冲突

- **位置**：`spec.md:48-49`，`tasks.md:51-58`，`tasks.md:88-105`
- **问题**：spec 写明重复 `FileLogger.init(context)` 时“已在 channel 里的未 flush log 由新协程消费（不丢失）”。但 tasks 的实现草案先 `flushJob?.cancel()`，旧协程本地 `batch` 中已经 `receive()` 出 channel、但尚未 `writeBatchSafe()` 的日志不会再回到 channel，新协程也消费不到。
- **后果**：一旦业务在首个 flush interval 内重复 init，日志可静默丢失，R1 的幂等 init 契约无法核销。更糟的是测试若只查“不崩溃”，会漏掉这个已出队未落盘窗口。
- **修订建议**：二选一拍板。若要求不丢，tasks 必须加入 graceful handoff：取消旧 job 前/取消时 flush 本地 batch，例如 `consumeAndFlushLoop` 用 `try/finally { if (batch.isNotEmpty()) writeBatchSafe(batch) }`，并让 `init` 的重启路径等待旧 job 完成或复用同一个 active consumer。若不承诺不丢，则删除 `spec.md:48-49` 的“不丢失”表述，改为“重复 init 不崩溃；已进入旧 batch 但未 flush 的日志为 best-effort”，并加对应测试。

## 2. 🟡 P2

### P2-1 · FileLoggerTest 任务使用了当前模块没有的 `@TempDir`

- **位置**：`tasks.md:207-210`
- **问题**：tasks 要求“JUnit4 + `@TempDir`”，但 `@TempDir` 是 JUnit5 Jupiter API；当前 `feature/test/build.gradle.kts` 只有 JUnit4 风格依赖与 Mockito，并未配置 Jupiter runner/engine。
- **后果**：实施方照 tasks 新建测试会直接 compile fail，或者被迫引入 JUnit5，扩大本 change scope。
- **修订建议**：改为 JUnit4 可执行写法：`@get:Rule val tempFolder = TemporaryFolder()`，或在 `@Before` 中用 `Files.createTempDirectory(...)` 并在 `@After` 清理。tasks §3.1 同步删除 `@TempDir`。

### P2-2 · IOException / rotation 测试缺少确定性 flush 入口

- **位置**：`tasks.md:217-230`，`spec.md:60-69`，`spec.md:100-116`
- **问题**：R1/R2 测试要求模拟 `FileWriter` 抛异常、触发一次 flush、观察 rotation，但 tasks 没有要求为异步 logger 提供 deterministic drain/flush 测试入口。只靠只读路径、反射取消 `flushJob`、等待 interval 或 mock 构造器，会在 JVM/macOS/Android 单测之间变成不稳定门槛。
- **后果**：代码可能正确但测试偶发失败；也可能测试只验证 enqueue，不验证 IO 协程的异常吞吐与 rotation。
- **修订建议**：tasks §2 增加明确的 test seam，例如 `@VisibleForTesting internal suspend fun flushForTest()` / `drainForTest()`、可注入 `CoroutineDispatcher` / `Clock` / writer factory，或至少把 `writeBatchSafe` 与 `checkAndRotate` 调整到 package-visible 并用直接单元测试覆盖。不要把“只读路径是否抛 IOException”作为唯一机器门槛。

### P2-3 · rotation 上界声明与批量写后检查互相矛盾

- **位置**：`spec.md:83-96`，`spec.md:100-106`，`tasks.md:226-230`
- **问题**：spec 说 flush 批次完成后检查 `currentLogFile.length()` 并 rotation，同时又说“不会出现单文件 > 5MB”。如果先 append 一个 batch 再检查，当前文件在 rotation 前必然可能超过阈值一个 batch 的大小。此外 Requirement 用 `5 * 1024 * 1024`，Scenario 又写 `5_000_000`，二进制/十进制阈值不一致。
- **后果**：实现方无法同时满足“写后检查”和“单文件永不 > 5MB”；测试按 `5_000_000` 预写也可能不足以触发 `5 * 1024 * 1024` 阈值。
- **修订建议**：统一阈值为 `MAX_FILE_BYTES = 5 * 1024 * 1024`。若要“单文件永不超过阈值”，必须在写入前按 `file.length() + pendingBytes >= MAX_FILE_BYTES` 预 rotate；若接受写后 rotate，则把上界改为“稳定态最多保留 current + .1；current 在单次 flush 内可短暂超过阈值一个 batch”，并调整测试断言。

## 3. 🟡 P3

暂无。

## 4. proposal / 上游遗留

本轮未发现 A18/A39 合并 scope 本身的问题；问题集中在 spec/tasks 的可执行性与边界契约。

## 5. 🟢 已充分认可

- A18 与 A39 合并是对的：FileLogger 异步化、日志级别和坐标精度降级都作用在同一条热路径，拆开反而容易重复返工。
- 默认 DEBUG、热路径 `v()`、低频事件保留 `d()` 的分层合理，能保持排障能力同时降低 25Hz 高频写盘。
- `DROP_OLDEST` 比阻塞业务线程更符合测试界面场景，风险取舍清楚。

## 6. 给实施方的回复模板

本轮暂不放行 `/opsx:apply`。请先修 4 点：重复 init 的“不丢日志”契约要么实现 graceful flush/handoff、要么降级为 best-effort；FileLoggerTest 改 JUnit4 可执行的临时目录方案；为异步 flush/IOException/rotation 增加确定性测试入口；rotation 阈值统一为 `5 * 1024 * 1024`，并在“预 rotate”与“写后 rotate 允许短暂超阈值”之间拍板。修完后重跑 `openspec validate fix-file-logger-and-engine-coord-hygiene --strict` 再提第二轮 mini review。
