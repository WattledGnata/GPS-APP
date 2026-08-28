# fix-file-logger-and-engine-coord-hygiene · 第 2 轮 code review

- **日期**：2026-04-24
- **评审对象**：
  - `da3f537` 主体实现
  - `d3e2496` 核销修补
- **覆盖攻击点**：A18 / A39
- **评审方**：codex session
- **实施方**：session 2
- **轮次**：代码落地后第 2 轮核销 review
- **结论**：🔴 暂不核销；Round 2 继续暂缓

## 0. 结论摘要

第 1 轮的三条 finding 主体修补方向正确：

- `SimpleDateFormat` 已改为 `ThreadLocal<SimpleDateFormat>`。
- `DROP_OLDEST` 下 `Flush/Shutdown` 满队列会挤掉最老 `Line` 的降级契约已写入代码注释与 spec。
- `drop_oldest` 测试里的 `drop-0 ` 假断言已改为按行 `endsWith("drop-0")`。

但 `d3e2496` 新增的并发 smoke test 与刚拍板的降级契约冲突：它一次并发写入 1600 条日志，超过 channel capacity 1024，却断言早期消息 `concurrent-0-0` 必须落盘。本机实跑 `./gradlew :feature:test:testDebugUnitTest --tests "*FileLoggerTest*"` 已失败，合流门槛红灯。

## 1. 🔴 P0 / P1

### Finding 1 · 新并发测试超过 channel 容量，当前合流门槛失败

- **位置**：`feature/test/src/test/java/com/blazepush/feature/test/FileLoggerTest.kt:403-427`
- **问题**：
  - 测试创建 `16 × 100 = 1600` 次 `FileLogger.d()` 并发调用。
  - `FileLogger` channel capacity 是 1024，且已经明确采用 `DROP_OLDEST`。
  - 测试又断言 `concurrent-0-0`、`concurrent-0-99` 等早期样本必须存在。
- **实际复现**：
  - 命令：`./gradlew :feature:test:testDebugUnitTest --tests "*FileLoggerTest*"`
  - 结果：FAILED
  - 失败消息：`并发写入应无线程安全问题 / 消息丢失：concurrent-0-0 应在文件里`
- **影响**：
  - 这不是业务代码行为失败，而是测试本身与降级契约矛盾。
  - 在 consumer 调度稍慢时，早期日志按 `DROP_OLDEST` 被丢弃是允许行为；测试把允许行为当成失败，导致门槛摇摆甚至当前直接红。
- **建议修复**：
  - 若该测试只验证 `ThreadLocal<SimpleDateFormat>` 并发安全，写入量应低于容量，例如 `16 × 32 = 512`，再断言抽样消息落盘。
  - 或保留 1600 压力，但只断言“不抛异常 + 最新尾部样本存在”，不要断言早期样本必在。
  - 测试注释也要从“所有日志都落盘”改成与 `DROP_OLDEST` 契约一致的表述。

## 2. 🟡 P2

暂无新增 P2。

## 3. 🟡 P3

### P3 · 统计注释仍写 14 / R1×5

- **位置**：`feature/test/src/test/java/com/blazepush/feature/test/FileLoggerTest.kt:31-38`
- **问题**：文件头注释仍写 `14 条 Scenario`、`R1 × 5`，但当前已是 16 条 / R1×7。
- **建议**：随 P1 测试修补一并更新，避免文档和测试数量不一致。

## 4. 已确认通过的部分

- `ThreadLocal<SimpleDateFormat>` 修补方向正确。
- `drop_oldest_whenChannelFull_doesNotBlock` 的 `drop-0` 断言已改为按行精确匹配，闭合第 1 轮 P2。
- full-buffer 降级契约已明确为“控制命令自身不丢，最老 Line 可丢”，符合本轮拍板。
- `openspec validate fix-file-logger-and-engine-coord-hygiene --strict` 通过。

## 5. 本轮执行的复核命令

```bash
git show --stat --oneline --decorate d3e2496
openspec validate fix-file-logger-and-engine-coord-hygiene --strict
./gradlew :feature:test:testDebugUnitTest --tests "*FileLoggerTest*"
rg -n "零丢失|所有.*Line|所有.*trySend|满 buffer|DROP_OLDEST|SimpleDateFormat|ThreadLocal|full-buffer|Flush|Shutdown|Scenario" \
  openspec/changes/fix-file-logger-and-engine-coord-hygiene \
  feature/test/src/main/java/com/blazepush/feature/test/FileLogger.kt \
  feature/test/src/test/java/com/blazepush/feature/test/FileLoggerTest.kt
```

## 6. 给实施方的回复模板

`d3e2496` 方向认可，但 F 战役仍暂不核销。当前 `FileLoggerTest` 在本机实跑失败：新增并发 smoke 一次写 1600 条，超过 channel capacity 1024，却断言早期日志必须落盘，和 `DROP_OLDEST` 降级契约冲突。请把并发 smoke 改成低于容量的并发安全测试，或保留 1600 压力但只断言不抛 + 尾部样本存在；同时更新文件头 14/R1×5 的统计注释。修完重跑 `:feature:test:testDebugUnitTest --tests "*FileLoggerTest*"` 后再提第 3 轮核销。
