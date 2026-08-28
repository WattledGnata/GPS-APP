# L2 Adversarial Review — Opus 子 agent B 线（chart-and-map-components 差异化角度）

> 触发时机：2026-05-05 W2 归档 commit `28e46fb` 之后
>
> Reviewer：CC 主会话 spawn 的 general-purpose 子 agent，model=opus，差异化角度（区别于 A 线模板）：跨 round 字段兼容 + 测试基础设施完整性 + Tier2 plug-in 验证 + Vico rejection 真实性
>
> Round 代码作者：mimo-v2.5-pro

## 测试结果

35 cases 全绿（6+10+8+7+4），编译 0 错误。

## 发现列表

### [P1] FakeLapTelemetry → 真实 LapTelemetry 切换路径承诺过于乐观（半闭环风险）

**位置**：MockTelemetry.kt:9-14 + tasks.md §11.2

**现状**：FakeLapTelemetry 仅 4 字段；真实 LapTelemetry (W1 已 land) 9 字段，多 5 个 non-null 字段（`sessionId / lapIndex / lapDurationMs / trackId / trackNameSnapshot`）。tasks.md §11.2 描述切换路径过于乐观（"删除 FakeLapTelemetry / import / 改返回类型"）——简单 import 切换会让 mockSingleLap / mockMultiLap 编译失败。

**修订建议**：更新 tasks.md §11.2 明确 follow-up round scope 必须包括"补全 5 个 W1 容器字段"。

### [P1] design 性能 baseline 与生产数据量差 15x-75x（决策最优性 vs 假数据 baseline）

**位置**：design.md D2 / Risks 段

**现状**：
- 项目 memory 明确 25 Hz 采样率（即使降采样到 5 Hz）
- 25 Hz × 60s lap = 1500 sample/lap；5 min lap = 7500 sample
- mock data 默认 n=100 —— 与生产数据量差 15x-75x
- contract test 仅测 n=100，没有 n=1500/n=7500 性能 baseline
- cursor highlight 用 `samples.indexOfFirst` (O(n))，4 chart × 60fps × 7500 = 30k ops/frame

**问题**：design D2 拒绝 Vico 的 "Compose Canvas 自绘性能足够 <16ms @100 sample" baseline 与真实生产数据量不一致，决策依据有缺陷。

**修订建议**：
- (a) mockHighFidelityLap(n=1500) mock 形态作为性能 baseline
- (b) follow-up round 新增 perf benchmark
- (c) cursor highlight 用 binarySearch 复用 `findNearestSampleIndex`（O(log n)）—— SpeedTimeChart line 162 / AccelTimeChart line 102 / TrackPolylineMap line 84 三处

### [P1] gate 8.7 LapTelemetrySample 字段断言与实际生产签名不严格对齐（spec-code 漂移）

**位置**：GrepGateTest.kt:138-150

**现状**：gate 锁 7 字段（绝不接受 flags: Int = 0）；W1 commit 3c2f2d9 加了 `flags: Int = 0` —— 实际 8 字段；gate 仍 pass，因为只验证"前 7 字段恰好命中 1 次"，没锁"恰好 7 字段"或"无 flags"；spec.md line 213 仍写 7 字段。

**问题**：spec/code 漂移。下次 round 起草新工件按 7 字段假设会漏 flags。

**修订建议**：
- (a) spec.md Requirement #8 增加 8 字段含 `val flags: Int = 0`
- (b) gate 8.7 增加 `assert content.contains("val flags: Int")`
- (c) 这条修订属"spec 与生产代码同步" L2 必修项

### [P1] AccelTimeChart partial-null contract test 没真正断言 segment break 行为（dead spec）

**位置**：AccelTimeChartContractTest.kt:60-71 + spec D9 #6

**现状**：spec 要求"部分 null 跳点不连线 → segment 拆分正确"；contract test 仅验证 `coords.size == 10`，不验证 segment 数 / null 段不连线；segment 拆分逻辑在 AccelTimeChart.kt:85-99 Composable 内部，纯函数 contract test 覆盖不到。

**修订建议**：
- (a) 把 segment break 逻辑拆为 `computeAccelSegments(samples): List<IntRange>`
- (b) AccelTimeChartContractTest 加 case 验证 partial-null 输入下返回正确 ranges
- (c) follow-up round add-compose-ui-test-for-cursor-drag 中追加 segment break 视觉自动化覆盖

### [P2] gate 8.2 / 8.3 / 8.5 / 8.6 baseline 0 命中 → trivially pass 风险

GrepGateTest.kt lines 64-71 (8.2), 73-81 (8.3), 107-115 (8.5), 117-129 (8.6)。4 个 gate 当前 baseline 都是 0 命中，与 W1 round P1-2 同款盲点。

**修订建议**：L1 review 增加"反例 detection"步骤；或在 GrepGateTest 加 `gate negative example` 测试用 string 拼违规模式 + assert regex 命中。

### [P2] mock data integer division drift（同 A 线 P0-2，B 线降级 P2）

`mockSingleLap(n=100, lapDurationMs=60_000)`：intervalMs = 60000/99 = 606（整除）→ last.elapsedMsInLap = 59994 (非 60000)。所有 contract test 用 last/first 做 lapDuration 计算自洽算回所以 pass，**功能不影响**但 API 参数语义不直观。

### [P2] D1 决策修订流程含混（metrics divergence_reason 表述不清）

metrics divergence_reason 文字含混。修订建议同 A 线 P0-1。

### [P2] cursor highlight O(n) vs O(log n) findNearestSampleIndex 一致性

4 个 chart 组件 cursor highlight 行：
- SpeedTimeChart.kt:162 / AccelTimeChart.kt:102 / TrackPolylineMap.kt:84 用 `indexOfFirst { it.absoluteTsMs == cursorAbsoluteTs }` (O(n))
- 触摸协议用 binarySearch (O(log n))

在 1500-7500 sample × 60fps 拖动下可能成瓶颈。

**修订建议**：cursor highlight 内部改用 `findNearestSampleIndex` 配合外部 cursorAbsoluteTs 转 elapsedMsInLap，或新增 `findSampleByAbsoluteTs(samples, absoluteTsMs): Int` 也走 binarySearch。

## 总结

| 优先级 | 数量 | 摘要 |
|---|---|---|
| **P0** | 0 | 无 |
| **P1** | 4 | FakeLapTelemetry 切换半闭环 / 性能 baseline 假数据 / gate 8.7 spec-code 漂移 / partial-null dead spec |
| **P2** | 4 | gate 0 命中 trivially pass / mock data 整除 drift / D1 metrics 含混 / cursor highlight O(n) |

## 是否放行

**YES，有条件放行**

**条件**：
1. P1-1 (FakeLapTelemetry 切换路径) 必须更新 tasks.md §11.2 明确 follow-up round scope
2. P1-3 (gate 8.7 spec-code 漂移) 必须更新 spec.md Requirement #8 + gate 8.7 加 flags 字面量断言

P1-2 (性能 baseline) / P1-4 (partial-null dead spec) 推到 follow-up round。

## 与 A 线模板角度的差异化贡献

A 线已覆盖 7 类盲点 + 决策最优性 + L1 P0 修订 verify。本 B 线**补充**：

1. **跨 round 字段兼容性深挖**（P1-1 / P1-3）：W1 加 flags 后 W2 测试为何还能 pass + spec.md 与代码漂移；FakeLapTelemetry 与真实 LapTelemetry 容器字段差距下 follow-up round scope 低估
2. **测试基础设施完整性**（P1-4 / P2-2）：partial-null dead spec / mock data integer drift —— mock helper 看似覆盖 spec 边界但实际有"形似而神不至"的盲点
3. **Tier 2 plug-in 验证**（P2-4）：cursor highlight O(n) vs touch protocol O(log n) 不一致 —— Tier 2 真机拖动场景会放大此差异
4. **Vico rejection rationale 真实性**（P1-2）：design 拒绝 Vico 的依据是 100 sample 性能假设，与 25Hz/5Hz 真实采样率不符；rejection 仍合理（cursor 协议灵活性 + V2 主题贴合）但**性能不应作为主要 rationale**

## metrics.yaml 建议值（与 A 线合并去重）

```yaml
review_rounds_l2: 1   # A 线 + 本次 B 线合并保持 1
review_findings_l2:
  # A 线 P0/P1（去重后）
  - "P0: metrics divergence_reason D1↔D5 conflate"
  - "P0: mockSingleLap intervalMs 整数除法 silent off-by-one"
  - "P0: spec mockMultiLap 返回类型描述错"
  - "P0: spec all-null acceleration 占位行为零自动化覆盖"
  - "P0: spec partial-null segment 行为零自动化覆盖"
  - "P1: SpeedTimeChart n=1 lapDurationMs=1L silent canvas 外渲染"
  - "P1: GrepGateTest §8.4 maxLines/Ellipsis 滑窗 trivially-pass"
  - "P1: GrepGateTest §8.7 LapTelemetrySample 字段 grep 漏 flags（spec drift）"
  - "P1: review_rounds_l2 跳过 governance violation"
  # B 线独有
  - "P1: FakeLapTelemetry → real LapTelemetry 切换缺 5 字段构造（follow-up scope 低估）"
  - "P1: design 性能 baseline 假数据（n=100 vs 25Hz×5min=7500）"
  - "P1: cursor highlight 4 处用 indexOfFirst O(n)，未复用 binarySearch"
  # P2
  - "P2: commit 28e46fb body spec 路径名错"
  - "P2: SectorBar boundaries.last() > lapEnd 边界未透明处理"
  - "P2: AccelTimeChartContractTest partial null 缺行为级断言"
  - "P2: design.md Risks 缺 W1 后续追加字段同步策略"
  - "P2: gate 8.2/8.3/8.5/8.6 baseline 0 命中 trivially pass 风险"
  - "P2: mockSingleLap intervalMs 整除 drift（last=59994!=60000）"
  - "P2: metrics divergence_reason 表述含混"
divergence_reason: "L1 找 D5 user-A 偏移已修；L2 双线发现 spec-code 漂移（gate 8.7 / W1 加 flags）+ 性能 baseline 假数据 + follow-up scope 低估，需归档前修订 spec/tasks/metrics 4 处"
```
