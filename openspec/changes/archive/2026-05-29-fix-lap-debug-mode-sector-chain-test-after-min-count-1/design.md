# Design: fix-lap-debug-mode-sector-chain-test-after-min-count-1

> 本文档 self-contained：无对话 context 也能完整理解。所有锚点已 grep 核实于 HEAD `21809c7`。
> baseline fail 根因链、生产闭圈逻辑、filter 集成路径见 proposal.md「Why」三个关键事实。

## Context

宽容闭合（lenient lap closure）已是生产现实：`LapTimingEngine.handleStartFinishCrossing`
（`feature/test/src/main/java/com/blazepush/feature/test/usecase/LapTimingEngine.kt:158-223`）
在第二次 startFinish 过线（`activeLap != null`）时无条件闭圈并打 `IncompleteSectors`
quality flag。本 round 不动这套生产逻辑，只解决「W4 把 `GpsDataFilter` 接进 bridge
后，测试 fixture 的合成过线两帧被 filter 污染，导致第二次过线判定失败、单测 fail」
这一 fixture 回归，并把宽容闭合语义升级为 normative spec 契约护栏。

3-class 字段对应表（避免误并，v3 盲点 #5）：本 round 唯一触及的数据载体是
**in-memory domain `LapRecord.qualityFlags: List<LapQualityFlag>`**
（`feature/test/src/main/java/com/blazepush/feature/test/model/laptiming/LapRecord.kt:14`）。
**不涉及** DTO、不涉及 Room Entity、不涉及 binary writer，故无 3-class drift 风险。
（详情屏 `TelemetryCrossingEvent` 是另一条独立 Room 读路径，本 round 不碰，见 Decision 4。）

## Decision 1：修测试 fixture 让过线真实达成，而非改 expected 数值 / 改生产 filter 集成

**决策**：把修复对象锚定在**测试 fixture 触发链路**——让 `emitCrossing` 注入的过线帧
在经过 `GpsDataFilter` 后，detector 仍能拿到可正确判定方向的 lat/lon，使第二次
startFinish 过线真实 accepted、`completedLaps.size` 真实达成 1。具体手法（apply 期按实测择优，
均为纯测试侧、不改任何生产代码）：

- 手法 A：在每次 `emitCrossing` 的两过线帧之间/之前插入若干"稳态喂帧"（同一/缓变位置 +
  合理 speed），把 filter 的 9 点 lat/lon median 窗口预热到稳态，再让过线帧的方向分量
  能被 detector 解出；
- 手法 B：缩小 `emitCrossing` 的 `offsetScale`（当前 0.25 度 ≈ 28km，远超 speed=36
  在采样间隔内可达位移 → 必触 `isPositionAnomaly = ratio > 3.0`，GpsDataFilter.kt:276），
  改成与 speed 物理一致的小位移，避免被 `isPositionAnomaly` 误杀；
- 手法 C：组合 A+B。

apply 期 MUST 先实跑确认 baseline fail 形态（expected:1 实际 0），改后实跑确认
`completedLaps.size == 1` 真实达成（绿不是靠改 expected）。

**Alternatives 考量**：

- **Alt（拒绝）改 expected 回 0 / 放宽断言**：直接把 `assertEquals(1, ...)` 改成 0 或删断言。
  拒绝理由：(1) expected 早已是 1（git 证实 W4 时点即 1），改成 0 等于反转 user 拍板的
  宽容闭合语义；(2) 是"假绿门槛"（v3 盲点 #2），让 fail 消失但不证明宽容闭合在 filter
  接通后真工作。
- **Alt（拒绝）改生产 `bridgeGpsToLapTiming` 让 debug 模式过线帧绕过 filter 的 lat/lon 替换**：
  在 `TestSessionViewModel.kt:351-356` 加 debug-mode 分支保 raw lat/lon。拒绝理由：
  (1) 直接推翻 W4 design Decision 1+2（filter 仅替换 4 字段 lat/lon/speed/bearing 的锁死契约，
  注释见 `TestSessionViewModel.kt:346-356`）→ 命中 #17 实施期 design drift，必须升级 medium
  跑 mini-proposal；(2) filter 接通是 W4 刻意为消 jitter 做的，绕过会让真机 jitter 回归；
  (3) 测试 fixture 的合成 28km 跳变本就是不真实的输入，正确做法是让 fixture 输入物理合理，
  不是给生产代码开后门迁就坏 fixture。
- **Alt（拒绝）给 GpsDataFilter 加 debug 旁路 / 调 isPositionAnomaly 阈值**：改 `GpsDataFilter.kt`
  的 `ratio > 3.0`。拒绝理由：`GpsDataFilter` 在 `core/domain` 是**跨 capability 公共组件**
  （加减速测试 / 圈速 / realtime delta 都用），改它命中"跨 capability ripple"强制升级 medium，
  且为单个测试调全局阈值是污染公共代码（违背 Scope Boundaries：不为局部任务改公共组件）。

**Rationale**：fixture 是测试自身的输入构造，修 fixture 是 small/纯测试侧最小 scope；
保住生产 filter 契约（不触 #17）；让绿测真实证明宽容闭合在含 filter 的数据流下工作（消除假绿）。

## Decision 2：断言收紧到 `qualityFlags.contains(IncompleteSectors)` 而非泛 `isNotEmpty()`

**决策**：把现有 `assertTrue(session.completedLaps.first().qualityFlags.isNotEmpty())` 升级为
`assertTrue(session.completedLaps.first().qualityFlags.contains(LapQualityFlag.IncompleteSectors))`，
精确锁死"宽容闭合时该圈被打 sector 不完整标记"——这是 sector 不完整提示语义在本 round
范围内的真实落点（in-memory 信号），也是 follow-up UI 提示接通时的数据契约源。

**Alternatives 考量**：

- **Alt（拒绝）保持 `isNotEmpty()`**：拒绝理由：`isNotEmpty()` 在该圈同时含 `ProtocolDesyncGap`
  （`LapTimingEngine.kt:194-195`）时也会真，无法区分"sector 不完整"与"丢帧"。本 round 主题
  是 sector 不完整闭圈，断言必须指名 `IncompleteSectors`，否则 fixture 偶然产生 desync gap
  也能让断言过、放过 sector 标记丢失的回归（弱断言假绿）。
- **Alt（拒绝）断言 `qualityFlags == listOf(IncompleteSectors)`（精确相等）**：拒绝理由：
  过严。fixture 喂帧若触发 `ProtocolDesyncGap` 会让 list 含两元素，精确相等会脆性 fail。
  `contains` 既锁死核心信号又对无关 flag 鲁棒。

**Rationale**：`contains` 是"恰好命中目标 flag"的最小充分断言，规避 v3 盲点 #7（弱 gate
trivially pass）与过严脆性两个极端。

## Decision 3：spec 落在 capability `lap-timing-engine`，用 `## ADDED Requirements`

**决策**：spec delta 写在 `specs/lap-timing-engine/spec.md`，用 `## ADDED Requirements`
新增一条「sector 链不完整时 startFinish 二次过线宽容闭合」的 normative requirement。

**Alternatives 考量**：

- **Alt（拒绝）放 `realtime-lap-delta` 或新建 `lap-debug-mode` capability**：拒绝理由：
  宽容闭合是 `LapTimingEngine` 判圈链路的语义，归属 `lap-timing-engine`（archive 已有
  `fix-lap-timing-closure-and-precision-contract` / `fix-lap-timing-engine-entry-hardening`
  两个 change 用此 capability）。新建 capability 命中"引入新 capability"强制升级 medium，
  且语义重复。
- **Alt（拒绝）用 `## MODIFIED Requirements` 改归档 change 的 closure requirement**：拒绝理由：
  宽容闭合此前未被写成 normative（archive closure spec 锁的是插值毫秒 / trajectory 切分 /
  多门 state 推进，不含"sector 不完整仍闭圈"的 user-decision 语义）。这是新增契约，
  用 ADDED 语义正确；MODIFIED 会引入对归档 spec 文本的精确匹配依赖，脆且无必要。

**Rationale**：归属正确 + 不升级复杂度 + ADDED 语义匹配「首次把宽容闭合写成契约」。

## Decision 4：本 round 不接通 UI 提示，显式 defer（防半闭环承诺）

**决策**：sector 不完整的**实时 banner / 详情屏 chip 真正接通**不在本 round scope，
在 tasks §10 留 follow-up backlog（`wire-incomplete-sector-hint-to-ui`）。本 round 只保证
in-memory `qualityFlags.IncompleteSectors` 信号正确产生且被单测锁死。

**Alternatives 考量**：

- **Alt（拒绝）本 round 顺手接通 `LapLiveStateDeriver` banner**：在 `invalidatingReasons` 或
  另起 warning 通道让 sector 不完整也出 banner。拒绝理由：(1) `AbnormalState.LAP_INVALIDATED`
  语义是"圈被作废/异常打断"，宽容闭合是"圈有效但有瑕疵"，二者语义冲突，混进同一 banner
  会误导用户以为圈废了；(2) 需要改生产 `LapLiveStateDeriver.kt`（脱离 small 纯测试 scope）
  + 设计新的 warning UI 状态（这是 UI 设计决策，应走独立 round 的 L0/design）；(3) 顺手做会
  让本 round 从"修测试回归"扩成"加 UI 能力"，scope 膨胀。
- **Alt（拒绝）本 round 顺手修详情屏 `deriveDetailMetrics` 让 INCOMPLETE chip 生效**：
  拒绝理由：`deriveDetailMetrics`（`LapSessionDetailScreen.kt:471`）从 Room `TelemetryCrossingEvent`
  派生，而 `IncompleteSectors` 是 in-memory `LapRecord.qualityFlags` —— Room crossing 表
  当前不持久化 qualityFlags（独立数据契约 gap），接通需先解决持久化，远超本 round scope。

**Rationale**：宁可 scope 收紧 + 明确 defer，也不做半闭环承诺（v3 盲点 #1）。本 round
交付"宽容闭合语义有 normative 护栏 + 真实绿测"，UI 提示是下一个独立主题。

## Risks

| 风险 | 说明 | Mitigation |
|---|---|---|
| **R1 沙箱无网未实跑 baseline，根因是高置信推断** | proposal「Why」关键事实 3 的 filter 污染链推断基于静态代码分析（GpsDataFilter median + isPositionAnomaly），未在沙箱实跑确认 | apply 期 MUST 实跑 baseline 确认 fail 形态（expected:1 实际 0），再实跑改后确认绿；若实测根因与推断不符，apply 期暂停按 #17 修订 design Decision 1 |
| **R2 修 fixture 误改其他 test 行为** | `emitCrossing` helper 被多个 test 共用（如 `lapDebugMode_replayAlignedTrackCatalogProducesAcceptedStartFinishCrossing` test:168-191 也用），改 helper 可能连锁影响 | 优先在本 test 局部构造过线帧（不改共享 helper）；若必须改 helper，apply 期 MUST 跑**整个** `TestSessionViewModelTrackLapTest` 全绿确认无连锁回归（v3 盲点：形态变化连锁影响） |
| **R3 弱断言假绿** | 若仅靠改 expected 让测试过，宽容闭合在 filter 接通后是否真工作仍无证明 | Decision 1 锁死"绿必须靠真实闭圈达成"+ Decision 2 锁死 `contains(IncompleteSectors)`；spec 反例 scenario 锁死"违反宽容闭合时测试 fail" |
| **R4 #16 跨 round 共享字段 drift** | 本 round 断言 `LapRecord.qualityFlags.IncompleteSectors`，需确认该枚举值/字段未被其他未合回 round 改动 | apply 期 #16 自查：grep `LapQualityFlag` 枚举与 `qualityFlags` 字段，确认 `IncompleteSectors` 存在且语义稳定（已核实 `LapQualityFlag.kt:7` 存在；本 round 不新增/改字段，drift 风险低） |
| **R5 follow-up UI 提示被遗忘** | defer 的 UI 接通若无沉淀点会丢失 | tasks §10 backlog 明确登记 `wire-incomplete-sector-hint-to-ui` + 在 proposal Impact 段写清当前 3 条提示链路现状，下次立项可直接对照 |

## Test Coverage（与 spec scenarios 对应）

- 正例：filter 接通后第二次 startFinish 过线 → `completedLaps.size == 1` + `currentLapIndex == 2`
  + `qualityFlags.contains(IncompleteSectors)`（修复后的 `lapDebugMode_secondStartFinishClosesLapEvenWhenSectorChainIsIncomplete`）。
- 反例（锁死违反约束时 fail）：spec Scenario「反例——sector 链不完整时若 startFinish 二次过线
  未闭圈则 normative 被违反」用 normative 文字锁死：若实现退回严格闭合（sector 不全则不闭圈），
  `completedLaps.size` 为 0，断言 `== 1` MUST fail。
- 边界：sector 链**完整**时闭圈不应打 `IncompleteSectors`（保证 flag 不是无条件加的）。
