# fix-file-logger-and-engine-coord-hygiene · 第 2 轮 mini review

- **日期**：2026-04-24
- **评审对象**：
  - `openspec/changes/fix-file-logger-and-engine-coord-hygiene/proposal.md` v2
  - `openspec/changes/fix-file-logger-and-engine-coord-hygiene/specs/file-logger-and-coord-hygiene/spec.md` v2
  - `openspec/changes/fix-file-logger-and-engine-coord-hygiene/tasks.md` v2
- **覆盖攻击点**：A18 + A39
- **评审方**：codex session
- **实施方**：session 2
- **轮次**：第 2 轮 mini review（复核第 1 轮 4 条 blocker）
- **结论**：🔴 拒绝进入 `/opsx:apply`
- **前置条件**：P1/P2 全部修订并重新 `openspec validate fix-file-logger-and-engine-coord-hygiene --strict`

## 0. 结论摘要

v2 已正确吸收大方向：`@TempDir` 改 JUnit4、引入 `flushForTest`、rotation 选择“写后 rotate + 单 batch 短暂超阈值”、并尝试用 `try/finally` 修 graceful handoff。

但 graceful handoff 仍未闭合：tasks 草案先替换 `currentLogFile` 并 `writeText("")`，再 cancel 旧 job；旧 job 的本地 batch 仍可能写到新文件并被清空，或 finally 异步执行导致测试读文件时还没落盘。此外 `flushForTest` 仍是空占位，TemporaryFolder 示例引入了当前模块没有的 `whenever`，rotation 的“≤10MB”旧叙述仍残留。

## 1. 🔴 P0 / P1

### P1-1 · graceful handoff 仍有竞态，且不能保证 init 后立即落盘

- **位置**：`tasks.md:54-64`，`tasks.md:113-121`，`spec.md:48-55`，`spec.md:85-98`
- **问题**：v2 的 `init` 草案先执行：
  1. `currentLogFile = File(...)`
  2. `currentLogFile?.writeText("")`
  3. `flushJob?.cancel()`
  4. `flushJob = scope.launch { ... }`

  旧 flush job 在第 1-3 步期间仍可能运行；它的 `finally { writeBatchSafe(batch) }` 又读取 object 级 `currentLogFile`，不是旧 job 启动时捕获的 file。结果存在两种竞态：

  - 旧 job 在 `currentLogFile` 已换成新文件后写 batch，然后 `writeText("")` 把它清空
  - `init` 返回后旧 job 的 finally 还没执行，tasks §3.4b 立刻读 `debug_log.txt` 会偶发 0 命中

- **后果**：第 1 轮 P1 的“旧 batch 不丢”并未真正闭合；按当前 tasks 写出来的 graceful handoff 测试会 flaky，甚至在不同调度下真实丢日志。
- **修订建议**：二选一拍板并写进 spec/tasks。
  - **强契约方案**：在替换/清空 `currentLogFile` 前先停止旧 job，并确定旧 batch 已落盘，例如 `runBlocking(Dispatchers.IO) { oldJob.cancelAndJoin() }`，或让 `init` 改成内部串行化的同步 handoff。此方案允许 `init` 有少量 IO 等待，但换来可证明不丢。
  - **弱契约方案**：把 spec 从“init 后必可见 / 不丢”降级为“best-effort eventual drain”，测试用 `eventually` 或 `flushForTest` 等待旧 job 完成，并明确重复 init 期间不承诺旧 batch 立即可读。若保留“不丢”，就不能先 `writeText("")` 再 cancel。

## 2. 🟡 P2

### P2-1 · `flushForTest` 仍是空占位，测试门槛不可执行

- **位置**：`tasks.md:148-159`，`tasks.md:272-285`，`tasks.md:299-315`，`spec.md:100-110`
- **问题**：v2 把 IOException / rotation 测试都改成依赖 `FileLogger.flushForTest()`，但 tasks 的实现块里该函数仍是空函数，只写“实现细节由实施方选”。`Channel<String>` + 本地 `batch` 架构下，单纯等待 channel empty 不足以保证本地 batch 已 `writeBatchSafe`；需要明确的控制消息 / `CompletableDeferred` / mutex 等机制。
- **后果**：实施方照代码块复制会编译但测试必失败；若自由发挥，可能只 drain channel 不 flush batch，继续漏掉原 P2 的确定性问题。
- **修订建议**：tasks §1.2 给出一个具体可执行方案。推荐把 `Channel<String>` 改成 `Channel<LogCommand>`，例如 `data class Line(...)` + `data class Flush(val ack: CompletableDeferred<Unit>)`；consumer 收到 `Flush` 时先写当前 batch，再 `ack.complete(Unit)`。`flushForTest()` 发送 Flush 并 `await()`。这样“调用前已入队日志落盘”才有可测语义。

### P2-2 · TemporaryFolder 示例使用了未引入的 Mockito-Kotlin `whenever`

- **位置**：`tasks.md:253-260`
- **问题**：feature/test 目前只有 `mockito-core` / `mockito-inline`，没有 `org.mockito.kotlin:mockito-kotlin`；现有测试也使用 `doReturn(...).\`when\`(...)` 风格。tasks 示例里的 `whenever(filesDir).thenReturn(dir)` 会 unresolved reference，除非额外加依赖。
- **后果**：修掉 `@TempDir` 后仍会在测试编译阶段踩坑。
- **修订建议**：改成现有依赖可用的写法：
  ```kotlin
  tempContext = mock(Context::class.java)
  doReturn(dir).`when`(tempContext).filesDir
  ```
  或明确新增 `mockito-kotlin` 依赖并把它纳入 tasks / commit scope。建议用前者，scope 更小。

### P2-3 · rotation “≤10MB”旧契约仍有残留

- **位置**：`spec.md:15`，`proposal.md:217-237`，`proposal.md:325`，`tasks.md:389`
- **问题**：v2 已在 R2 正文和 Alternative E 拍板“写后 rotate，允许稳定态 ≤10MB + 瞬态一个 batch”，但顶部核心原则、proposal R2 契约、行为变更表和 commit body 仍写“磁盘占用恒定 ≤10MB / 上限 = 10MB”。
- **后果**：实施方和后续评审会看到两套互相冲突的 rotation 契约；按旧句子核销会再次要求严格上界，和 v2 拍板相冲突。
- **修订建议**：全文件统一成一个表述：`稳定态 current + .1 ≤ 2 × MAX_FILE_BYTES；写后 rotate 允许单 batch 瞬态超量，严格总上界为 Non-goal`。同时把 proposal 代码块里的 `MAX_LOG_FILE_BYTES` 改成 `MAX_FILE_BYTES`，避免常量名又分裂。

## 3. 🟡 P3

### P3-1 · 文案重复与测试数量残留

- **位置**：`spec.md:180`，`tasks.md:180`，`tasks.md:393`，`proposal.md:108-109`
- **问题**：`关键属性` / task 2.1 行重复；commit body 仍写 FileLoggerTest 12 条，但 v2 已变成 15 条 Scenario；proposal 仍说 8-10 条 Scenario。
- **建议**：顺手清理，避免实施方按旧数量报绿。

## 4. proposal / 上游遗留

proposal 的 R1/R2 示例仍大段保留 v1 结构（无 `try/finally`、无 `flushForTest`、旧 `MAX_LOG_FILE_BYTES`），与 v2 spec/tasks 不完全同步。若实施方主要照 tasks 做，风险可控；但作为 change 三件套，proposal 至少要删掉会误导的旧代码块或补一段“最终以 spec/tasks v2 为准”。

## 5. 🟢 已充分认可

- 选择 JUnit4 `TemporaryFolder` 是正确方向。
- 引入测试专用 deterministic flush 入口是必要的，方向对。
- rotation 选择写后 rotate 可以接受，只要全文件统一“稳定态 + 瞬态超量”的契约。
- 高频 3 个 call site 加 `isVerboseEnabled` 守卫比只靠 `enqueue` 过滤更稳，能避免字符串插值成本。

## 6. 给实施方的回复模板

v2 暂不放行 `/opsx:apply`。请先修 3 个必修点：graceful handoff 不能先换文件/清空再 cancel 旧 job，需要可证明不丢或降级为 best-effort；`flushForTest` 必须给出具体实现机制，推荐 `Channel<LogCommand>` + `Flush(ack)`；TemporaryFolder 示例改成现有 Mockito `doReturn(...).\`when\`` 写法；rotation 全文统一为“稳定态 ≤ 2×MAX_FILE_BYTES，允许单 batch 瞬态超量”。修完后重跑 `openspec validate fix-file-logger-and-engine-coord-hygiene --strict` 再提第三轮 mini review。
