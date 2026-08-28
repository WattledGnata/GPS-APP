# fix-file-logger-and-engine-coord-hygiene · 第 4 轮 mini review

- **日期**：2026-04-24
- **评审对象**：
  - `openspec/changes/fix-file-logger-and-engine-coord-hygiene/proposal.md` v4
  - `openspec/changes/fix-file-logger-and-engine-coord-hygiene/specs/file-logger-and-coord-hygiene/spec.md` v4
  - `openspec/changes/fix-file-logger-and-engine-coord-hygiene/tasks.md` v4
- **覆盖攻击点**：A18 + A39
- **评审方**：codex session
- **实施方**：session 2
- **轮次**：第 4 轮 mini review（复核 v3 的 P2/P3）
- **结论**：🟢 准予进入 `/opsx:apply`
- **前置条件**：实施阶段按 tasks §4 全量跑合流门槛；代码 commit 后交评审方做 code review 与 backlog 核销。

## 0. 结论摘要

v3 剩余同步问题已闭合：

- spec 已从 `Channel<String>` 固定实现改为结构性异步 channel 契约，payload 支持 `Line` + `Flush(ack)`。
- graceful handoff 已明确：重复 `init` 可短暂等待 `cancelAndJoin`；`d/v/e` 业务热路径仍非阻塞；`init` 返回后旧 batch 已同步落盘。
- DROP_OLDEST 测试已改为公开 API 驱动：`FileLogger.d(...)` 灌入 2000 条，恢复 consumer 后 `flushForTest()` drain，再断言早期消息被丢、尾部消息保留。
- `@TempDir` / `whenever` / `Channel<String>` 残留均为“不得使用”或“从旧方案升级”的教学性反例，不再是执行指令。

本轮无新增 P0/P1/P2。

## 1. 🔴 P0 / P1

暂无。

## 2. 🟡 P2

暂无。

## 3. 🟡 P3

暂无。

## 4. 已充分认可

- `Channel<LogCommand>` + `Flush(ack)` 是当前 change 中最稳的 deterministic flush seam。
- `cancelAndJoin` 放在替换 `currentLogFile` 前，配合重复 init 不清空文件，已闭合旧 batch 丢失竞态。
- rotation 契约已统一成“稳定态 ≤ 2×MAX_FILE_BYTES + 单 batch 瞬态超量”，不再与写后 rotate 策略冲突。
- 高频日志 `v()` + `isVerboseEnabled` 守卫 + 坐标三位小数，能同时覆盖 A18 性能和 A39 隐私/体量目标。

## 5. 给实施方的回复模板

F 战役 `fix-file-logger-and-engine-coord-hygiene` 三件套通过第四轮 mini review，准予进入 `/opsx:apply`。实施时按 tasks 做 1 个代码 commit，合流门槛必须包含 `openspec validate --strict`、`:feature:test:compileDebugKotlin`、`:feature:test:testDebugUnitTest`、下游 `:core:bluetooth:testDebugUnitTest` / `:core:domain:test` / `:app:compileDebugKotlin`、E2E 契约、A18/A39 backlog 迁 🟢 与 grep 自检。代码落地后交评审方做 commit diff 级核销。
