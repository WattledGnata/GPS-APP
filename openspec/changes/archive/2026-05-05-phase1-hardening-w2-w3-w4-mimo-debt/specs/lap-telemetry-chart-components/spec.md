## MODIFIED Requirements

### Requirement: SpeedTimeChart 数据契约与坐标转换

SpeedTimeChart MUST 把 sample 列表的 `elapsedMsInLap` 映射到 x 轴、`speedKmh` 映射到 y 轴；坐标转换 MUST 提取为纯函数 `computeChartCoordinates` 等以支持单测；触摸 x 坐标 MUST 通过纯函数 `findNearestSampleIndex` 转换为最近 sample 索引。

**新增 n=1 守卫契约（C1 修订 + L1 R1 P0-1 caveat）**：

**当前生产代码现状**（worktree HEAD = 4326e11）：
- `SpeedTimeChart.kt:151` 已有 `if (coords.size >= 2)` 守卫 → n=1 时 chart line 渲染分支**不会**进入（**视觉无 silent canvas 外渲染**）
- `SpeedTimeChart.kt:128-148` 触摸路径 `lapDurationMs = if (size >= 2) ... else 1L`：n=1 时 lapDurationMs=1L、touchElapsedMs coerceIn(0, 1L)、findNearestSampleIndex 返回 0 → 仍触发 `onCursorChange(samples[0].absoluteTsMs)`（**真问题，cursor 联动语义错乱**）

**本 round 修订**（**L1 R2 P0-R2-1 加严**：cursor 渲染分支补全）：当 `samples.size <= 1` 时（含 isEmpty + n=1 两个 case），组件 MUST 满足三条契约：
- (a) Composable 体内 early-return placeholder 分支（与 `samples.isEmpty()` 同语义；不画 chart line + 显示"NO DATA"占位文字）；early-return MUST 在 Box block 内**最早**位置发生（在 Canvas / cursor 渲染分支之前），即 `if (samples.isEmpty() || samples.size == 1) { 占位文字; return@Box }` 形态；**不允许**仅在触摸 detector 内 return（detector return 只 cover 触摸路径，不 cover cursor line 渲染）
- (b) 触摸路径（drag + tap） MUST NOT 调 `onCursorChange` — 即 `samples.size <= 1` 时 detector callback 不 emit
- (c) **cursor 渲染路径 MUST NOT 画在 canvas 外**：n=1 时若外部 `cursorAbsoluteTs == samples[0].absoluteTsMs` 触发 `cursorIdx == 0`，`coords[0].x` 由 `computeChartCoordinates` 计算 — 当 lapDurationMs=1L + samples[0].elapsedMsInLap > 0 时 `coords[0].x = elapsedMsInLap × canvasWidth / 1L` 远超 canvas（v3 #2 silent 问题）；**修订**：(a) 路径的 early-return 已天然 cover（return@Box 直接跳过 Canvas 内的 cursor 渲染分支 line 161-167）；测试 MUST 锁该路径

(C1 修订核心：W2 review-l2-opus-a.md P1-1 描述的 silent canvas 外渲染**真问题在 cursor 渲染分支**——line 151 守卫只保护 chart line，cursor line drawLine 没保护；本 round (a) early-return 一次性 cover 三条路径)

#### Scenario: 正例 — 100 sample 正常路径
- **WHEN** 传入 mockSingleLap(n=100) 生成的 samples（speedKmh 在 50-150 区间）
- **THEN** chart 渲染 100 sample 连续曲线，x 轴跨度 == lapDurationMs，y 轴跨度覆盖 speedKmh 实际 min/max + 适度 padding

#### Scenario: 正例 — 触摸事件拾取最近 sample
- **WHEN** 用户触摸 chart canvas 在 x = canvasWidth * 0.5 位置
- **THEN** `findNearestSampleIndex(samples, touchElapsedMs)` 返回中间 sample 索引（约 50），`onCursorChange(samples[50].absoluteTsMs)` 被调用

#### Scenario: 反例 — 空 sample 列表
- **WHEN** `samples.isEmpty()`
- **THEN** chart 渲染占位文字 "NO DATA"（或等价语义），不抛 IndexOutOfBoundsException，触摸事件不触发 callback

#### Scenario: 反例 — n=1 单 sample 走占位分支不渲染 line
- **WHEN** `samples.size == 1` 且 `samples[0].elapsedMsInLap > 0`（如 5000L）
- **THEN** chart 渲染占位文字 "NO DATA"（与 isEmpty 同行为）；本 round Composable 体内加 early-return placeholder 分支，**比当前 line 151 的 `coords.size >= 2` 守卫更早 short-circuit**（避免 computeChartCoordinates 计算 + 触摸 listener 注册）；现状 line 151 守卫已避免渲染分支，本 round 加严仅为统一 isEmpty / n=1 路径

#### Scenario: 反例 — n=1 单 sample 触摸不触发 onCursorChange（L1 R1 P0-1 修订核心反例）
- **WHEN** `samples.size == 1` 时用户在 chart canvas 上 drag / tap
- **THEN** 触摸 callback **MUST NOT** 调 `onCursorChange(...)`（防止 lapDurationMs=1L 退化路径让 idx==0 误触发 cursor 联动）；具体地 SpeedTimeChartContractTest 加 case：构造 `samples = listOf(LapTelemetrySample(... elapsedMsInLap=5000L ...))` + mock `onCursorChange = { ... }`，模拟 drag/tap → 断言 callback 调用次数 = 0（计数器锁）

#### Scenario: 反例 — n=1 触摸路径仍调 onCursorChange 必须 fail（防回退 grep gate）
- **WHEN** 实施时遗漏触摸路径 `samples.size <= 1` 守卫，`detectDragGestures` / `detectTapGestures` 内 `lapDurationMs = if (samples.size >= 2) ... else 1L` 后仍执行 `onCursorChange(samples[idx].absoluteTsMs)`
- **THEN** SpeedTimeChartContractTest 反例 case 断言 callback 调用次数 = 0 必 fail；同时 grep gate 扫 `SpeedTimeChart.kt` MUST 在两处 `detectDragGestures` / `detectTapGestures` 块内含 `if (samples.size <= 1) return` 字面量（或等价 early-return 形态）；命中数 ≥ 2 → 通过；命中数 < 2 → fail

#### Scenario: 反例 — n=1 + cursorAbsoluteTs 匹配 cursor line silent canvas 外渲染必须 fail（L1 R2 P0-R2-1 修订核心反例）
- **WHEN** `samples.size == 1` 且 `samples[0].absoluteTsMs == 100_000L` + `samples[0].elapsedMsInLap == 30_000L` + 外部传入 `cursorAbsoluteTs = 100_000L`（匹配 samples[0]，触发 line 161-167 cursor 渲染分支）
- **THEN** Composable MUST 在 Box block 起始位置 early-return placeholder（在 Canvas 内任何 drawLine 调用之前）—— 防止 `cursorIdx == 0` + `coords[0].x = 30_000 × canvasWidth / 1L` 远超 canvas 触发 silent canvas 外 drawLine。SpeedTimeChartContractTest 加 case：mock DrawScope 的 `drawLine` 调用计数器，n=1 + cursorAbsoluteTs match 场景下断言 `drawLine 调用次数 == 0`（或断言 cursor line 没有渲染）

#### Scenario: 反例 — Composable early-return 位置错误必须 fail（L1 R2 P0-R2-1 防回退 grep gate）
- **WHEN** 实施时把 early-return 放到 Canvas block 内或仅在触摸 detector 内 return（漏掉 cursor 渲染路径），`if (samples.size <= 1) return` 出现在 detectDragGestures/detectTapGestures 内但 Box block 起始无 early-return
- **THEN** 反例 grep gate 扫 SpeedTimeChart.kt MUST 在 Box block 起始（`Box(modifier) {` 之后 5 行内）出现 `if (samples.isEmpty() || samples.size == 1)` 或 `if (samples.size <= 1)` 字面量，**或** Composable 函数体顶端（function body 起始 5 行内）出现等价 early-return；命中 0 → fail。这与 (b) 触摸路径守卫是**两个独立 grep gate**（不可互相替代）

---

### Requirement: AccelTimeChart 处理 nullable accelerationG

AccelTimeChart MUST 处理 `accelerationG: Double?` 字段的三种场景：(a) **全 null** → 显示"NO ACCEL DATA"占位文字，不画曲线；(b) **部分 null** → 跳点不连线（在 null sample 处 close path + start new path）；(c) **全有值** → 正常画连续曲线。

组件 **MUST NOT** 假设父屏会过滤 null；自身负责 graceful degrade。

**新增 computeAccelSegments 纯函数契约（A4/A5/C3 修订）**：组件 MUST 提取 `internal fun computeAccelSegments(samples: List<LapTelemetrySample>): List<IntRange>` 纯函数，把 sample 列表按 accelerationG nullable 拆分为连续非 null 段的 IntRange list（每个 IntRange 表示一段连续非 null sample 的索引范围 `[startIdx..endIdx]`）；空列表 / 全 null 输入返回 `emptyList()`；全非 null 输入返回 `[0..size-1]` 单元素 list。组件 Composable 内部 MUST 通过该纯函数获取 segment 列表后逐段画线。

#### Scenario: 正例 — 全有值正常曲线
- **WHEN** `samples.all { it.accelerationG != null }` 且 size = 100
- **THEN** chart 画连续曲线覆盖所有 sample 的 G 值；`computeAccelSegments(samples)` 返回 `[0..99]` 单元素 list

#### Scenario: 正例 — 部分 null 跳点不连线（IntRange 真断言）
- **WHEN** `samples` size=30，sample[10..20] 的 `accelerationG == null`，sample[0..9] 与 sample[21..29] 有值
- **THEN** chart 画两段曲线（sample[0..9] 一段 + sample[21..29] 一段），中间 null 区间无线段连接；**`computeAccelSegments(samples)` MUST 返回 `[(0..9), (21..29)]`**（IntRange list 长度 2，两段 IntRange 起止精确）

#### Scenario: 正例 — 前段全 null 中后段有值
- **WHEN** `samples` size=20，sample[0..4] null，sample[5..19] 有值
- **THEN** `computeAccelSegments(samples)` MUST 返回 `[(5..19)]`（IntRange list 长度 1）

#### Scenario: 正例 — 后段全 null 前段有值
- **WHEN** `samples` size=20，sample[0..14] 有值，sample[15..19] null
- **THEN** `computeAccelSegments(samples)` MUST 返回 `[(0..14)]`（IntRange list 长度 1）

#### Scenario: 正例 — 多段交替 null/有值
- **WHEN** `samples` size=10，sample[0..2] 有值，sample[3] null，sample[4..6] 有值，sample[7] null，sample[8..9] 有值
- **THEN** `computeAccelSegments(samples)` MUST 返回 `[(0..2), (4..6), (8..9)]`（IntRange list 长度 3）

#### Scenario: 反例 — 全 null 显示占位
- **WHEN** `samples.all { it.accelerationG == null }` 且 size = 100
- **THEN** chart 显示 "NO ACCEL DATA" 占位文字（V2 主题色 + Score 字体），不画任何曲线，触摸不触发 callback；**`computeAccelSegments(samples)` MUST 返回 `emptyList()`**

#### Scenario: 反例 — 空 sample 列表
- **WHEN** `samples.isEmpty()`
- **THEN** 同上 "NO ACCEL DATA" 占位行为，不抛异常；`computeAccelSegments(samples)` MUST 返回 `emptyList()`

#### Scenario: 反例 — Composable 内部直接做 segment 拆分必须 fail
- **WHEN** `AccelTimeChart` 函数体内出现内联 `samples.indices.fold(...)` / `samples.windowed(2).filter { ... }` 等内联做 segment 拆分（未通过 computeAccelSegments 纯函数）
- **THEN** 本 round contract test 加 grep gate 扫 AccelTimeChart.kt MUST 出现 `internal fun computeAccelSegments` 字面量 + Composable 体内 MUST 含 `computeAccelSegments(samples)` 调用；缺失 → fail

---

### Requirement: MockTelemetry helper 边界与生产代码隔离

`MockTelemetry.kt` MUST 落在 `feature/test/src/test/java/.../ui/components/` 路径（src/test source set），生产 APK **MUST NOT** 包含此文件。helper 仅供 contract test 与开发期 manual preview 使用。`mockSingleLap(n, lapDurationMs)` MUST 生成 (a) 等时间间隔 sample / (b) 正弦波 speedKmh / (c) 圆周 lat-lon / (d) 中央差分 accelerationG / (e) 默认 3 sector 等分。

**修订等距间隔公式（A2 修订）**：sample[i].elapsedMsInLap MUST 用公式 `(i.toLong() * lapDurationMs) / (n - 1).coerceAtLeast(1)` 计算（**MUST NOT** 使用 `i * (lapDurationMs / n)` 或 `i * (lapDurationMs / (n-1))` 整除写法，避免 off-by-one）。该公式保证 `sample[n-1].elapsedMsInLap == lapDurationMs`（严格等于而非近似 60_000 → 59_994）。

**修订返回类型描述（A3 修订）**：`mockMultiLap(n)` 实际返回 `List<FakeLapTelemetry>`（不是 `List<LapTelemetry>`），spec scenario MUST 描述实际类型。FakeLapTelemetry 是测试 only 的轻量数据类（属性子集对齐 LapTelemetry 但不依赖 W1 类型契约 land 完成）；W1 round 合回后由 follow-up round `wire-mock-telemetry-to-w1-real-classes` 切换到正式类型。

#### Scenario: 正例 — mockSingleLap 默认参数（A2 修订）
- **WHEN** 调用 `mockSingleLap()`（n=100, lapDurationMs=60_000）
- **THEN** 返回 LapTelemetry 含 100 sample，sample[i].elapsedMsInLap == `(i.toLong() * 60_000) / 99`，**特别地 sample[99].elapsedMsInLap == 60_000L**（严格等于；之前的 `60_000 / 99 = 606` 整除写法会让 sample[99].elapsedMsInLap == 99 * 606 == 59_994L 偏离 lapDurationMs 6ms），speedKmh 在 50-150 区间正弦波形，sectorBoundaries 含 3 元素等分

#### Scenario: 正例 — mockMultiLap 三圈不同 pace（A3 修订）
- **WHEN** 调用 `mockMultiLap()`（n=3）
- **THEN** 返回 **List<FakeLapTelemetry>**（不是 List<LapTelemetry>；FakeLapTelemetry 是 test-only 类型），size == 3，三圈 lapDurationMs 分别 60s / 62s / 58s，wallClock 顺序递增

#### Scenario: 反例 — 生产 APK 不含 MockTelemetry
- **WHEN** 编译 release APK 后扫 dex 类清单
- **THEN** 0 命中 `com.blazepush.feature.test.ui.components.MockTelemetryKt`（src/test source set 不进 main classpath）

#### Scenario: 反例 — 生产 .kt 文件 import MockTelemetry 必须 fail
- **WHEN** `feature/test/src/main/` 下任意 .kt 文件出现 `import com.blazepush.feature.test.ui.components.mockSingleLap` 或 `mockMultiLap`
- **THEN** 本 round contract test 加 grep gate（扫 src/main 下所有 .kt 文件），命中 → grep gate fail

#### Scenario: 反例 — mockSingleLap 用整除公式必须 fail（A2 防回退）
- **WHEN** 实施时把 elapsedMs 公式写成 `i * (lapDurationMs / n).toLong()` 或 `i * (lapDurationMs / (n-1)).toLong()` 等整除路径
- **THEN** 测试断言 `mockSingleLap(n=100, lapDurationMs=60_000).samples.last().elapsedMsInLap == 60_000L` MUST fail（整除路径只能给出 59_994L 或 59_400L）；同时 grep gate 扫 MockTelemetry.kt MUST 出现 `(i.toLong() * lapDurationMs) / (n - 1).coerceAtLeast(1)` 字面量

---

### Requirement: V2 视觉规则贴合

4 个组件 + mock helper preview MUST 严格遵守 V2 视觉规则（`gps-app/CLAUDE.md` "UI 视觉约束" 节）：

1. DSEG7 字体（Mechanical）**MUST 仅** 用于纯数字仪表瞬时读数（如 cursor tooltip 处的 "speed = 132"，单位 km/h 经 unit 参数分离）
2. 时间字符串 / "NO DATA" 占位 / 坐标轴 tick label MUST 用 Score（Italic Bold SansSerif）
3. 所有裸 `Text(...)` 调用 MUST 加 `maxLines = 1, overflow = TextOverflow.Ellipsis`（走 `MetricNumber` 等已封装组件不计在内——封装组件内部已硬编码 maxLines/overflow）
4. **MUST NOT** 使用 `TrackTechTypography.MetricHero/Medium/Small`（已 @Deprecated）
5. **MUST NOT** 引入字号自适应库

**修订 grep gate §8.4 实施算法（C2 修订）**：本 round 用 §8.4 grep gate 锁"裸 Text 调用必须含 maxLines/Ellipsis"时 MUST 用 **per-Text 块栈式匹配**（单 `Text\(` 调用起始 → paren balance 算法找闭合 `)` → 验证块内含 maxLines + overflow 字面量）。**MUST NOT** 用单一 N 字符 contextWindow 滑窗匹配（W2 review-l2-opus-a.md P1-2 揭示：300 字符 contextWindow 跨多 Text 块 → 任意前序 Text 块含 maxLines/overflow 后续 Text 块 trivially-pass）。

**算法 caveat**：per-Text 块栈式匹配前提是"组件源文件不嵌套 Text 调用"（如不出现 `Text(buildAnnotatedString { Text(...)})` ）；本 round apply 期 grep `Text\(` 验证 4 组件 .kt 文件无嵌套 Text。

#### Scenario: 正例 — cursor tooltip 速度数字走 Mechanical
- **WHEN** SpeedTimeChart 在 cursor 位置渲染 tooltip "speed = 132 km/h"
- **THEN** 数字 "132" 用 `MetricNumber(value = "132", kind = MetricKind.Mechanical, unit = "km/h")`，单位 "km/h" 走 unit 参数（不进 Mechanical 字体）

#### Scenario: 正例 — 坐标轴 tick label 走 Score
- **WHEN** SpeedTimeChart 渲染 x 轴 tick "1:00 / 2:00 / 3:00"
- **THEN** 时间字符串 Text 调用 `style = TrackTechTypography.ScoreSmall`（Italic Bold SansSerif），不使用 Mechanical 字体

#### Scenario: 反例 — 裸 Text 缺 maxLines 必须 fail（per-Text 块栈式匹配，C2 修订）
- **WHEN** 4 组件中任意裸 `Text(...)` 调用未带 `maxLines = 1` + `overflow = TextOverflow.Ellipsis`
- **THEN** 本 round GrepGateTest §8.4 用 per-Text 块栈式匹配（找 `Text\(` 起始 → paren balance 找闭合 `)` → 验证块内含 maxLines + overflow 字面量），命中"块内缺字面量" → fail；该算法 MUST NOT 用单一 contextWindow 滑窗（防止跨多 Text 块 trivially-pass）；走 `MetricNumber` 等封装组件的调用不计在内（内部已硬编码）

#### Scenario: 反例 — 引入 autoSize 必须 fail
- **WHEN** 任意组件出现 `BasicText.autoSize` / 第三方 AutoSizeText / `autoSize = true` 字面量
- **THEN** 本 round contract test 加 grep gate 扫 4 组件源文件，命中 → fail

#### Scenario: 反例 — §8.4 用 contextWindow 滑窗实施必须 fail（C2 防回退）
- **WHEN** 实施时 §8.4 grep gate 用 `text.windowed(300).contains(maxLines)` 等滑窗形式（非 per-Text 块栈式）
- **THEN** GrepGateTest 自身的 meta-gate 检测 §8.4 实现中 MUST 含 `Text\\(` 起始定位 + `paren balance` 算法（grep 关键字 `parenDepth` / `balanceCount`）；命中 contextWindow / windowed 字面量 → meta-gate fail

---

### Requirement: 类型契约依赖 — `LapTelemetrySample` data class 由本 round 提前 land

本 round（W2）MUST 在 worktree 启动时**先**在 `core/domain/.../model/LapTelemetry.kt` 落地 entry sketch §1 已定签的 `LapTelemetrySample` data class（仅 sample 类，不含 `LapTelemetry` / `PerformanceTelemetry` 容器类——容器类是 W1 scope）。**MUST** 严格匹配 entry sketch §1 字段签名：

```kotlin
data class LapTelemetrySample(
    val absoluteTsMs: Long,
    val elapsedMsInLap: Long,
    val lat: Double,
    val lon: Double,
    val speedKmh: Double,
    val bearingDeg: Double?,
    val accelerationG: Double?,
)
```

**修订字段清单契约（B1 修订）**：W1 round 后续在 `LapTelemetry.kt` 文件内合回时已追加 `flags: Int = 0` 字段（commit chain `f6aed72` → `3c2f2d9`），W2 spec 字段清单 MUST 描述为「**7 核心字段（上述 7 个）+ 允许 W1 追加非必填字段（含 `flags: Int = 0`）**」。W2 round 自身只锁 7 核心字段；W1 追加的 `flags` 字段在 W2 grep gate 中作为「允许但不锁」处理（`val flags: Int` 字面量 grep 命中数 = 1，与 W1 commit 一致；不命中 → W1 字段被错误删除）。

**MUST NOT** 修改 `LapTelemetrySample` 7 核心字段签名（包括类型 / nullable / 顺序）；W1 与本 round 之外的 round 追加字段时 MUST 走 OpenSpec 流程并触发跨 round drift mini-review（CLAUDE.md 高频盲点 #16）。

#### Scenario: 正例 — 字段签名严格对齐 entry sketch
- **WHEN** 本 round land 的 `LapTelemetrySample` 与 entry sketch §1 字段类型 / 名称 / 顺序对比
- **THEN** 7 个字段（`absoluteTsMs / elapsedMsInLap / lat / lon / speedKmh / bearingDeg / accelerationG`）逐一匹配，nullable 标注一致（仅 bearingDeg 与 accelerationG 是 `Double?`）

#### Scenario: 正例 — W1 round 扩展容器不动 sample 类核心字段
- **WHEN** W1 round 后续在 `LapTelemetry.kt` 文件内追加 `LapTelemetry` data class 与 `PerformanceTelemetry` data class，并给 `LapTelemetrySample` 追加 `flags: Int = 0` 非必填字段
- **THEN** `LapTelemetrySample` 7 核心字段签名 0 改动，本 round 已有的 chart 组件继续编译通过；新增 `flags` 字段默认值不破坏 mock 数据构造（mockSingleLap 不显式传 flags）

#### Scenario: 反例 — sample 字段签名偏离必须 fail
- **WHEN** 本 round land `LapTelemetrySample` 时字段名 / 类型 / nullable 标注与 entry sketch §1 不一致（如 `absoluteTsMs: Int` 而非 Long，或 `accelerationG: Double` 而非 Double?）
- **THEN** 本 round contract test 加 grep gate 锁字段字面量（包含 nullable `?` 标注），命中偏离 → fail

#### Scenario: 反例 — W1 追加 flags 字段被错误删除必须 fail（B1 防回退）
- **WHEN** 任意 follow-up round 把 `LapTelemetrySample` 的 `val flags: Int = 0` 字段删除（误以为是 mimo 添加的死代码）
- **THEN** GrepGateTest §8.7（本 round 修订）扫 `core/domain/.../model/LapTelemetry.kt` MUST 命中 `val flags: Int` 字面量恰好 1 次（W1 commit 锚点）；命中 0 次 → fail；命中 ≥ 2 次（重复声明 / 非预期扩展）→ fail

#### Scenario: 反例 — 跨 round 字段扩展未走 #16 流程必须触发 review
- **WHEN** 任意 round 给 `LapTelemetrySample` 追加新字段（除 W1 已落地的 flags 外）但未在该 round design 决策段列"消费此字段的已合回 round 列表" + 未触发 follow-up drift mini-review
- **THEN** L2 review 期 MUST 抓到 governance violation（参 CLAUDE.md 高频盲点 #16）；本 round 自身不直接 fail，但下次 phase 1 looking back 时 retrospective 应 flag
