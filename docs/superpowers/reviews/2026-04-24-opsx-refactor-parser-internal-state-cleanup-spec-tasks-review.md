# refactor-parser-internal-state-cleanup · spec/tasks review

- **日期**：2026-04-24
- **评审对象**：
  - `openspec/changes/refactor-parser-internal-state-cleanup/proposal.md`
  - `openspec/changes/refactor-parser-internal-state-cleanup/specs/race-chrono-parser/spec.md`
  - `openspec/changes/refactor-parser-internal-state-cleanup/tasks.md`
- **覆盖攻击点**：A26 / A41
- **评审方**：codex session
- **实施方**：session 1/2 待分工
- **轮次**：第 1 轮 spec/tasks review
- **结论**：🔴 暂不放行 `/opsx:apply`

## 0. 结论摘要

变更方向认可：A26 和 A41 都是 parser 内部状态污染清理，物理改动分离，拆 2 个 commit 合理。`openspec validate refactor-parser-internal-state-cleanup --strict` 通过。

但 v1 还存在 2 条 P1 + 1 条 P2。P1 都会在实施阶段直接制造失败：一条是旧 `RaceChronoParserTest` 源码断言仍要求 `parseGpsTimeData` 两个 `errorMessage = null` 分支；另一条是 spec 要求 `reset()` 清 `lastFrequencyUpdateTime = 0`，tasks/proposal 又明确说不修该字段。P2 是 tasks 要新建的 `RaceChronoParserProtocolTimeTest.kt` 已经存在，容易覆盖 A8 既有契约测试。

## 1. 🔴 P0 / P1

### Finding 1 · R1 未处理既有源码断言，`RaceChronoParserTest` 全量会失败

- **位置**：
  - `openspec/changes/refactor-parser-internal-state-cleanup/tasks.md:23-43`
  - 既有测试：`core/bluetooth/src/test/java/com/blazepush/core/bluetooth/parser/RaceChronoParserTest.kt:943-959`
- **问题**：
  - R1 计划把 `parseGpsTimeData` 成功路径改成单一 `currentData.copy(errorMessage = null)`。
  - 但现有 `RaceChronoParserTest.parseGpsTimeData_successPathExplicitlyClearsErrorMessage_sourceAssertion` 明确断言函数体内 `errorMessage = null` 出现次数 `>= 2`，理由是锁定 if/else 两分支都清 error。
  - tasks 只要求新增 `RaceChronoParserProtocolTimeTest`，没有要求同步修订这条旧源码断言。
- **影响**：
  - 实施方照 tasks 做完 R1 后，合流门槛 `./gradlew :core:bluetooth:testDebugUnitTest --tests "*RaceChronoParserTest*"` 会失败。
- **建议修复**：
  - 在 tasks §1 增加一项：修订既有源码断言，使它从“两个分支都清 errorMessage”改为“成功路径保留显式 `errorMessage = null`，且不包含 `isTestReady = true` / `if (!currentData.isTestReady)`”。
  - spec R1 可追加一句：A25 的成功路径清 error 契约仍保留，但不再要求两个 copy 分支。

### Finding 2 · `lastFrequencyUpdateTime` reset 契约自相矛盾

- **位置**：
  - `openspec/changes/refactor-parser-internal-state-cleanup/specs/race-chrono-parser/spec.md:93-96`
  - `openspec/changes/refactor-parser-internal-state-cleanup/tasks.md:178-181`
- **问题**：
  - spec 写 `reset()` MUST 保留 `lastFrequencyUpdateTime = 0`。
  - proposal/tasks 又明确说原 `reset()` 没有重置 `lastFrequencyUpdateTime`，这是 frequency reset 完整性问题，不在本 change 顺手修。
  - 当前代码也确实只清 `gpsDataTimestamps` 和 `gpsFrequency`，没有清 `lastFrequencyUpdateTime`。
- **影响**：
  - 实施方按 tasks 做会违反 spec；按 spec 做又违反 Non-goal / scope 边界。
  - 后续 code review 无法判断该不该要求新增 `lastFrequencyUpdateTime = 0`。
- **建议修复**：
  - 若坚持 H 一期只做 A26/A41：从 spec R2 正文和 Scenario 3 中删除 `lastFrequencyUpdateTime = 0` 保留要求，改为“frequency 活字段不误删；本 change 不修 lastFrequencyUpdateTime reset 缺失”。
  - 若决定顺手修：proposal Non-goals、tasks §2.2、测试示例都要同步改，并把它标成新增 scope。当前更建议前者，保持 scope 收敛。

## 2. 🟡 P2

### Finding 3 · `RaceChronoParserProtocolTimeTest.kt` 已存在，tasks 写“新建文件”会撞车

- **位置**：`openspec/changes/refactor-parser-internal-state-cleanup/tasks.md:45-47`
- **问题**：
  - 当前仓库已经存在 `core/bluetooth/src/test/java/com/blazepush/core/bluetooth/parser/RaceChronoParserProtocolTimeTest.kt`，里面承载 A8 时间同步契约测试。
  - tasks 要求“新建同名文件”，但没有说明是扩展现有文件还是另起唯一文件名。
- **影响**：
  - 实施方可能覆盖既有 A8 测试，或者复制出同名 class 导致编译冲突。
  - “新建文件不继承 legacy 豁免”这个目标可以保留，但不能用已经存在的名字。
- **建议修复**：
  - 二选一：
    - 明确“扩展现有 `RaceChronoParserProtocolTimeTest.kt`，不得删除 A8 既有测试”。
    - 或改用新文件名，例如 `RaceChronoParserTestReadyStateTest.kt`，class 名也对应唯一。
  - 合流门槛加一句：A8 既有 `RaceChronoParserProtocolTimeTest` 全部仍通过。

## 3. 🟡 P3

暂无。

## 4. 已确认通过的部分

- A26 / A41 合一 change 的理由成立：同文件、同类 parser 内部状态污染、物理改动分离。
- R1/R2 拆 2 commit 合理，便于独立回退和核销。
- A26 行为目标明确：时间包只维护 `protocolTimeReference` + 清 `errorMessage`，不再写 `isTestReady`。
- A41 行为目标明确：删除 5 个死字段和 tracking 计算块，不碰 frequency / time sync / altitude。
- `openspec validate refactor-parser-internal-state-cleanup --strict` 已通过。

## 5. 本轮执行的复核命令

```bash
openspec validate refactor-parser-internal-state-cleanup --strict
nl -ba core/bluetooth/src/main/java/com/blazepush/core/bluetooth/parser/RaceChronoParser.kt | sed -n '1,360p'
nl -ba core/bluetooth/src/test/java/com/blazepush/core/bluetooth/parser/RaceChronoParserTest.kt | sed -n '936,965p'
nl -ba core/bluetooth/src/test/java/com/blazepush/core/bluetooth/parser/RaceChronoParserProtocolTimeTest.kt | sed -n '1,220p'
rg -n "lastFrequencyUpdateTime|gpsFrequency|gpsDataTimestamps|reset\\(" \
  core/bluetooth/src/main/java/com/blazepush/core/bluetooth/parser/RaceChronoParser.kt \
  openspec/changes/refactor-parser-internal-state-cleanup
```

## 6. 给实施方的回复模板

战役 H 一期三件套 v1 暂不放行 `/opsx:apply`。方向认可，但请先修 2 条 P1 + 1 条 P2：R1 必须同步修订既有 `RaceChronoParserTest` 源码断言；spec/tasks 对 `lastFrequencyUpdateTime` reset 的要求必须统一；`RaceChronoParserProtocolTimeTest.kt` 已存在，tasks 需改成扩展现有文件或换唯一新文件名。修完 validate 后可重提第二轮 mini review。
