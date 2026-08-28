# fix-altitude-encoding-contract-alignment · 第 3 轮 mini review

- **日期**：2026-04-24
- **评审对象**：
  - `openspec/changes/fix-altitude-encoding-contract-alignment/proposal.md` v3
  - `openspec/changes/fix-altitude-encoding-contract-alignment/specs/race-chrono-parser/spec.md` v3
  - `openspec/changes/fix-altitude-encoding-contract-alignment/tasks.md` v3
- **覆盖攻击点**：A16b
- **评审方**：codex session
- **实施方**：session 1
- **轮次**：第 3 轮 mini review（复核第 2 轮 P2）
- **结论**：🟢 准予进入 `/opsx:apply`
- **前置条件**：实施阶段按 tasks §4 全量跑合流门槛，代码 commit 后再提 code review 核销。

## 0. 结论摘要

第 2 轮唯一 P2 已闭合。R2 现在明确限定：simulator → parser 只在截断区间外精确往返；`[2776.7m, 6053.5m]` 区间只承诺 simulator 与 ino 产生同一截断字节，parser 解出截断后的值，不承诺还原原 altitude。

本轮无新增 P0/P1/P2。

## 1. 🔴 P0 / P1

暂无。

## 2. 🟡 P2

暂无。

## 3. 🟡 P3

暂无。

## 4. 已闭合项

- `6053m` bit15 边界已改为 `6054m / 0x999A`，并明确 `6053m` 仍属 bit15=0 截断区间。
- 协议文档正文与 Kotlin 示例代码块都纳入修订任务。
- `GpsDataGeneratorTest.generatesBytes_altitudeWithInoCompatibleEncoding` 已设为必做，覆盖 `100m / 10000m / 4000m` 三组字节。
- `4000m -> 0x2F 0xC8 -> 723.2m` 截断区间 Non-goal 已有 parser 测试锚点。
- R2 往返契约已排除截断区间，与 R1/R5 Non-goal 保持一致。

## 5. 给实施方的回复模板

A16b 三件套通过第三轮 mini review，准予进入 `/opsx:apply`。实施时按 tasks 拆 2 commit：R1/R3/协议文档一个 commit，R2 simulator 一个 commit。合流门槛必须包含 `openspec validate --strict`、`RaceChronoParserTest`、完整 `:core:bluetooth:testDebugUnitTest`、`EndToEndLapTimingContractTest`、`GpsDataGeneratorTest`、协议文档旧 altitude 公式 grep、A16b backlog 迁 🟢。代码落地后交评审方按 commit diff 做最终核销。
