# refactor-parser-internal-state-cleanup · 第 2 轮 mini review

- **日期**：2026-04-24
- **评审对象**：
  - `openspec/changes/refactor-parser-internal-state-cleanup/proposal.md` v2
  - `openspec/changes/refactor-parser-internal-state-cleanup/specs/race-chrono-parser/spec.md` v2
  - `openspec/changes/refactor-parser-internal-state-cleanup/tasks.md` v2
- **覆盖攻击点**：A26 / A41
- **评审方**：codex session
- **实施方**：session 1/2 待分工
- **轮次**：第 2 轮 mini review（复核第 1 轮 2P1 + 1P2）
- **结论**：🟢 准予进入 `/opsx:apply`
- **前置条件**：实施阶段按 tasks §3.1 全量跑合流门槛，代码 commit 后再提 code review 核销。

## 0. 结论摘要

第 1 轮三条问题已闭合：

- 旧 `RaceChronoParserTest.parseGpsTimeData_successPathExplicitlyClearsErrorMessage_sourceAssertion` 已纳入 R1 tasks，同步改为 `errorMessage = null` 至少 1 次 + 禁止 `isTestReady = true` / `if (!currentData.isTestReady)` v1 残留。
- `lastFrequencyUpdateTime` reset 契约已统一：H 一期不新增、不修改该字段 reset 行为；它只作为 frequency 活字段存在性保留，不再要求 `reset()` 写 `lastFrequencyUpdateTime = 0`。
- R1 新测试文件已改为唯一名称 `RaceChronoParserTestReadyStateTest.kt`，并把既有 `RaceChronoParserProtocolTimeTest.kt` 明确列为 A8 零回归门槛，避免覆盖同名文件。

`openspec validate refactor-parser-internal-state-cleanup --strict` 通过。本轮无新增 P0/P1/P2。

## 1. 🔴 P0 / P1

暂无。

## 2. 🟡 P2

暂无。

## 3. 🟢 已闭合项

### P1-1 旧源码断言会失败

已闭合。tasks §1.3 明确要求在 R1 commit 内修订既有源码断言，合流门槛 §3.1.2 继续跑完整 `RaceChronoParserTest`，不会在实施阶段被旧断言反杀。

### P1-2 `lastFrequencyUpdateTime` reset 契约冲突

已闭合。spec R2 正文现在只要求 `gpsDataTimestamps.clear()`、`gpsFrequency = 0.0`、`protocolTimeReference = null` 保持；并显式声明 `lastFrequencyUpdateTime` reset 行为不在本 change 修改，候选归 A28。

### P2 同名测试文件风险

已闭合。R1 新文件改为 `RaceChronoParserTestReadyStateTest.kt`；既有 `RaceChronoParserProtocolTimeTest.kt` 只作为 A8 零回归门槛运行，不复用、不覆盖。

## 4. 本轮执行的复核命令

```bash
openspec validate refactor-parser-internal-state-cleanup --strict
rg -n "RaceChronoParserProtocolTimeTest|lastFrequencyUpdateTime\s*=\s*0|errorMessage = null.*≥ 2|RaceChronoParserTestReadyStateTest|isTestReady = true|if \(!currentData\.isTestReady\)" \
  openspec/changes/refactor-parser-internal-state-cleanup
rg -n "@IgnoreFormatCheck|RaceChronoParserTestReadyStateTest|RaceChronoParserInternalStateTest|lastFrequencyUpdateTime|gpsFrequency|gpsDataTimestamps" \
  openspec/changes/refactor-parser-internal-state-cleanup/tasks.md \
  openspec/changes/refactor-parser-internal-state-cleanup/specs/race-chrono-parser/spec.md
```

## 5. 给实施方的回复模板

战役 H 一期三件套 v2 通过第二轮 mini review，准予进入 `/opsx:apply`。请按 tasks 拆 2 commit：R1 A26 `parseGpsTimeData` 不写 `isTestReady` + 新增 `RaceChronoParserTestReadyStateTest` + 修订既有源码断言；R2 A41 删除 5 个 tracking 死字段 + tracking 块 + 新增 `RaceChronoParserInternalStateTest`。实施完成后必须跑 tasks §3.1 全量门槛，尤其是既有 `RaceChronoParserTest` 与 A8 `RaceChronoParserProtocolTimeTest` 零回归；commit 后交评审方做最终核销。
