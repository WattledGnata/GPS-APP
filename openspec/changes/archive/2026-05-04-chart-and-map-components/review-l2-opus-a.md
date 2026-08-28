# L2 Adversarial Review — Opus 子 agent A 线（chart-and-map-components）

> 触发时机：2026-05-05 W2 归档 commit `28e46fb` 之后（mimo 实施期跳过 L2，user 统一协调时补跑）
>
> Reviewer：CC 主会话 spawn 的 general-purpose 子 agent，model=opus，不持有此 round 主会话 context
>
> Codex review 因后端失效（reconnect 失败 + 僵尸任务已 cancelled）由 Opus 双线替代
>
> Round 代码作者：mimo-v2.5-pro

## P0/P1/P2 findings

### [P0-1] metrics.yaml 与 proposal.md 关于 D1 决议直接矛盾（governance fail）

**位置**：
- `metrics.yaml:13` — `divergence_reason: "L1 found D1 decision misalignment between user choice and artifacts; resolved by confirming D1=D with user"`
- `proposal.md:20` — "user 拍板 = A"
- `design.md:34-54` — D1 定义为 "W1 类型契约依赖与本 round 编译可独立"（决议 = D，与 A 无可比性）
- `design.md:105-117` — D5 才是 "TrackPolylineMap 底图策略"，user 拍板 = A 与 proposal 一致

**问题**：metrics 把 "D1=A vs D" 描述当作 divergence reason，但 D1 在 design 是类型契约依赖（决议=D），真正的 user-A-vs-D 决议是 D5。mimo 自始至终 conflate 了两个无关的 D；W1 P1-1 同款盲点（commit message 不严谨）。

**修订建议**：metrics.yaml 把 "P0: D1 decision discrepancy" 重写为 "P0: D5 (map base-layer) initially marked as D, user chose A"。

### [P0-2] mockSingleLap 等距间隔语义违反 spec scenario「正例」

**位置**：spec.md:156（"sample[i].elapsedMsInLap == i * 60_000 / 99"）+ MockTelemetry.kt:24-26 实现

**现状**：`intervalMs = 60_000 / 99 = 606`（整除），`elapsedMs[99] = 99 * 606 = 59_994` —— **不等于** lapDurationMs (60_000)。

**问题**：spec scenario "elapsedMsInLap == i * 60_000 / 99" 字面违反；下次有人改 n / lapDurationMs 会触发 silent off-by-one。

**修订建议**：`val elapsedMs = (i.toLong() * lapDurationMs) / (n - 1).coerceAtLeast(1)` 让 elapsedMs[n-1] 严格 == lapDurationMs。

### [P0-3] spec scenario「mockMultiLap 三圈不同 pace」返回类型描述错

**位置**：spec.md:158（"返回 List<LapTelemetry>"）+ MockTelemetry.kt:69-87（实际返回 `List<FakeLapTelemetry>`）

**问题**：spec 写假签名。违反 spec normative MUST 描述「实际行为」原则。L1 P0「GForceChart.kt is inside SpeedChart.kt」是同款盲点。

**修订建议**：spec.md 改为 "返回 List<FakeLapTelemetry>（W1 round 合回后 follow-up round wire-mock-telemetry-to-w1-real-classes 切换）"。

### [P0-4] spec scenario「全 null acceleration」契约零自动化覆盖（不可执行 spec）

**位置**：spec.md:88-90 + AccelTimeChartContractTest.kt:46-57

**现状**：spec normative 是 "MUST 显示 NO ACCEL DATA" / "MUST 触摸不触发 callback"，但 contract test 仅断言 `computeChartBounds` 返回 (0.0, 1.0)，零行为级覆盖。

**问题**：v3 高频盲点 #3「不可执行测试」教科书例子。

**修订建议**：抽 `computeAccelSegments(samples): List<IntRange>` 纯函数 + 单测断言；或透明声明"组件级行为只在 Tier 2 真机首次联动签收"加到 design.md Risks。

### [P0-5] spec scenario「部分 null 跳点不连线」零自动化覆盖

**位置**：spec.md:84-86 + AccelTimeChartContractTest.kt:60-71（仅断言 coords.size==10，不断言 segment 拆分语义）

**问题**：partial-null spec normative 行为零覆盖。任何后续重构（如换成 path.moveTo per-segment）都可能 silent regress 而 contract test 全绿。

**修订建议**：抽 `computeAccelSegments` 纯函数 + 单测断言 IntRange list（[0..9, 21..end]）。

### [P1-1] SpeedTimeChart `samples.size == 1` 时 lapDurationMs=1L 触发 silent canvas 外渲染

**位置**：SpeedTimeChart.kt:50-54

**现状**：n=1 时 lapDurationMs=1L；若 elapsedMsInLap=30_000，x = 30_000_000 px 远超 canvas。

**问题**：spec scenario "n=1 单 sample" 仅断言 `coords[0].x == 0f`（前提 elapsedMsInLap==0）；生产代码对 elapsedMsInLap > 0 的单 sample 会渲染到 canvas 外。

**修订建议**：单 sample 时直接走 placeholder 分支不画 chart line；或 x 强制 coerce 到 canvasSize.width / 2。

### [P1-2] grep gate §8.4「裸 Text 必须有 maxLines + Ellipsis」滑窗 trivially-pass 风险

**位置**：GrepGateTest.kt:91-104

**现状**：300 字符滑窗会跨越多个 Text 调用——若文件内任意前序 Text 块包含 maxLines/Ellipsis，后续 Text 滑窗也能命中。

**问题**：v3 高频盲点 #7「grep gate trivially pass」+ #8「跨文件 grep gate 范围错」组合变种。当前实现仅在「裸 Text 1 个 / 文件」场景安全。

**修订建议**：改成"找 Text 后 balance count 闭合 `)` 范围内验证"或"按行紧随 Text 之后 N 行"。

### [P1-3] grep gate §8.7 LapTelemetrySample 字段 grep 漏 flags（spec drift + 哨兵风险）

**位置**：GrepGateTest.kt:138-146（锁 7 字段）+ LapTelemetry.kt:13-22（实际 8 字段含 `flags: Int = 0`）

**问题**：W1 commit 3c2f2d9 后追加 flags 字段；spec.md 仍写 7 字段，gate 没扫到 flags → 假绿门槛 + spec 与代码 drift。MockTelemetry.kt 也只填 7 字段，第 8 个 flags 默认 0 = 哨兵值（v3 #6 "NOT NULL DEFAULT 哨兵风险"）。

**修订建议**：spec.md 字段清单从「7 字段」改为「7 核心字段 + 允许 W1 追加非必填字段」；GrepGateTest §8.7 加 `val flags: Int` 字面量验证。

### [P1-4] review_rounds_l2: 0 + L2 跳过 governance violation

**位置**：metrics.yaml:4

**问题**：CLAUDE.md Review v3 明确 "L2 必跑：每个 round /opsx:apply 完成后 + 归档前"。归档已发生（28e46fb），L2 在归档时未跑——governance violation。本 review 已抓 5 P0 + 4 P1 + 4 P2 共 13 项，证明 L2 跳过是高风险。

**修订建议**：把本 review (A 线) 与 B 线发现合并写回 metrics.yaml `review_findings_l2`，review_rounds_l2 从 0 改为 ≥1。

### [P2-1] commit message 28e46fb 写错 spec 路径名

写 `specs/charts-and-map/`，实际 `specs/lap-telemetry-chart-components/`。归档后无法 amend，记入 metrics.yaml findings 透明声明。

### [P2-2] SectorBar 边界 `boundaries.last() > lapEnd` 未透明处理

SectorBar.kt:55-56 + computeSectorBounds line 28-29。当 boundaries 越界时 windowed(2) 把异常 segment 当最后一段，xEnd 越界被 coerceIn 拉回。

### [P2-3] AccelTimeChartContractTest 缺 partial null 行为级断言

仅断言 coords.size==10，不断言 segment 拆分。

### [P2-4] design.md Risks 缺 W1 后续追加字段同步策略（v3 #14）

W1 加 flags 后 W2 MockTelemetry 没填 flags（虽 default 0 不报错，但语义可能 silent regress）。

## 是否放行

**NO**——条件：至少 P0-1/P0-4/P1-3/P1-4 修订才放行。归档已发生（28e46fb），但 metrics.yaml `review_rounds_l2: 0` 必须补；P0-1/P0-3/P0-4 需在工件层修订；P0-2/P0-5/P1-1 是代码 silent bug 需要补丁。

**推荐选 (C) 全部 P0/P1 写到 metrics review_findings_l2 + tasks §11 backlog，留待 Tier 2 round 接 W2 时同步消化**——最经济（35 cases 全绿、字段稳定、Tier 2 还没启动）。

## 修订清单

### 立即修（spec / metrics / design 文档级，不重开 round）
1. metrics.yaml line 13 divergence_reason 重写（D1 → D5）
2. spec.md mockMultiLap Scenario 返回类型 List<LapTelemetry> → List<FakeLapTelemetry>
3. spec.md LapTelemetrySample 字段清单从 7 → 8（含 flags）
4. P0/P1/P2 全部加进 metrics.yaml review_findings_l2

### follow-up round 立项（建议名 fix-w2-mimo-silent-bugs，medium）
5. P0-2 mockSingleLap intervalMs 公式改 `(i.toLong() * lapDurationMs) / (n - 1)`
6. P0-4/P0-5/P2-3 抽 `computeAccelSegments` 纯函数 + 单测
7. P1-1 SpeedTimeChart n=1 占位
8. P1-2 GrepGateTest §8.4 滑窗改 balance count

## metrics.yaml 建议值

```yaml
review_rounds_l2: 1  # A 线本次（合并 B 线后保持 1）
review_findings_l1:
  - "P0: D5 (map base-layer) initial misalignment (user chose A, artifacts initially used D); design.md 已修正"  # 修订（原 D1 描述错）
  - "P0: GForceChart.kt is inside SpeedChart.kt, not a separate file"
  - "P0: MetricNumber called inside Canvas drawScope (not composable context)"
  - "P1: Vico already in deps but rejected for touch protocol reasons"
  - "P1: Design risks section missing @Preview and touch protocol risks"
  - "P1: Color token mismatches (BackgroundDark→Background, AccentSecondary→Purple)"
review_findings_l2:
  - "P0: metrics divergence_reason D1↔D5 conflate (governance, mimo commit body 描述链多处错)"
  - "P0: mockSingleLap intervalMs 整数除法 silent off-by-one (elapsedMsInLap[n-1] != lapDurationMs)"
  - "P0: spec mockMultiLap 返回类型描述 List<LapTelemetry> 与代码 List<FakeLapTelemetry> 不一致"
  - "P0: spec all-null acceleration 占位 + 触摸 0 callback 行为零自动化覆盖（dead spec）"
  - "P0: spec partial-null segment 行为零自动化覆盖"
  - "P1: SpeedTimeChart n=1 lapDurationMs=1L 在 elapsedMsInLap>0 时 silent canvas 外渲染"
  - "P1: GrepGateTest §8.4 maxLines/Ellipsis 滑窗 trivially-pass 风险（多 Text 块共享 300 char window）"
  - "P1: GrepGateTest §8.7 LapTelemetrySample 字段 grep 漏 flags（W1 commit 3c2f2d9 追加，spec drift）"
  - "P1: review_rounds_l2 跳过为 governance violation"
  - "P2: commit 28e46fb body spec 路径名错"
  - "P2: SectorBar boundaries.last() > lapEnd 边界未透明处理"
  - "P2: AccelTimeChartContractTest partial null 缺行为级断言"
  - "P2: design.md Risks 缺 W1 后续追加字段同步策略"
divergence_reason: "(1) L1 找到 D5 map base-layer user-A vs artifacts-D 偏移; (2) L2 跳过为 governance violation"
model_apply: "mimo-v2.5-pro"  # 修正：原写 opus 不准确
```

## Adversarial 收尾

mimo 工件 verbose 但写错关键决策（D1 vs D5 conflate / commit message 路径错 / spec 与代码 drift）这一模式在 W1/W2 重复出现，是结构性问题。建议 Phase 1 W1-W4 mimo 执行的 round MUST 全部回补 L2 review。
