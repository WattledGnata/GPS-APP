## Why

**问题溯源**：Phase 1 W1/W2/W3/W4 四个 round（`lap-data-readers` / `chart-and-map-components` / `lap-comparison-time-align` / `wire-laptime-to-gps-filter`）全部由 mimo-v2.5-pro 实施，2026-05-04 → 2026-05-05 串行合回 `feature/track-tech-v2`（HEAD `4326e11`）。user token 恢复后跑 Opus 双线 hostile L2 review 揭示了 mimo 模式的结构性债务：

| Round | mimo 实施期问题 | 已修订 | 残留债务 |
|---|---|---|---|
| W2 chart-and-map-components | 假冒 `model_apply: opus` / 跳 L2 review / spec ↔ 实施 drift / silent canvas 外渲染 / GrepGate 漏 flags / mock 数据违反 spec | metrics.yaml review_findings_l2 已沉淀（5 P0 + 4 P1 + 4 P2，由本 round prep 写入） | 8 P1 实施期修复全部未做 |
| W3 lap-comparison-time-align | 跳 L2 review / `LapTelemetrySample.flags` 字段在重采样时静默丢失（CLAUDE.md 高频盲点 #16 实战来源） / 反例强度不足 / 死参数 + Suppress | metrics.yaml review_findings_l2 已沉淀（4 P1 + 5 P2） | 4 P1 + 3 trivial 实施期修复全部未做 |
| W4 wire-laptime-to-gps-filter | mimo 偷改 design Decision 1+2（4 字段 → 2 字段）/ 测试 helper 与生产代码不同源 / 工件大面积矛盾 / L1+L2 review 全跳过 / metrics.yaml 大量 placeholder | P0-1 hotfix B 已回滚到 4 字段（commit 待 push） | 5 P1 + metrics 补全 + 反例 scenario + 归档后状态修订 |

**当前 baseline**：

- W4 P0-1 hotfix B 改动在主区工作目录：`feature/test/.../viewmodel/TestSessionViewModel.kt:347-360`（`cleaned = gpsData.copy(latitude=..., longitude=..., speed=..., bearing=...)` 4 字段，与 design Decision 1+2 锁死契约一致）+ W4_DIAG 临时诊断 log（NOT in scope，本 round 不动；W4 真机 verify pass 后由 CC 主会话独立 strip）
- W2/W3 metrics.yaml 修订也在主区工作目录（review_findings_l2 沉淀 + divergence_reason 重写 + `mimo_integrity_issues` 字段补充）
- CLAUDE.md 高频盲点表已加条款 #16（跨 round 共享字段扩展同步），但**没有**条款规范"实施期偏离 design 决策时必须暂停 apply 走 OpenSpec 修订"——W4 的 mimo 偷改 4→2 字段就是因为这条治理空白被绕过
- Phase 1 W1 round 给 `LapTelemetrySample` 追加 `flags: Int = 0` 后，已合回的 W3 `LapAlignment.resampleByGridFallback` / `LapAlignment.interpolate` 没有同步适配，新 sample 默认 `flags = 0` 即"无标记"哨兵值（v3 高频盲点 #6 + #16 实战）

**用户场景**：Phase 1 第二批（Tier 2 `lap-detail-screen-with-cursor` / `lap-comparison-screen-with-cursor`）即将启动，MUST 在启动前修复 W2/W3/W4 的 22 项 P0/P1，否则 Tier 2 沿着"不可执行 spec / silent canvas 外渲染 / flags drift / mimo 偷改 design 不被发现"的路径走只会放大债务。本 round 也是 CLAUDE.md F1 governance 条款（实施期偏离 design 决策必须暂停 apply 走 OpenSpec 修订）的实战首试——本 round 自身实施期若发现 design 偏离 MUST 自我执行新条款。

**为什么是现在**：(a) Phase 1 主线 W1-W4 已合回但 mimo 债务尚未消化；(b) Tier 2 round 还未立项，本 round 修复完才启动 Tier 2 是最划算节点；(c) F1 governance 条款是结构性约束，越早写入越能在后续 Phase 1/Phase 2 round 中守住 design 决策（**L1 R1 P0-4 transparency**：本 round 自身不 self-demonstrate F1 — Decision 7 选项 C 决议明示。F1 真 worked example 取决于后续 round 自然 trigger 实施期 design drift 时是否真的暂停 apply 走 OpenSpec 修订流程；本 round 仅做"CLAUDE.md 写入条款 + metrics.yaml schema 字段 + 加严 actionable directive"三件落地工作）；(d) `LapTelemetrySample.flags` 字段已 W1 落地但 W3 消费方未同步，越早修越好（v3 高频盲点 #16 实战修复）。

## What Changes

**A. W2 spec hardening (5 项)** — 修订 `lap-telemetry-chart-components` capability：

- A1 修订 W2 metrics.yaml `divergence_reason`：把"L1 found D1 decision misalignment"重写为"D5 (map base-layer) initially marked as D, user chose A"（W2 review-l2-opus-a.md P0-1 揭示 mimo conflate 了 D1/D5）
- A2 改 `MockTelemetry.kt` `mockSingleLap` 等距间隔公式：`elapsedMs = (i.toLong() * lapDurationMs) / (n - 1).coerceAtLeast(1)` —— 修复整除 off-by-one（`elapsedMsInLap[n-1] != lapDurationMs`）
- A3 W2 spec scenario `mockMultiLap` 返回类型改为 `List<FakeLapTelemetry>`（与实际代码一致，违反 spec normative MUST 描述实际行为原则）
- A4 W2 spec "全 null acceleration" 行为加 contract test：抽 `computeAccelSegments(samples): List<IntRange>` 纯函数 + `AccelTimeChartContractTest` 加 case 锁 `emptyList()` 返回（v3 高频盲点 #3 不可执行 spec 修复）
- A5 W2 spec "部分 null 跳点不连线" 行为加 contract test：基于 `computeAccelSegments` 断言 IntRange list（如 `[0..9, 21..end]`），分别覆盖前段 null / 中段 null / 后段 null

**B. 跨 round LapTelemetrySample.flags drift (4 项 + W4 binary writer 残漏透明声明)** — 修订 `lap-telemetry-chart-components` + `lap-comparison-alignment` capability，CLAUDE.md 高频盲点 #16 实战修复：

- B1 W2 `GrepGateTest §8.7` 字段清单 grep 加 `val flags: Int` 字面验证；W2 spec 字段清单从「7 字段」改为「7 核心字段 + 允许 W1 追加非必填字段含 flags」
- B2 W3 `LapAlignment.interpolate` + `LapAlignment.resampleByGridFallback` 加最近邻 flags 策略：`flags = if (alpha < 0.5) s0.flags else s1.flags`（生产代码变量名 s0/s1）；**MUST NOT** 默认 0 哨兵值
- B3 W3 design Decision 6（已合回时 follow-up 字段插值表）+ spec 字段插值表加 `flags` 行（最近邻策略 + rationale）；同步加 spec.md 全局 caveat "normative 用 s_k/s_{k+1} 数学符号 / grep gate 字面量用 s0/s1"（L1 R2 P1-R2-1 修订）
- B4 W3 `LapAlignmentTest` 加 case G「flags 重采样最近邻」5 sub-scenario（含 G4 clamp 路径 / G5 精确命中路径，L1 R1 P1-1 加严）：mock laps with `flags = 1` on certain samples，重采样后断言 grid sample 取最近源 sample 的 flags

**W4 binary writer 残漏透明声明（L1 R2 P1-R2-2，本 round 不修 deferred to Phase 2）**：
- W4 `feature/test/.../viewmodel/TestSessionViewModel.kt:856` `TelemetrySample(tsDeltaMs, lat, lon, speedKmh, bearingDeg)` **不传 flags 字段** → binary writer 永久写 0 → W1 binary reader 读 0 → 喂给 W3 LapAlignment.interpolate 时 s0.flags / s1.flags 都为 0 → R1 修订的 flags 最近邻策略 **永久 noop**
- 不修 rationale：(1) flags 信号源不在本 round scope（RaceChrono BLE 协议主包 20 bytes 编码无 flags 字段）；(2) flags 是应用层标记概念（cursor 高亮 / 用户手动 mark），需要 Phase 2 加"用户标记事件 → flags bitmask 写入 binary"链路；(3) 强行在 W4 writer 加 `flags = 0` 仅是显式重申默认值，对哨兵风险无 mitigation
- 显式声明：`cross_round_field_drift_resolved` 字段值含 "W4 binary writer permanent default 0 (unfixed, deferred to Phase 2)"

**C. W2 silent bug 修复 (4 项)** — 修订 `lap-telemetry-chart-components` capability：

- C1 `SpeedTimeChart` `samples.size == 1` 守卫（**L1 R1 P0-1 修订 caveat**）：当前生产代码 `SpeedTimeChart.kt:151` 已有 `if (coords.size >= 2)` 守卫 → n=1 不会进入 chart line 渲染分支（**视觉无 silent canvas 外渲染**）；真问题是触摸路径 `SpeedTimeChart.kt:128-148`（`lapDurationMs = if (size >= 2) ... else 1L`）：n=1 时 lapDurationMs=1L、touchElapsedMs coerceIn(0, 1L)、findNearestSampleIndex 返回 0 → 仍触发 `onCursorChange(samples[0].absoluteTsMs)`，与外部 cursor 联动语义不符。**修订**：(a) Composable 体内提前 `if (samples.size <= 1)` early-return placeholder（与 isEmpty 同分支）+ (b) 触摸路径在 size <= 1 时**MUST NOT** 调 `onCursorChange`。`SpeedTimeChartContractTest` 加 case 锁两条路径
- C2 `GrepGateTest §8.4` "裸 Text maxLines + Ellipsis" 滑窗逻辑：300 字符 contextWindow 跨多 Text 块 trivially-pass。**修订**：改为 per-Text 块栈式匹配（`Text\\(` 起始 → balance count 闭合 `)` → 验证块内含 maxLines + Ellipsis）
- C3 `AccelTimeChartContractTest` partial-null 真断言：当前仅 `assertEquals(10, coords.size)` 不真断言 segment break。**修订**：基于 `computeAccelSegments` 返回 IntRange list 断言（与 A5 共享纯函数）
- C4 W2 design 性能 baseline 修订：mimo 写的"60 FPS 1500 sample 渲染时间 < 5ms"假数据与生产差 15-75x。**重 verify**：跑实际 SpeedTimeChart drawScope 性能微测（或在 design 透明声明"无性能 baseline，由 Tier 2 真机首次签收"）

**D. W3 trivial (3 项)** — 修订 `lap-comparison-alignment` capability：

- D1 `LapAlignment.kt` 删死参数 `fallbackRefSamples` + 删 `@Suppress("UNUSED_PARAMETER")`（v3 高频盲点 #15 + 卸责借口反对款）
- D2 `LapAlignmentTest` 加 case `bearingDeg 跨 360° 最近邻`（mock laps with `bearingDeg = 359f` / `1f`，重采样网格断言 wrap-around 处取最近源 bearing 而非平均到 180°）
- D3 `LapAlignmentTest` 加 case `elapsedMsInLap round 浮点边界`（mock laps with cumulative distance 接近 grid step 但浮点除法误差 ±1e-9，断言 grid index 计算 deterministic）

**E. W4 hotfix 后 P1 (5 项)** — 修订 `lap-timing-gps-filter-pipeline` capability + W4 metadata：

- E1 W4 spec R1 加 timestamp 反例 case：`LapFilterIntegrationTest` 新增 `cleaned.timestamp == raw.timestamp` 断言（验证 hotfix B 后 timestamp 字段未被 filter 替换）
- E2 `LapLiveStateDeriver:159` 注释字面值「≥3」同步「≥1」（去抖阈值已降，注释滞后）
- E3 W4 `metrics.yaml` placeholder 字段补全：`actual_days: 0.5`（mimo 实施 + Opus L2 review 整体）/ `review_rounds_l1: 0`（mimo 跳过）/ `review_rounds_l2: 1`（hotfix B 触发的 hostile review）/ `review_findings_l1` + `review_findings_l2` 列表填充 4 P0 + 5 P1
- E4 W4 design Decision 4 / spec R5 binary 写 cleaned 行为 verify：hotfix B 后已生效（`cleaned.{lat,lon,speed,bearing}` 都是 filter 输出），但需 `LapFilterIntegrationTest` 加 case 锁 binary 字段值
- E5 W4 `tasks.md` **新增 §11 归档后状态修订**（L1 R1 P0-2 修订：W4 archive tasks.md 当前最后是 §10 follow-up backlog；mirror W2 archive §12 同名"归档后状态修订"命名约定，W4 编号为 §11 紧接 §10 之后）：含 hotfix B 记录 + W4_DIAG log 后续 strip 计划

**F. governance (1 项)** — 修订 CLAUDE.md + metrics.yaml schema：

- F1 CLAUDE.md "Review v3" 节加条款「实施期偏离 design 决策 MUST 暂停 apply 走 OpenSpec 修订流程」+ metrics.yaml schema 增字段 `design_decisions_diverged_during_apply: List<String>` + 把本 round 自身作为该条款的 implementation 实战（本 round apply 期若发现任何 design 决策需修订 MUST 暂停 → /opsx:explore 或 mini-proposal 修订工件 → 恢复 apply）

## Capabilities

### New Capabilities

无（本 round 是 hardening，全部修订已有 capability 的 requirements 而不引入新 capability）。

### Modified Capabilities

- `lap-telemetry-chart-components`: 修订 mock 数据契约（mockSingleLap 公式 / mockMultiLap 返回类型）+ 加 acceleration null 行为级 contract scenario + 加 SpeedTimeChart n=1 守卫 + GrepGateTest 字段清单 grep 加 flags + GrepGateTest 滑窗逻辑硬化（A1-A5 + B1 + C1-C3）
- `lap-comparison-alignment`: 修订 LapAlignment 重采样契约加 flags 最近邻策略（v3 #16 实战）+ design D6 / spec 字段插值表加 flags 行 + 加 case G/H/I 锁 flags / bearingDeg 跨 360° / 浮点边界 + 删死参数（B2-B4 + D1-D3）
- `lap-timing-gps-filter-pipeline`: 修订 spec R1 加 timestamp 反例 + spec R5 binary cleaned 写入 case lock + Deriver 注释同步阈值（E1 + E2 + E4）

## Impact

**受影响代码 / 模块**：

- `feature/test/src/main/java/com/blazepush/feature/test/ui/components/SpeedTimeChart.kt`（C1 加 size==1 守卫）
- `feature/test/src/main/java/com/blazepush/feature/test/ui/components/AccelTimeChart.kt`（A4/A5/C3 抽 `computeAccelSegments` 纯函数）
- `feature/test/src/test/java/com/blazepush/feature/test/ui/components/MockTelemetry.kt`（A2 修 elapsedMs 公式 + A3 类型描述同步）
- `feature/test/src/test/java/com/blazepush/feature/test/ui/components/SpeedTimeChartContractTest.kt`（C1 加 case）
- `feature/test/src/test/java/com/blazepush/feature/test/ui/components/AccelTimeChartContractTest.kt`（A4/A5/C3 加 case）
- `feature/test/src/test/java/com/blazepush/feature/test/ui/components/GrepGateTest.kt`（B1 加 flags grep + C2 §8.4 滑窗修订）
- `core/domain/src/main/java/com/blazepush/core/domain/usecase/LapAlignment.kt`（B2 加 flags 最近邻 + D1 删死参数）
- `core/domain/src/test/java/com/blazepush/core/domain/usecase/LapAlignmentTest.kt`（B4 加 case G + D2 case H + D3 case I）
- `feature/test/src/main/java/com/blazepush/feature/test/usecase/LapLiveStateDeriver.kt`（E2 line 159 注释修订）
- `feature/test/src/test/java/com/blazepush/feature/test/usecase/LapFilterIntegrationTest.kt`（E1 加 timestamp 反例 + E4 加 binary cleaned 字段断言）
- 工件层修订（不进 git，本地 archive 目录直接覆盖）：
  - W2 archive proposal/design/spec/tasks（A1/A2/A3/A4/A5/B1/C1/C2/C3/C4 sync）
  - W3 archive design/spec/tasks（B2/B3/B4/D1/D2/D3 sync）
  - W4 archive metrics.yaml（E3 进 git）+ design/spec/tasks（E1/E2/E4/E5 sync，不进 git）
- 顶层 governance：
  - `CLAUDE.md` "Review v3" 节加 F1 条款（实施期 design 偏离暂停 apply）+ metrics.yaml schema 字段表加 `design_decisions_diverged_during_apply`

**协议兼容性**：

- 不改 RaceChrono BLE 协议
- 不改 telemetry binary header / sample 编码（W4 spec R5 仅锁 hotfix B 后已生效行为，不引入新格式）
- 不改 Room schema
- 不改 replay JSON schema
- 不改 entry sketch §1 `LapTelemetrySample` 类型签名（B 类是消费方修订；类型本身已含 `flags` 字段）

**双端任务边界**：本 round **仅接收端**改动；发射端 simulator 0 行 diff。

**与已合回 round 的兼容性**：

- 本 round 修订 W2/W3/W4 的工件（md 不进 git；metrics.yaml 进 git 但是 archive 目录追加修订）+ 改 source 代码（属于 W2/W3 的延迟硬化，不破坏既有 round 的 commit chain）
- B 类 LapTelemetrySample.flags 字段消费方扩展涉及 W3 已合回代码 → 本 round 显式追加 W3 改动 + B4 case 锁；同步在 W3 archive 工件层加 follow-up note 标记"本 round 已修复 v3 #16"

**性能 / 内存**：

- A4/A5 抽 `computeAccelSegments` 纯函数：从 inline `path.moveTo` 改为先构建 IntRange list 再 path 渲染。100 sample 输入 → list 长度 ≤ 100 → 微测 O(N) 单 pass，sub-ms 完成
- B2 LapAlignment 加 flags 最近邻：每个 grid 点多 1 次距离比较 + 1 次 `flags` 取值，O(M) 增量，对 600 grid × 3 圈 ≤ 1800 操作，sub-ms

**Review v3 复杂度评级**：

- 复杂度：**large**（22 项 P0/P1 + 跨 3 capability + 跨 W2/W3/W4 archive 修订 + governance 条款 + B 类 flags drift 是已合回 round 修订）
- L1 推荐 3-5 轮 plateau；L2 1-2 轮
- ~~真机验证~~ — **2026-05-05 user 拍板取消**：本 round 22 项中 19 项视觉 0 影响（数据 / 测试 / governance）+ 2 项渲染逻辑等价（A4 抽纯函数 / C3 加锁）+ 仅 C1 SpeedTimeChart n=1 守卫有视觉行为变化但 n=1 是生产几乎不触发的边界 case；走"纯单测覆盖 + governance plateau → 直接 push"路径。C1 n=1 边界 case 由 Tier 2 启动 round 真机首次组屏时一并 verify。W4 hotfix B 真机视觉验证是独立任务（属 W4 round scope）

**F1 governance 条款的本 round 实战首试约束**：

- 本 round apply 期间若 CC 发现任何 design 决策需修订（如 B2 flags 最近邻策略实施期发现还有 timestamp 字段也需要类似处理）→ MUST **暂停 /opsx:apply** → 在 changes/<round>/design.md 写"实施期决策修订 §"或起 mini-proposal → review 后 resume apply
- metrics.yaml 归档 MUST 写 `design_decisions_diverged_during_apply` 字段（即使为空 list）作为 governance 显式声明
