# L2 Adversarial Review — phase1-hardening-w2-w3-w4-mimo-debt

**Reviewer**: Opus 子 agent (L2 实施期 review)
**Date**: 2026-05-05
**Mode**: Hostile / adversarial — 不持有 round 之前的对话上下文，仅读工件 + 主区生产代码 + L1 review trail
**输入**: 21/22 项 apply 完成（E4 binary contract test 推 Phase 2 follow-up）+ L1 R1/R2/R3 plateau

## §A 复盘 — apply 期处置 verify

按 task 文件路径 + done condition 字面量逐项 grep verify。

### F1 governance（§1）

- §1.1 #17 条款：`/Users/wattledgnata/traeProjects/gps-app/CLAUDE.md:351` 已写入 + 7 字面量全部命中（`#17` ✓ / `实施期偏离` ✓ / `MUST 暂停 apply` ✓ / `MUST grep 自检` ✓ / `每完成 1 task` ✓ / `actionable directive` ✓ / `governance 局限` ✓）
- §1.2 yaml schema 字段：`CLAUDE.md:287-288` 两个字段名 `design_decisions_diverged_during_apply` + `cross_round_field_drift_resolved` 落地 ✓
- §1.3 cross-reference：`CLAUDE.md:360` "见 #17 条款" 在 v3 流程结束信号节内 ✓
- §1.4 #16 update note：`CLAUDE.md:349` 含 "phase1-hardening-w2-w3-w4-mimo-debt" + "deferred to Phase 2" 字面量 ✓ + 在 #16 block 内（不超出边界）✓

### A 类 W2 spec hardening（§2）

- §2.1 (A1) **task path drift**：task 写"`proposal.md`"，但 D1↔D5 conflate 实际位置在 `metrics.yaml:11-14, 40-42` + review-l2-opus-a.md。修复确实落地（metrics.yaml 出现 "D1=D, 与 W1 编译可独立" + "D5 ... user-A-vs-D 决议" 措辞 ✓ + 0 命中"D1=A vs D"误字 ✓）；但 task done condition 字面量 `D1 类型契约依赖` **0 命中 in proposal.md**（实际在 metrics.yaml comment line 12）。这是 task-vs-actual-edit 路径漂移，非"未修复"。**P2**（task 文档与实际 commit 路径不对齐，将来 retroactive 工作可能误导）
- §2.2 (A2) MockTelemetry.kt:34 `(i.toLong() * lapDurationMs) / (n - 1).coerceAtLeast(1).toLong()` ✓
- §2.3 (A3) MockTelemetry.kt:14 `internal data class FakeLapTelemetry` + line 10 KDoc "test-only" ✓ + line 75 `mockMultiLap(): List<FakeLapTelemetry>` ✓
- §2.4 (A4) AccelTimeChart.kt:137 `internal fun computeAccelSegments(samples: List<LapTelemetrySample>): List<IntRange>` + line 87 `computeAccelSegments(samples)` 调用 ✓
- §2.5 (A5) AccelTimeChartContractTest.kt 含 5 个 `computeAccelSegments` @Test：all-non-null / all-null / leading-null / trailing-null / alternating-null ✓ + 1 额外 empty input case（共 6 个）— done condition 5 case 满足 ✓

### C 类 W2 silent bug（§3）

- §3.1 (C1) SpeedTimeChart.kt:106 `if (samples.isEmpty() || samples.size == 1) { Box(...) NO DATA + return }` 在 Composable 体内最早位置 ✓ + 双层保险：line 132 `if (samples.size <= 1) return@detectDragGestures` + line 145 `if (samples.size <= 1) return@detectTapGestures` ✓ + cursor 渲染分支 line 167-173 在 early-return 之后 ✓
- §3.2 (C1 测试) SpeedTimeChartContractTest 已存在并跑通（fastlane gate `:feature:test:testDebugUnitTest --tests "*SpeedTimeChartContractTest*"` BUILD SUCCESSFUL）
- §3.3 (C2) GrepGateTest.kt:88 `private fun findTextCallBlocks(content: String): List<IntRange>` + line 94 `var parenDepth = 1` + line 116 `findTextCallBlocks(content)` 调用 ✓ + 0 命中 `windowed(300)` / `windowed(200)` ✓
- §3.4 (C3) AccelTimeChartContractTest.kt:70-72 partial-null case 用 `computeAccelSegments` 真断言 ✓
- §3.5 (C4) W2 archive design.md:216 含 "无量化测量" + "由 Tier 2 ... 真机首次组屏签收" 透明声明 + line 211 `5ms` 字符已不存在（实测无 grep 命中）✓

### B 类 LapTelemetrySample.flags drift 修复（§4）

- §4.1 (B1) GrepGateTest.kt:170 `"val flags: Int"` ✓（命中 1 次）
- §4.2 (B2) LapAlignment.kt:196 `val flags = if (alpha < 0.5) s0.flags else s1.flags` ✓ + line 208 `flags = flags,` 入构造器 ✓ + line 145 fallback 路径 `flags = 0` ✓ + 4 锚点 grep verify：`flags = if` 命中 1 次（仅 interpolate）+ `flags = 0` 命中 1 次（仅 fallback）+ clamp 路径 line 159-160 `return samples[0]` / `return samples[n - 1]` ✓ + 精确命中 line 168 `return samples[kMin]` ✓ + 0 命中 `samples[0].copy(flags = 0)` 等 anti-pattern ✓
- §4.3 (B3) W3 archive design.md:72 + spec.md:113 均含 "B3 修订（phase1-hardening-w2-w3-w4-mimo-debt round 同步）" + "实际生产代码 ... s0 / s1" + "grep gate 字面量 MUST 用 s0 / s1" ✓ + design.md:81 字段插值表 `flags` 行 "最近邻 ... v3 高频盲点 #16 实战首例" ✓
- §4.4 (B4) LapAlignmentTest.kt 5 个新 case：caseG1 `caseG1_flagsAlphaSmallTakesS0` / caseG2 / caseG3 / **caseG4 (clamp)** / **caseG5 (精确命中)** ✓ + assert msg 含 "no default-0 sentinel" ✓ + `:core:domain:test` BUILD SUCCESSFUL（21 case 全绿）✓

### D 类 W3 trivial（§5）

- §5.1 (D1) LapAlignment.kt:115 注释 "删除死参数 fallbackRefSamples + 删除 @Suppress" ✓ + 0 命中 `fallbackRefSamples` ✓ + 0 命中 `@Suppress("UNUSED_PARAMETER")` ✓
- §5.2 (D2) LapAlignmentTest.kt:471 `fun caseH_bearingWrap360()` ✓ + assert msg "all bearingDeg must be 359.0 or 1.0 (nearest neighbor across 0/360 wrap)" ✓
- §5.3 (D3) LapAlignmentTest.kt:514 `fun caseI_elapsedFloatBoundary()` ✓ — **注意 apply 期 spec drift 修订**：spec 原写"边界 ±1e-9 三种情况 deterministic 一致"，实测 truncation 跨边界本就不 deterministic（99.999... → 19, 100.000... → 20）→ inline 修订 spec 描述为"同侧 deterministic + 跨边界 expected truncation"。这是合理的 self-discovery，**未走 #17 mini-proposal 流程**（因为 spec 描述错误属于工件 hardening 期的语义对齐，不是"实施期偏离 design Decision"），但 metrics.yaml 应透明声明此 inline drift。**P2**（透明性 — F1 自身要求"design 决策"drift，但 spec 描述错误也建议进 metrics 透明）

### E 类 W4 hotfix 后 P1（§6）

- §6.1 (E1) LapFilterIntegrationTest.kt:419 `fun \`E1 - cleaned timestamp must equal raw timestamp (4 fields hotfix B contract)\`` + line 443 `cleaned.timestamp MUST equal raw.timestamp` 反例 case ✓
- §6.2 (E2) LapLiveStateDeriver.kt:159 注释引用常量名 `LAP_INVALIDATED_DEBOUNCE_MIN_COUNT` ✓ + 0 命中 "≥3" ✓
- §6.3 (E3) W4 metrics.yaml 全部 placeholder 字段补全 + 4 P0 + 5 P1 review_findings_l2 ✓ + actual_days: 1.0 + comment ✓ + design_decisions_diverged_during_apply 含 Decision 1+2 drift（F1 #17 实战首例）✓ + cross_round_field_drift_resolved 显式声明（W4 写 binary 永久默认 0, deferred to Phase 2）✓
- §6.4 (E4) **deferred to Phase 2 follow-up** — task 标 [ ]，未实施。理由：mock TelemetryRepository 脱离 W4 round scope 需要新建 fake/mock 依赖，CC 主会话评估 scope 风险后判定推延更安全。**透明声明**：metrics.yaml 应在 review_findings_l2 加一条 P1 "E4 binary contract test 推 Phase 2 follow-up — 当前无 contract test 锁 binary 4 字段"。**P2**
- §6.5 (E5) W4 archive tasks.md:176 `## 11. 归档后状态修订` + §11.1 hotfix B 落地记录 + §11.2 W4_DIAG strip 计划 + 0 命中 "## 12." ✓

## §B 生产代码 grep verify（实际命令 + 输出）

每项给 OK / NOT OK / 部分 OK 评价。

| 项 | grep verify | 结果 |
|---|---|---|
| F1 #17 七字面量（CLAUDE.md:351） | `grep -c '#17\|实施期偏离\|MUST 暂停 apply\|MUST grep 自检\|每完成 1 task\|actionable directive\|governance 局限'` 全部命中 | OK ✓ |
| F1 yaml schema 两字段 | `design_decisions_diverged_during_apply` + `cross_round_field_drift_resolved` 命中 line 287-288 | OK ✓ |
| F1 cross-ref（CLAUDE.md:360） | "见 #17 条款" 在 line 360 | OK ✓ |
| #16 update note | "phase1-hardening-w2-w3-w4-mimo-debt" + "deferred to Phase 2" 在 line 349 #16 block 内 | OK ✓ |
| A2 公式 | `(i.toLong() * lapDurationMs) / (n - 1).coerceAtLeast(1).toLong()` 命中 MockTelemetry.kt:34 | OK ✓ |
| A3 KDoc | "test-only" 在 MockTelemetry.kt:10 | OK ✓ |
| A4 函数抽取 | `internal fun computeAccelSegments` 在 AccelTimeChart.kt:137 | OK ✓ |
| A5 contract test | 5 case 全绿（empty input 是额外 6th case） | OK ✓ |
| C1 SpeedTimeChart 三层守卫 | `if (samples.isEmpty() \|\| samples.size == 1)` 在 line 106（在 cursor 渲染 line 167-173 之前）+ detector 双层保险 | OK ✓ |
| C2 §8.4 paren balance | `parenDepth` + `findTextCallBlocks` 命中 GrepGateTest.kt:88-104 | OK ✓ |
| C3 partial-null 真断言 | `computeAccelSegments` 在 AccelTimeChartContractTest.kt:70-72 | OK ✓ |
| C4 design 性能透明声明 | "无量化测量" + "Tier 2 真机首次组屏签收" 在 W2 archive design.md:216 | OK ✓ |
| B1 §8.7 flags 字段 | `"val flags: Int"` 命中 GrepGateTest.kt:170（lock 1 次） | OK ✓ |
| B2 4 锚点 | `flags = if (alpha < 0.5) s0.flags else s1.flags` 命中 1 次 (line 196) + `flags = 0` 命中 1 次 (line 145) + clamp `return samples[0]` ✓ + `return samples[kMin]` 命中 1 次 (line 168) + 0 命中 `.copy(flags = 0)` anti-pattern | OK ✓ |
| B3 W3 archive sync | "flags 最近邻 / phase1-hardening / s0 / s1" 字面量在 W3 design.md:72,81 + spec.md:113 | OK ✓ |
| B4 case G 5 sub-scenario | caseG1/G2/G3/G4/G5 + caseH + caseI 全部 @Test 函数命中 + `:core:domain:test` 21 case 全绿 | OK ✓ |
| D1 死参数 | 0 命中 `fallbackRefSamples` + 0 命中 `@Suppress("UNUSED_PARAMETER")` | OK ✓ |
| D2/D3 边界 case | caseH/caseI 在 LapAlignmentTest.kt:471, 514 | OK ✓ |
| E1 timestamp 反例 | `cleaned.timestamp MUST equal raw.timestamp` 在 LapFilterIntegrationTest.kt:443 | OK ✓ |
| E2 注释更新 | `LAP_INVALIDATED_DEBOUNCE_MIN_COUNT` 在 LapLiveStateDeriver.kt:159 + 0 命中 ≥3 | OK ✓ |
| E3 metrics.yaml 补全 | actual_days=1.0 + 4 P0 + 5 P1 + design_decisions_diverged_during_apply + cross_round_field_drift_resolved 全部命中 | OK ✓ |
| E5 W4 tasks §11 | `## 11. 归档后状态修订` + hotfix B + W4_DIAG + "真机 verify pass 后" 全部命中 | OK ✓ |
| **W3 archive tasks §10 follow-up note**（task 11.1 (a) "v3 #16 实战首例已修复 by phase1-hardening-w2-w3-w4-mimo-debt"） | grep W3 tasks.md 0 命中 "phase1-hardening" / "v3 高频盲点 #16 实战首例" | **NOT OK ✗** |
| **W1 archive design.md retroactive section**（task 11.1 (b) "消费此字段的已合回 round 列表"） | grep W1 design.md 0 命中 "phase1-hardening-w2-w3-w4-mimo-debt" / "消费 LapTelemetrySample.flags" / "W2/W3/W4" 列表 | **NOT OK ✗** |
| **本 round metrics.yaml**（task 8.3 / 9.2） | worktree 目录下未生成 `metrics.yaml`（仅有 4 工件 + 3 R1/R2/R3 review trail） | **NOT OK ✗**（计划在 L2 review 后写，符合 task 8.3 顺序） |

**§B 总计**：29 项 verify — OK = 26 / NOT OK = 3。其中 1 项（本 round metrics.yaml）按 task 8.3 顺序在 L2 后写，不算 finding；2 项（task 11.1 a/b）是真实漏项，需要纳入 L2 finding。

## §C 实施期 self-discovery 处置评估

### self-discovery #1：spec D3 drift 修订（caseI 浮点边界）
- **现状**：apply 期跑 caseI fail 揭示 spec "边界 ±1e-9 三种情况 deterministic 一致" 描述错误（truncation 跨整数边界本就不 deterministic，99.999... 截到 19 / 100.000... 截到 20）→ inline 修订 W3 spec 字段插值表 case I 描述为"同侧 deterministic + 跨边界 expected truncation"
- **处置评估**：合理 ✓ —— 这是 spec 描述错误（normative 描述与 truncation 数学定义不符），属于工件 hardening 期 inline 语义对齐，**不是 design Decision drift**（Decision 2 flags 最近邻 + Decision 3 computeAccelSegments 抽取等都未变）。F1 #17 严格要求是"design Decision drift"必须暂停；spec 字段表 normative 描述错误是 review 漏盘 + apply 修复的合理路径
- **改进建议**：metrics.yaml `review_findings_l2` 应加一条 P2 "spec D3 字段插值表 case I 描述错误（apply 期 inline 修订）— 反映 L1 R1/R2/R3 review 都未细看 truncation 边界数学语义，未来类似边界 case 应在 L1 R3 fresh-eyes 阶段加 normative-vs-test-expected 一致性 check"。**透明性方面 OK**

### self-discovery #2：mistake-1 git stash（已恢复）
- **现状**：apply 期 git stash 把 21 项 apply 改动 stash 走，立即 git stash pop 全部恢复
- **处置评估**：合理 ✓ —— 即时发现 + 即时修复，无 net loss；不需进 metrics.yaml
- **改进建议**：无；可能 worktree 工作流 caveat（task 10.6 沉淀）顺带提"apply 期慎用 git stash"

### self-discovery #3：mistake-2（apply 改动在主区，不在 worktree）
- **现状**：21 项 apply 代码改动全部用 absolute path Edit 改主区物理 .kt 文件 → 在主区 working tree（feature/track-tech-v2 分支）+ 13 commit ahead origin；worktree 仅含 4 工件 + 3 review trail；user 拍板 A 路径"apply 改动 + hotfix B 同主区，commit + push 一起 sort out"
- **处置评估**：透明声明 + user 决策 ✓ —— task 10.6 完整沉淀此 mistake + user A 路径决策 + 经验沉淀（"未来 round MUST 在 worktree cwd 执行 Edit"）。worktree 工作流约定写入 task §10.6 等待独立 sed memo（不在本 round scope）
- **改进建议**：metrics.yaml `cross_round_field_drift_resolved` 旁应加一个 `apply_in_main_worktree_drift: true` + comment 透明声明此 mistake；或在 metrics.yaml `divergence_reason` 段补一句"apply 路径 drift（22 项改动落主区 working tree 而非 worktree branch），user A 路径接受现状"。**当前 worktree metrics.yaml 还没创建，应在写时补此声明**

### self-discovery #4：baseline failing test
- **现状**：`TestSessionViewModelTrackLapTest.lapDebugMode_secondStartFinishClosesLapEvenWhenSectorChainIsIncomplete` 在 baseline HEAD 4326e11 已 fail（expected:1 但 0），根因 W4 commit `e2f4417` 改 `LAP_INVALIDATED_DEBOUNCE_MIN_COUNT 3→1` 让 lap debug mode sector chain 不完整时 1 帧 invalidating 立即触发 banner → lap 失效；W4 mimo 缺漏 test 同步
- **处置评估**：合理推延 ✓ —— task 10.5 完整沉淀根因 + user 拍板"sector chain 不完整时 lap 是否仍闭环"决策 + 独立立项 follow-up `fix-lap-debug-mode-sector-chain-test-after-min-count-1`（small 复杂度）
- **改进建议**：metrics.yaml `review_findings_l2` 加一条 P1 "baseline failing test (W4 mimo 缺漏 test 同步) — 推延 follow-up round `fix-lap-debug-mode-sector-chain-test-after-min-count-1`"。**透明性 OK**

## §D 新发现（adversarial — L2 漏盘 P0/P1/P2）

### P1-1: task 11.1 (a/b) **完全未实施**

- **位置**：`/Users/wattledgnata/traeProjects/gps-app/openspec/changes/archive/2026-05-04-lap-comparison-time-align/tasks.md`（缺 phase1-hardening 引用）+ `/Users/wattledgnata/traeProjects/gps-app/openspec/changes/archive/2026-05-04-lap-data-readers/design.md`（缺 retroactive section "消费此字段的已合回 round 列表"）
- **现状**：task 11.1 done condition 三项 ((1) W3 archive tasks.md 含 phase1-hardening 引用 / (2) W1 archive design.md 含"消费此字段的已合回 round 列表" + W2/W3/W4 角色 / (3) 本 round metrics.yaml 含 cross_round_field_drift_resolved）：(1) 0 命中 / (2) 0 命中 / (3) 待 L2 后写
- **问题**：v3 高频盲点 #16 normative 把"列消费方 round 列表"责任放在**发起字段扩展的 round（W1）**，本 round task 11.1 显式承诺把责任补回 W1 + 同步告知 W3，但**两处都未实施**。这违反 #16 的实战首例闭环承诺，也违反 task 11.1 done condition；后续 Tier 2 round 立项读 W3 archive tasks 时仍不会知道 flags 已经按最近邻处理，重复 #16 盲点
- **修订建议**：apply 期补两处：(a) W3 archive tasks.md §10 backlog 加一条 "v3 高频盲点 #16 实战首例已修复 by phase1-hardening-w2-w3-w4-mimo-debt round（B2/B4 修 LapAlignment 最近邻 + clamp/精确命中 grep gate 锁）；详见 archive review-l2-opus.md / spec.md:113 / design.md:72,81"；(b) W1 archive design.md 加 retroactive section "消费 LapTelemetrySample.flags 字段的已合回 round 列表"，含 W2 grep gate consumer / W3 LapAlignment consumer / W4 binary writer producer (deferred Phase 2) 三角色表

### P2-1: task 2.1 (A1) 文件路径与实际修复点漂移

- **位置**：`/Users/wattledgnata/traeProjects/gps-app/.worktrees/phase1-hardening-w2-w3-w4-mimo-debt/openspec/changes/phase1-hardening-w2-w3-w4-mimo-debt/tasks.md:13`
- **现状**：task 2.1 文件路径写"`proposal.md`"，但 D1↔D5 conflate 实际位置在 `metrics.yaml:11-14, 40-42`；done condition 字面量"D1 类型契约依赖" 0 命中 in proposal.md（实际命中在 metrics.yaml:11-12 comment）
- **问题**：task vs actual edit 路径漂移；实际修复确实落地（metrics.yaml D5 conflate 已修订 + W1 编译可独立 = D 标注），但 task 文档与 commit 不对齐 → 未来 retroactive 工作（如 phase 1 retrospective）按 task 路径核对会找错地方
- **修订建议**：metrics.yaml `review_findings_l2` 加一条 P2 "task 2.1 (A1) 文件路径漂移 — 实际修复在 W2 metrics.yaml 而非 proposal.md；task done condition '出现 D1 类型契约依赖' 字面量在 proposal.md 0 命中（实际命中在 metrics.yaml comment）；task 文档不更正，但归档时 transparently 沉淀此漂移"

### P2-2: spec D3 inline drift 与 F1 #17 边界

- **位置**：本 round spec.md case I 描述（W3 archive 同步）+ task §10.5 处置
- **现状**：apply 期 caseI fail 暴露 spec D3 描述错误（truncation 跨边界本就不 deterministic）→ inline 修订 spec normative；未走 #17 mini-proposal 流程
- **问题**：F1 #17 字面要求"design Decision drift"必须暂停 + 走 mini-proposal；spec 描述错误属于"工件 hardening 期 inline 修订" — F1 #17 边界含糊，未来 round 类似情况会有"算不算 drift？" 的判断困境
- **修订建议**：在 CLAUDE.md F1 #17 条款体内加一句 caveat："**适用范围**：仅 design Decision drift（如 Decision 编号 / 决议 / rationale 改）；spec normative 描述错误（apply 期跑测试发现）是 review 漏盘的合理 inline 修复，metrics.yaml `review_findings_l2` 加 P2 透明声明即可，不需走 mini-proposal"。**适合本 round 顺手补**（CLAUDE.md edit 1 行）or **deferred to F1 governance round 自然 trigger**

### P2-3: 本 round metrics.yaml 待写

- **位置**：`/Users/wattledgnata/traeProjects/gps-app/.worktrees/phase1-hardening-w2-w3-w4-mimo-debt/openspec/changes/phase1-hardening-w2-w3-w4-mimo-debt/metrics.yaml`（不存在）
- **现状**：worktree 工件目录仅 4 工件 + 3 R1/R2/R3 review trail；按 task 8.3 顺序应在 L2 review 后写
- **问题**：L2 review 完成是 metrics.yaml 撰写的前置条件（要含 review_findings_l2 + design_decisions_diverged_during_apply 字段）；本 review 完成后 CC 主会话 MUST 在 metrics.yaml 中：(1) `design_decisions_diverged_during_apply` 含 `[]`（本 round 无 design Decision drift）or 含 spec D3 inline drift（如果用宽义边界）；(2) `cross_round_field_drift_resolved` 含 LapTelemetrySample.flags W1→W3 修复条目；(3) review_findings_l2 含本 review §D 的 P1/P2/P2/P2 共 4 条 + apply 期 self-discovery 4 条
- **修订建议**：本 review 之后 CC 主会话立即写本 round metrics.yaml，含 P1-1 / P2-1 / P2-2 / P2-3 + apply self-discovery #1/#2/#3/#4

## §E plateau 信号 verify（L1 是否真 plateau）

CLAUDE.md L2 review 节"Plateau 早判信号"要求 L2 finding 应该集中在"实施细节防错"而非"设计层缺陷"。本 review §D 4 条 finding：

- P1-1（task 11.1 未实施）：**实施细节** — task 文档承诺 vs 实际落地的 path drift，不是设计层 P0
- P2-1（A1 路径漂移）：**实施细节** — task vs commit 路径不对齐
- P2-2（F1 #17 边界含糊）：边缘 — 算"governance 条款边界细化"，介于实施细节与设计层之间；但 F1 #17 主体仍 actionable（只是边界判定有歧义），**不构成 P0 重审**
- P2-3（metrics.yaml 待写）：**流程细节** — task 顺序

**结论**：L2 finding 集中在实施细节防错，**L1 plateau 不需 R4** ✓。

## 是否放行归档

**Conditional**

放行条件（≤ 3 条立即修）：

1. **MUST 修 P1-1**：apply 补 W3 archive tasks.md §10 backlog 一条 phase1-hardening 引用 + W1 archive design.md retroactive section "消费 LapTelemetrySample.flags 字段的已合回 round 列表"（W2 / W3 / W4 三角色表，参 W1 Decision 6 表）
2. **MUST 写本 round metrics.yaml**：含 `design_decisions_diverged_during_apply` `[]` + `cross_round_field_drift_resolved` 含 LapTelemetrySample.flags 条目 + `review_findings_l2` 含本 review §D 4 条 finding + apply self-discovery 4 条 + `divergence_reason` 段补"apply 路径 drift（22 项改动落主区 working tree 而非 worktree branch），user A 路径接受现状"
3. （可选 P2-2）：CLAUDE.md F1 #17 条款体内加 1 行 caveat "适用范围仅 design Decision drift；spec normative 描述错误属 review 漏盘的合理 inline 修复"

修完 P1-1 + P2-3 → 可发起 ff-only 合回 + push（按 task 8.5 / 8.6 user 拍板顺序）。

**评价**：本 round 22 项 P0/P1 + F1 governance + 跨 3 capability + 跨 round 共享字段 drift 大部分都修复到位（21/22 实施 + E4 deferred 透明声明 OK + 4 self-discovery 处置合理）；唯 task 11.1 retroactive memo 漏盘 + 本 round metrics.yaml 待写两项需收尾。L1 3 轮 plateau 决策**正确**（L2 finding 集中实施细节，无设计层 P0/P1）；apply 期 self-discovery 4 条处置合理，1 条（spec D3 drift）边界含糊但未实质损害闭环。
