# fix-altitude-encoding-contract-alignment · 第 1 轮 spec/tasks review

- **日期**：2026-04-24
- **评审对象**：
  - `openspec/changes/fix-altitude-encoding-contract-alignment/proposal.md`
  - `openspec/changes/fix-altitude-encoding-contract-alignment/specs/race-chrono-parser/spec.md`
  - `openspec/changes/fix-altitude-encoding-contract-alignment/tasks.md`
- **覆盖攻击点**：A16b
- **评审方**：codex session
- **实施方**：session 1
- **轮次**：第 1 轮结构化 review
- **结论**：🔴 拒绝进入 `/opsx:apply`
- **前置条件**：P1/P2 全部修订并重新 `openspec validate fix-altitude-encoding-contract-alignment --strict`

## 0. 结论摘要

方向认可：A16b 以 RaceChrono 官方 BLE DIY API + ESP32 ino 实际发送端为真相源，且把 parser / 协议文档 / test helper / simulator 一起对齐，是正确的闭环方式。A16a 之后再做 A16b，顺序也对。

但当前三件套有 1 个 P1 数值边界错误和 3 个 P2 覆盖/任务映射问题。尤其是 `6053m`：按 ino 条件 `alt < 6053.5`，它仍走 bit15=0 截断分支，不是 bit15=1 高海拔分支。现有 RP22b / R2 E2E 场景会因此照 spec 实现后失败或覆盖到错误分支。

## 1. 🔴 P0 / P1

### P1-1 · 6053m 被误归入 bit15=1 分支

- **位置**：`spec.md:49-60`，`spec.md:96-110`，`tasks.md:71-76`，`proposal.md:51-52`
- **问题**：spec/tasks 把 `alt=6053m` 写成 bit15=1 高海拔边界，期望字节 `0x99 0x99` 并解码回 6053m。但 ino 判定条件是 `if (alt < 6053.5)`，所以 `6053.0 < 6053.5` 仍走 bit15=0，编码为 `((6053+500)*10) & 0x7FFF = 0x7FFA`，parser 解码为 `2776.2m`，属于 R5 的截断 Non-goal 区间。
- **后果**：RP22b 照 tasks 写会失败；R2 simulator → parser 往返 Scenario 把 `6053` 放进“精确往返”取值域也会失败。更严重的是这会让 bit15=1 边界测试名义上存在、实际边界定义错误。
- **修订建议**：把 bit15=1 边界用例从 `6053m` 改成 `6053.5m` 或 `6054m`。如果用 `6053.5m`，ino `(int)(6053.5 + 500)` 解码回 `6053.0`，断言应允许 0.5m 量化误差；如果想断言精确整数，建议用 `6054m`，字节为 `0x99 0x9A`，解码 `6054.0`。同时把 `spec.md:108` 往返取值域里的 `6053` 改成 `6053.5`/`6054`，并把 `proposal.md:52` 的 `0x8CA3` 修成正确字节或删除该行错误示例。

## 2. 🟡 P2

### P2-1 · 协议文档修订漏掉解析示例代码块

- **位置**：`tasks.md:83-100`，当前 `docs/RaceChrono_BLE_Protocol.md:143-148`
- **问题**：tasks 只要求改协议文档 §3.4 的文字公式，但当前文档下方 `GPS 主数据解析` Kotlin 示例仍有 altitude 旧公式：bit15=0 `/100.0 - 500.0`，bit15=1 `*10 / 100.0 - 500.0`。
- **后果**：实施方照 tasks 完成后，协议文档仍会在同一文件里同时出现新旧两套 altitude 公式，违反本 change “协议文档与 ino 对齐”的 R2/R5 目标。
- **修订建议**：tasks §1.4.1 增加 `docs/RaceChrono_BLE_Protocol.md:143-148` 示例代码同步修订，并在合流门槛加 grep：协议文档中 altitude 旧公式 `raw / 100.0 - 500.0`、`* 10) / 100.0 - 500.0` 不得残留；注意不要误伤 speed 公式。

### P2-2 · simulator R2 单测被标为可选，但现有测试基础设施已经存在

- **位置**：`tasks.md:138-143`
- **问题**：R2 有明确的 simulator 编码 Scenario，但 tasks 把 `GpsDataGeneratorTest.generatesBytes_altitudeWithInoCompatibleEncoding` 标成“可选 / 若无测试基础设施则 Non-goal”。当前仓库已经有 `simulator/src/test/java/com/blazepush/simulator/data/GpsDataGeneratorTest.kt`，测试基础设施存在；而 `EndToEndLapTimingContractTest` 主要断圈时，不直接断 byte 12-13，高海拔 bit15=1 也未必走到。
- **后果**：实施方可以不加 simulator 字节级测试而仍过当前门槛，R2 “判定条件 6053.5 + bit15=1 不乘 10”没有硬区分保护。
- **修订建议**：把 §2.2.2 改为必做，并在 §4 合流门槛新增 `./gradlew :simulator:testDebugUnitTest --tests "*GpsDataGeneratorTest*"` 或项目实际 simulator 测试 task。测试至少断言 `100m -> 0x17 0x70`、`10000m -> 0xA9 0x04`，并加入 `4000m` 截断区间字节 `0x2F 0xC8` 作为 Non-goal 锁定。

### P2-3 · R1 截断区间 Scenario 是 MUST，但 task 标成可选

- **位置**：`spec.md:62-70`，`tasks.md:77-79`
- **问题**：spec 把 `[2776.7m, 6053.5m]` 截断区间列为 R1 Scenario，并要求测试使用 `assertNotNull` 而非具体数值断言；tasks §1.3.4 却标成“可选”。
- **后果**：实施方可以跳过该测试，导致本 change 最关键的 Non-goal 边界没有机器锚点。后续维护者可能再次把该区间误当成可精确往返，或者重复本轮 `6053m` 边界错误。
- **修订建议**：把 §1.3.4 改为必做。建议断 `alt=4000m` 的字节为 `0x2F 0xC8`、parser 不抛、解码值等于截断后的 `723.2m` 或至少明确 `!= 4000.0`；这样既不承诺恢复真实高度，也锁住 Non-goal 行为。

## 3. 🟡 P3

### P3-1 · RP22 断言文案仍写“海拔溢出”

- **位置**：`tasks.md:70`
- **问题**：RP22 改成 1600m bit15=0 后，断言消息仍是“海拔溢出时应为1600”。
- **建议**：改成“bit15=0 低海拔分支应为1600”或“ino 真实编码 1600m 应正确解码”。

## 4. proposal / 上游遗留

本轮不挑战“以 ino + 官方 RaceChrono API 为真相源”的决策；需要修的是该决策落到边界值和机器门槛时的精度。

## 5. 🟢 已充分认可

- A16a / A16b 拆分正确，避免 lat/lon 与 altitude 两类 bug 互相污染核销。
- 把 `[2776.7m, 6053.5m]` 作为 ino 固件截断 Non-goal 显式声明是正确取舍。
- parser + helper + simulator + 协议文档同 change 对齐，比拆散成多个 change 更稳。

## 6. 给实施方的回复模板

本轮暂不放行 `/opsx:apply`。请先修 4 点：把 bit15=1 边界从 `6053m` 改成 `6053.5m` 或 `6054m`，并修正相关字节/断言/往返取值域；协议文档除 §3.4 外还要同步改解析示例代码块并加旧公式 grep；`GpsDataGeneratorTest` 的 altitude 字节级测试改为必做；截断区间 `4000m` Non-goal 测试从可选改必做。修完后重跑 `openspec validate fix-altitude-encoding-contract-alignment --strict` 再提第二轮 mini review。
