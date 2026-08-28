# L2 Adversarial Review — Opus 子 agent B 线（lap-comparison-time-align 差异化角度）

> 触发时机：2026-05-05 W3 归档 commit `4326e11` 之后
>
> Reviewer：CC 主会话 spawn 的 general-purpose 子 agent，model=opus，差异化角度（区别于 A 线）：数学公式正确性 + 6 cases 边界完整性 + LapAlignmentResult Tier2 消费 + 跨时钟域不复发 + proposal API surface
>
> Round 代码作者：mimo-v2.5-pro

## 角度 1：数学公式正确性

### [P2] cosLat0 单点取自参考圈起点适用边界范围未在 spec 显式锁单测

**位置**：LapAlignment.kt:58, 81

**现状**：cosLat0 = cos(refLap.samples[0].lat * DEG_TO_RAD) 提前算 1 次，所有比较圈共用。design D1 + spec 已在范围 < 5km / lat0=31° 锁死 < 0.5m 误差，并由 R2 / spec scenario 显式声明边界。**单测仅在 lat0=0.0 / 0.000898 等小 lat 范围测试**，未跑 lat0=31° 上海样地 / lat0=45° 高纬带。

**问题**：spec 把"赛道边界框对角线 < 5km" 写成 normative 但**没有反例 case 数学锁死**——只是 hand-wave "调用方 SHALL 在文档中标注"。Tier 2 屏 round 实施期未跑此边界 → 实际超 5km 时 silently 漂出 5m 步长精度。

**修订建议**：(a) 不阻塞当前 round；(b) 派生到 follow-up `lap-alignment-vincenty-fallback` deferred memo 应**显式包含一条 "lat0=31° 5km 直线赛道下平面投影 vs Haversine 真值偏差 ≤ 5m" 的 cap-test 方案**。

### [P2] case A speed-at-grid 容差 1.0 km/h 偏松

**位置**：LapAlignmentTest.kt:84-88

**现状**：`assertEquals(80.0, result.samplesPerLap[0][100].speedKmh, 1.0)` 等三条断言用 1.0 km/h delta。所有 mock 数据都构造常量 speedKmh，线性插值任意 alpha 后值依然精确——精度应在 1e-9 级别，1.0 容差掩盖了"接错 lap → 数值偏 6+ km/h"也能 pass 的风险。

**修订建议**：把容差收紧到 1e-6（常量值线性插值不该有任何偏差）。

## 角度 2：6 cases 边界覆盖完整性

### [P1] flags 字段在 interpolate / fallback 中被静默丢弃（同 A 线 P1-A）

**位置**：
- 模型：LapTelemetry.kt:21 `val flags: Int = 0`
- 上游来源：GpsBinaryFormat.kt:62, 75, 89 —— flags 是 17-byte sample 协议的固有 1 byte（GPS fix 状态 / 哨兵 flag）
- LapAlignment.kt:193-201（interpolate 构造）+ 134-142（fallback 构造）：构造 `LapTelemetrySample(...)` 全部**未传 flags**，编译器走 default 0

**现状**：原 sample 的 flags 在重采样后**100% 静默置 0**。只有 case F1（直接 copy 单帧 path）保留原 flags。所有其它 path（case A/B/C/E 走 interpolate；case F2 走 fallback；refIdx 圈走 interpolate）都丢失 flags。

**问题**：
- flags 是 W1 binary reader (GpsBinaryFormat) 落地的协议字段，承载 GPS fix-quality / sentinel 信息（CLAUDE.md 公共协议不可改约束的字段范围）
- design D2 字段插值表 + spec "字段插值 SHALL 区分..." requirement **完全没提 flags 的处理方式**
- Tier 2 屏 round 5 如果消费 sample.flags 显示"GPS fix 异常"图标，会发现重采样后所有 grid 点 flags == 0 → 误判"全程信号好"

**修订建议**：
- (a) 优选：design D2 + spec 字段插值表显式补一行"flags：最近邻 + fallback 路径强制 0"，单测加 1 条断言（构造 lap0 samples[k].flags=1, samples[k+1].flags=2，验证 grid 50 处 flags ∈ {1, 2}）
- (b) 派生 follow-up round `lap-alignment-flags-propagation`：当前 round 归档时在 tasks.md §10 backlog + memo
- 建议 (a)，因为 flags 语义已被上游协议固定（不是开放设计），最近邻 fallback 与 bearingDeg 同 pattern，加 1 行 spec + 1 条单测代价 < 30 分钟

### [P1] bearingDeg 跨 360° 最近邻 + elapsedMsInLap round 浮点边界 case 都没有单测（dead spec）

**位置**：LapAlignmentTest.kt

**现状**：
- 所有 mockLap 构造 `bearingDeg = 0.0` 常量 → 跨 0°/360° 最近邻 spec scenario "角度型字段最近邻 fallback"（spec line 140-142）**没有单测覆盖**
- 所有 mockLap 构造 elapsedMsInLap 是 frac 线性映射 → "elapsedMsInLap 浮点 round" spec scenario（spec line 136-138：α=0.6 时 round(1000.6) 应 == 1001L 不 == 1000L）**没有单测验证 round 而非 floor/截断**

**问题**：spec 把这两条作为 normative scenario 写出来，但 spec 行 "**禁止** 1000L 截断" 反例**没有实际单测锁死**。如果未来某个 refactor 把 `round` 改成 `toLong()` 截断或 `floor`，单测全绿但 spec 反例 silently 失效——典型"dead spec"风险（v3 高频盲点 #13）。

**修订建议**：加 case G "字段插值精度（bearing 跨 360° + elapsed round）"：构造 2 帧 lap，s0.bearingDeg=350°, s1.bearingDeg=10°, elapsedMsInLap=1000L vs 1001L，distance=10m vs 20m。grid d*=14m → α=0.4。断言 bearingDeg == 350.0（α<0.5 走 s_k）与 elapsedMsInLap == 1000L（round(1000+0.4)=1000）；再变 α=0.6 → 断言 bearingDeg == 10.0 与 elapsedMsInLap == 1001L（round(1000.6)=1001 而不是 1000 截断）。跟 P1 flags 修订合并到一个 follow-up patch，30 分钟可补完。

### [P2] case E 缺"刚出静止区"边界 + 重复区间内多个 grid 点的稳定性断言

case E 测了 gridIdx20 (距离 100m，正好命中静止区中心)。**没测**：gridIdx21 (刚出静止区 / 重复区间内多个 grid 点的稳定性)。建议合并到 P1 follow-up。

## 角度 3：LapAlignmentResult 字段完整性 + Tier2 消费

### [P2] gridIndexFor(D) 当 D > refTotalDist 时 silently clamp，调用方无法区分"clamp 命中"vs"D 在范围内"

**位置**：LapAlignment.kt:30-33；spec line 215-217

**现状**：spec scenario 已显式锁定 clamp 行为（D=5000m, refTotalDist≈3000m → 返回 600）。Tier 2 屏 round 5 调用时若 cursor 拖到比较圈尾速段（comparison lap 比 ref lap 长），会得到 grid_size-1 而无任何 "out-of-range" 信号——UI 可能误显示"游标在比较圈实际位置外"。

**修订建议**：(a) 不改 helper 行为（保持 clamp）+ spec 加一句"调用方 SHALL 自行 `if (D > refTotalDistMeters)` 标记 cursor out-of-range"。

### [P2] LapAlignmentResult 字段对 round 5 够用吗？

字段当前：(samplesPerLap, distanceStepMeters, refTotalDistMeters, gridSize, referenceLapIndex)。round 5 需要的派生（Δt(D) / Δt max-min 位置 / lap 长度差 / fallback 标记）都可由调用方 1-2 行原语派生。**字段够用**。round 5 不需要回头扩展 LapAlignment。

## 角度 4：W3 内是否有跨时钟域二次派生

### [PASS] W3 内不做基于 raw tsDeltaMs / wallClock 的二次减法

LapAlignment.kt 接收 `LapTelemetry.lapStartWallClock` (Long, 真壁钟) + `LapTelemetrySample.elapsedMsInLap` (Long, 圈内偏移) + `absoluteTsMs` (派生自 lapStart + elapsed)。重采样时：
- interpolate() 用 lapStartWallClock + elapsedMsInLap 派生新 absoluteTsMs（同源派生 ✓）
- resampleByGridFallback() 同样模式（重派生 lap1 absoluteTsMs，case F2 assert 4 反例锁死跨时钟域不污染 ✓）

**W1 L2 B 线发现的 "tsDeltaMs (System.currentTimeMillis) vs entity.timestamp (GPS 协议) 跨时钟域" 风险不会在 W3 复发** —— W3 只消费 LapTelemetry 类型（已被 W1 派生过），不接触 raw tsDeltaMs。

## 角度 5：proposal API surface 真实性

### [P2] proposal API surface 漏写 = 5.0 默认值

**位置**：proposal line 44

proposal 漏写默认值，spec line 13 + tasks §3.1 + 实施全部含 `distanceStepMeters: Double = 5.0`。低风险，已归档 retrospective 项。

## 角度 6：5 → 6 cases 同步漏网（L1 P1 修订 verify）

测试 @Test 数：13；tasks §4 letter cases：6；spec scenarios：29；proposal line 10 case 描述：6。**6 letter case × tests 13 sub-test × spec 29 scenario 三方 1:N 一致，5→6 同步修订到位。PASS**

## 角度 7：实施期遗留

### [P2] resampleByGrid 的 fallbackRefSamples 死参数（同 A 线 P1-B，B 线降级 P2）

**位置**：LapAlignment.kt:115-127

第 5 个参数 fallbackRefSamples 加 @Suppress("UNUSED_PARAMETER") 注解，方法体从未读取。`@Suppress("UNUSED_PARAMETER")` 是"作者明知有但选择保留"的负面信号。3 行 trivial diff，可作为 push 前 last-mile patch。

## 总结

| 优先级 | 数量 | 摘要 |
|---|---|---|
| **P0** | 0 | 无 |
| **P1** | 2 | flags 静默丢弃 / bearing-round dead spec |
| **P2** | 6 | cosLat0 边界单测 / case A 容差松 / case E 边界 / gridIndexFor clamp / proposal 漏默认值 / 死参数 / case A 重复构造 |

## 是否放行

**YES 放行 + P1 派生 follow-up round**

理由：
- 当前 commit a0cbfb7 已合回主区，13 单测全绿，签名/return type/grep gate 全 PASS，跨时钟域 invariant 锁死
- P1 #1（flags 静默丢弃）+ P1 #2（bearing/round 死 spec）建议合并到一个 follow-up round `lap-alignment-flags-and-bearing-tests`：估时 ≤ 0.2 day

## 与 A 线模板角度的差异化贡献

A 线：覆盖 7 类盲点 + L1 P0 修订 verify + pure function 真 pure。

B 线：补充
1. **数学公式正确性深挖**（cosLat0 边界 / case A 容差精度）
2. **6 cases 边界覆盖完整性**（P1 flags + bearing-round dead spec 关键 finding）
3. **LapAlignmentResult Tier2 plug-in 验证**（gridIndexFor clamp 调用方约束 / 字段够用判定）
4. **跨时钟域不复发 PASS**（W1 P1-1 风险在 W3 不复发的具体证据）
5. **proposal API surface 真实性**

## metrics.yaml 建议值（与 A 线合并去重）

```yaml
review_rounds_l2: 1   # A 线 + 本次 B 线合并保持 1
review_findings_l2:
  # A/B 线共识 P1
  - "P1: flags 字段在 interpolate/fallback 中被静默丢弃（W1 合回后才显现，L1 plateau 漏检；spec 字段插值表未规定 flags 处理）"
  - "P1-B: resampleByGrid 死参数 fallbackRefSamples 用 @Suppress 掩盖（A线 P1，B线降级 P2）"
  # B 线独有 P1
  - "P1: bearingDeg 跨 360° 最近邻 + elapsedMsInLap round 浮点边界 spec scenario 无单测锁死（dead-spec）"
  # P2
  - "P2: caseA gridSize 断言依赖巧合数值匹配"
  - "P2: spec 未规定 NaN/Inf 输入下 distanceStepMeters 防御"
  - "P2: cosLat0 单点适用边界 spec 已锁但单测未跑 lat0=31°/45° 反例"
  - "P2: caseA speedKmh 容差 1.0 km/h 偏松（应 1e-6）"
  - "P2: gridIndexFor 超 refTotalDist silently clamp，调用方约束 spec 应加 1 句"
  - "P2: proposal §API surface 漏 = 5.0 默认值"
divergence_reason: "L1 5 轮 plateau 在 W1 合回前判定，W1 合回后追加 flags 字段触发跨 round 时序问题；L2 双线补跑发现 2 P1（flags 字段 + bearing/round dead spec）+ 6 P2"
model_apply: "mimo-v2.5-pro"
```

## 关键加分项（B 线确认）

- **数学算法正确性**：cosLat0 单点 + 球面投影 + binary search 含 sweep-left + α=0/0 防 NaN —— 全部按 spec 落地，13 单测通过
- **跨时钟域 invariant**：W3 内不做 raw tsDeltaMs 二次减法，W1 L2 B 线警示的污染路径在 W3 不复发；case F2 反例显式锁死
- **return type LapAlignmentResult 对 Tier 2 round 5 plug-in 已够用**

## 关键扣分项（B 线发现）

- **flags 字段 spec 留白 + 单测漏 + 上游协议字段 silently 0** —— v3 高频盲点 #1（半闭环承诺）+ #13（dead spec）混合形态
- **bearing/round 反例死 spec** —— spec scenario 写"禁止...截断/线性插值"，但单测 mockLap 全 0.0 bearing 永远不触发反例 path
