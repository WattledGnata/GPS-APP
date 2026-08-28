# fix-file-logger-and-engine-coord-hygiene · 第 3 轮 mini review

- **日期**：2026-04-24
- **评审对象**：
  - `openspec/changes/fix-file-logger-and-engine-coord-hygiene/proposal.md` v3
  - `openspec/changes/fix-file-logger-and-engine-coord-hygiene/specs/file-logger-and-coord-hygiene/spec.md` v3
  - `openspec/changes/fix-file-logger-and-engine-coord-hygiene/tasks.md` v3
- **覆盖攻击点**：A18 + A39
- **评审方**：codex session
- **实施方**：session 2
- **轮次**：第 3 轮 mini review（复核 v2 的 P1/P2）
- **结论**：🟡 暂不进入 `/opsx:apply`，修 2 个 P2 后可放行
- **前置条件**：P2 全部修订并重新 `openspec validate fix-file-logger-and-engine-coord-hygiene --strict`

## 0. 结论摘要

v3 已实质修掉上一轮的关键问题：

- graceful handoff 改为 `cancelAndJoin` 强等待，方向正确。
- `flushForTest` 从空占位升级为 `Channel<LogCommand>` + `Flush(ack)`，具备可执行语义。
- TemporaryFolder 示例改成现有 Mockito `doReturn(...).\`when\`` 风格。
- rotation 的严格上界旧说法大体已统一为“稳定态 + 单 batch 瞬态超量”。

剩余问题是 spec/tasks 自身同步不足：spec 仍写 `Channel<String>`，与 `Flush` 命令机制冲突；spec 仍说 finally 不阻塞 init 调用方，和 v3 强契约 `runBlocking(cancelAndJoin)` 冲突；DROP_OLDEST 测试任务也还停留在旧的 “反射 logChannel.trySend” 写法，未适配 private `LogCommand`。

## 1. 🔴 P0 / P1

暂无。

## 2. 🟡 P2

### P2-1 · spec 仍声明 Channel<String>，与 flushForTest 的 LogCommand 方案冲突

- **位置**：`spec.md:35-38`，`spec.md:102-110`，`tasks.md:54-65`，`tasks.md:192-210`
- **问题**：tasks v3 已把 channel payload 升级成 `Channel<LogCommand>`，用 `Line` / `Flush(ack)` 实现 `flushForTest`。但 spec Requirement 仍写 `Channel<String>(capacity = 1024, onBufferOverflow = DROP_OLDEST)`，这会让实现方按 spec 写成 String channel，无法承载 Flush 控制命令。
- **后果**：spec 和 tasks 的核心数据结构冲突；后续核销时无法同时要求 “MUST 持有 Channel<String>” 和 “MUST 支持 Flush(ack)”。
- **修订建议**：把 spec 改为结构性要求而不是固定 String payload：
  - `FileLogger MUST 持有 capacity=1024、DROP_OLDEST 的异步 channel；payload MUST 支持普通日志行与测试 flush 控制命令。推荐实现为 Channel<LogCommand>，含 Line(content) 与 Flush(ack)。`
  - `flushForTest` Scenario 明确通过 Flush 控制命令完成 deterministic drain。

### P2-2 · spec 仍说 graceful handoff 不阻塞 init 调用方

- **位置**：`spec.md:50-57`，`tasks.md:79-97`
- **问题**：v3 tasks 采用强契约：`init` 开头 `runBlocking(Dispatchers.IO) { oldJob.cancelAndJoin() }`，同步等待旧 job 的 `finally` 落盘完成后再替换 file 引用。这必然会阻塞 `init` 调用方直到旧 batch 写完。可 spec 仍写 “finally 块在 cancel 触发时执行，在 IO 线程完成，不阻塞 init 调用方”。
- **后果**：这一句会重新放宽到 v2 被拒绝的非 join 式异步 drain；也会让性能预期错误地以为重复 init 永不等待 IO。
- **修订建议**：改成：`重复 init MAY 短暂阻塞调用方以等待旧 flush job cancelAndJoin；该阻塞只发生在 init 生命周期路径，不影响 d/v/e 的业务热路径非阻塞契约。` 同时在 graceful handoff Scenario 里写明“init 返回后旧 batch 已落盘”。

### P2-3 · DROP_OLDEST 测试任务未适配 private LogCommand channel

- **位置**：`tasks.md:366-369`
- **问题**：tasks 仍写“反射取 logChannel、连续 trySend 2000 次”。v3 后 channel 类型是 `Channel<LogCommand>`，且 `LogCommand` 是 private sealed class；测试侧不能自然构造 `LogCommand.Line`。更稳的测试方式应从公开 API `FileLogger.d(...)` 灌入 2000 条，或者显式把测试 seam 写出来。
- **后果**：实施方照 tasks 写会被 private sealed class 卡住，或者为了测试去放宽生产可见性。
- **修订建议**：把 §3.4 改成基于公开 API：
  - cancel/暂停 consumer 或注入测试开关使 channel 暂不消费；
  - 调用 `FileLogger.d(TAG, "drop-$i")` 2000 次并断言调用耗时；
  - 恢复 consumer 后用 `flushForTest()` drain；
  - 断言早期消息如 `drop-0` 不在文件中、尾部消息如 `drop-1999` 在文件中。

## 3. 🟡 P3

### P3-1 · proposal 仍有旧骨架残留

- **位置**：`proposal.md:151-177`，`proposal.md:361-364`
- **问题**：proposal 的概念代码块仍展示 `Channel<String>` / 非 join `flushJob?.cancel()`；风险表仍提 `@TempDir`。虽然 line 225 已说明完整实现以 tasks 为准，但这些残留会干扰阅读。
- **建议**：把旧代码块改成摘要或指向 tasks §1.2；`@TempDir` 改为 `TemporaryFolder`。

## 4. proposal / 上游遗留

无新的上游决策问题。A18/A39 合并、写后 rotate、强 handoff、LogCommand flush seam 这些决策现在都可接受。

## 5. 🟢 已充分认可

- `runBlocking(Dispatchers.IO) { oldJob.cancelAndJoin() }` 放在替换 `currentLogFile` 前，修正了 v2 的竞态。
- `initialized` + `resetForTest()` 解决了重复 init 不清空与测试隔离之间的张力。
- `Flush(ack)` 是正确的 deterministic flush 测试 seam，比 delay 或 channel empty 更可靠。
- rotation 的最终契约“稳定态 ≤ 2×MAX_FILE_BYTES + 单 batch 瞬态超量”已基本统一。

## 6. 给实施方的回复模板

v3 没有 P1，剩 2-3 个同步类 P2。请把 spec 的 `Channel<String>` 改成支持 `Line/Flush` 的命令 channel 契约；把 graceful handoff 的“不阻塞 init 调用方”改成“重复 init 可短暂等待 cancelAndJoin，d/v/e 热路径仍非阻塞”；把 DROP_OLDEST 测试任务从反射 private `LogCommand` 改成通过公开 `FileLogger.d(...)` 灌入并 drain 验证。顺手清 proposal 的 `@TempDir` / `Channel<String>` 旧骨架。修完 validate 通过后，我建议放行 `/opsx:apply`。
