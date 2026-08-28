# lap-telemetry-chart-components Specification

## Purpose
TBD - created by archiving change chart-and-map-components. Update Purpose after archive.
## Requirements
### Requirement: 4 个 Composable 组件签名严格对齐 entry sketch §3

`lap-telemetry-chart-components` capability SHALL 提供 4 个 Composable 组件，签名严格对齐 entry sketch（`docs/design/phase-1-entry-data-contracts.md`）§3 定义：

- `SpeedTimeChart(samples: List<LapTelemetrySample>, cursorAbsoluteTs: Long?, onCursorChange: (Long) -> Unit, modifier: Modifier = Modifier)`
- `AccelTimeChart(samples: List<LapTelemetrySample>, cursorAbsoluteTs: Long?, onCursorChange: (Long) -> Unit, modifier: Modifier = Modifier)`
- `SectorBar(sectorBoundaries: List<Long>, lapStartWallClock: Long, lapEndWallClock: Long, cursorAbsoluteTs: Long?, modifier: Modifier = Modifier)`
- `TrackPolylineMap(samples: List<LapTelemetrySample>, cursorAbsoluteTs: Long?, modifier: Modifier = Modifier)`

签名一旦在本 round 落地，**MUST NOT** 由 Tier 2 round 单方面调整——若需调整，user 拍板 + 同步通知 entry sketch 维护方。

#### Scenario: 正例 — 4 组件签名匹配 entry sketch
- **WHEN** Phase 1 W2 worktree 内编译 4 个新组件
- **THEN** 每个组件的参数列表严格匹配 entry sketch §3 的 `@Composable fun <Name>(...)` 签名（参数名 + 类型 + 顺序 + 默认值），且 `modifier: Modifier = Modifier` 是最后一个参数

#### Scenario: 正例 — Tier 2 调用方按签名 import 即可消费
- **WHEN** Tier 2 `lap-detail-screen-with-cursor` round 引入这 4 个组件
- **THEN** import + 直接传 `samples / cursorAbsoluteTs / onCursorChange` 参数即可调用，无需额外 wrapper / converter

#### Scenario: 反例 — 签名加额外参数（如 onSectorClick）必须 fail
- **WHEN** 任意组件签名增加 entry sketch §3 未约定的参数（如给 SectorBar 加 `onSectorClick: (Int) -> Unit`）
- **THEN** spec 检查 fail，本 round contract test 加 grep gate 锁死签名字面量（`SpeedTimeChart(samples:` / `SectorBar(sectorBoundaries:` 等），任何越界增参 → grep gate 命中数偏离 → 测试 fail

---

### Requirement: cursor 状态外置协议（无内部 MutableStateOf）

所有需要 cursor 联动的组件 MUST **不持有** cursor 内部状态——cursor 通过 `cursorAbsoluteTs: Long?` 入参从外部传入，组件触摸事件通过 `onCursorChange: (Long) -> Unit` callback 把变化报告给外部。

任何组件 **MUST NOT** 在自身函数体内出现 `remember { mutableStateOf<Long?>(...) }` 持有 cursor 时间——多组件 cursor 同步唯一靠外部状态 ownership。

#### Scenario: 正例 — SpeedTimeChart 拖动 cursor 触发 callback
- **WHEN** 用户在 SpeedTimeChart canvas 上拖动手指到某 sample 对应位置
- **THEN** `onCursorChange(sample.absoluteTsMs)` 被调用，传入最近 sample 的 `absoluteTsMs`，组件本身**不**改变内部状态

#### Scenario: 正例 — 多组件共享外部 state 同步高亮
- **WHEN** Tier 2 父屏 `val cursorTs by remember { mutableStateOf<Long?>(null) }`，把同一 state 传给 SpeedTimeChart / AccelTimeChart / SectorBar / TrackPolylineMap，并用 `onCursorChange = { cursorTs = it }` 接 SpeedTimeChart callback
- **THEN** 任一 chart 的拖动操作 → 4 组件同步高亮该时间点位置 / 速度 / G / sector / 轨迹点

#### Scenario: 反例 — 组件内部持有 cursor state 必须 fail
- **WHEN** 某组件函数体内出现 `remember { mutableStateOf<Long?>(null) }` 用于持有 cursor
- **THEN** 本 round contract test 加 grep gate 扫 4 个生产 `.kt` 文件，禁止匹配 `remember.*mutableStateOf<Long\?>` 用于 cursor 字段；命中 → grep gate fail

#### Scenario: 反例 — null cursor 不引起异常
- **WHEN** `cursorAbsoluteTs == null` 且组件被渲染
- **THEN** 组件 MUST 渲染所有 sample 数据但不画 cursor 高亮，且不抛异常 / 不 emit `onCursorChange`

---

### Requirement: SpeedTimeChart 数据契约与坐标转换

SpeedTimeChart MUST 把 sample 列表的 `elapsedMsInLap` 映射到 x 轴、`speedKmh` 映射到 y 轴；坐标转换 MUST 提取为纯函数 `computeChartCoordinates` 等以支持单测；触摸 x 坐标 MUST 通过纯函数 `findNearestSampleIndex` 转换为最近 sample 索引。

#### Scenario: 正例 — 100 sample 正常路径
- **WHEN** 传入 mockSingleLap(n=100) 生成的 samples（speedKmh 在 50-150 区间）
- **THEN** chart 渲染 100 sample 连续曲线，x 轴跨度 == lapDurationMs，y 轴跨度覆盖 speedKmh 实际 min/max + 适度 padding

#### Scenario: 正例 — 触摸事件拾取最近 sample
- **WHEN** 用户触摸 chart canvas 在 x = canvasWidth * 0.5 位置
- **THEN** `findNearestSampleIndex(samples, touchElapsedMs)` 返回中间 sample 索引（约 50），`onCursorChange(samples[50].absoluteTsMs)` 被调用

#### Scenario: 反例 — 空 sample 列表
- **WHEN** `samples.isEmpty()`
- **THEN** chart 渲染占位文字 "NO DATA"（或等价语义），不抛 IndexOutOfBoundsException，触摸事件不触发 callback

#### Scenario: 反例 — n=1 单 sample
- **WHEN** `samples.size == 1`
- **THEN** chart 渲染单点（不画线），触摸任意位置都拾取该唯一 sample 索引 0

---

### Requirement: AccelTimeChart 处理 nullable accelerationG

AccelTimeChart MUST 处理 `accelerationG: Double?` 字段的三种场景：(a) **全 null** → 显示"NO ACCEL DATA"占位文字，不画曲线；(b) **部分 null** → 跳点不连线（在 null sample 处 close path + start new path）；(c) **全有值** → 正常画连续曲线。

组件 **MUST NOT** 假设父屏会过滤 null；自身负责 graceful degrade。

#### Scenario: 正例 — 全有值正常曲线
- **WHEN** `samples.all { it.accelerationG != null }` 且 size = 100
- **THEN** chart 画连续曲线覆盖所有 sample 的 G 值

#### Scenario: 正例 — 部分 null 跳点不连线
- **WHEN** `samples` 中 sample[10..20] 的 `accelerationG == null`，其他 sample 有值
- **THEN** chart 画两段曲线（sample[0..9] 一段 + sample[21..end] 一段），中间 null 区间无线段连接

#### Scenario: 反例 — 全 null 显示占位
- **WHEN** `samples.all { it.accelerationG == null }`
- **THEN** chart 显示 "NO ACCEL DATA" 占位文字（V2 主题色 + Score 字体），不画任何曲线，触摸不触发 callback

#### Scenario: 反例 — 空 sample 列表
- **WHEN** `samples.isEmpty()`
- **THEN** 同上 "NO ACCEL DATA" 占位行为，不抛异常

---

### Requirement: SectorBar 数据语义与纯展示

SectorBar MUST 严格按 entry sketch §3 数据语义渲染：`sectorBoundaries: List<Long>` 元素为各 sector **起点** absoluteTs（含 lap 起点 == `lapStartWallClock`，不含 lap 终点 == `lapEndWallClock`），sector 总数 == `sectorBoundaries.size`，宽度按时间比例分布。SectorBar **MUST NOT** 接受 `onSectorClick` 等交互 callback——纯展示组件。

#### Scenario: 正例 — 3 sector 等分显示
- **WHEN** `sectorBoundaries = [t0, t1, t2]` 且 t0 == lapStart，t1-t0 == t2-t1 == (lapEnd-t2) == lapDurationMs/3
- **THEN** SectorBar 渲染 3 等宽 sector 段，cursor 在中间 sector 时该段填 `TrackTechColors.Purple`

#### Scenario: 正例 — cursor 高亮当前 sector
- **WHEN** `cursorAbsoluteTs == t1 + (t2-t1)/2`（落在 sector 2 中段）
- **THEN** sector 2 段被填 `TrackTechColors.Purple`，其他 sector 维持基础色，cursor 位置画 1px 竖线

#### Scenario: 反例 — 空 sectorBoundaries 单 sector
- **WHEN** `sectorBoundaries.isEmpty()`
- **THEN** SectorBar 渲染单 sector full lap（不分段），cursor 高亮整条 bar 比例位置

#### Scenario: 反例 — boundaries 不以 lapStart 起头
- **WHEN** `sectorBoundaries = [t1]` 且 t1 > lapStartWallClock
- **THEN** SectorBar log warning + 仍按比例渲染 2 sector（lapStart-t1 / t1-lapEnd），不抛异常

#### Scenario: 反例 — cursor 越界（在 lap 时间外）
- **WHEN** `cursorAbsoluteTs == lapStartWallClock - 1000`（早于 lap 起点）
- **THEN** SectorBar 不高亮任何 sector，cursor 视觉化为左边界 cap（或不显示），不抛异常

---

### Requirement: TrackPolylineMap 纯 polyline 渲染

TrackPolylineMap MUST 用 sample 中 `lat / lon` 自动计算 bounding box + scale 到 canvas（保持纵横比 + 居中），以 polyline 形式连点；cursor 处 sample 用 outlined dot 高亮（外圈 `TrackTechColors.Purple` 空心 stroke + 内圈 `TrackTechColors.Purple` 实心）。**MUST NOT** 引入第三方 map SDK / **MUST NOT** 显示坐标网格背景 / **MUST NOT** 接受 cursor 触摸输入。

#### Scenario: 正例 — 圆周轨迹渲染
- **WHEN** `samples` 是 mockSingleLap(n=100) 的圆周 lat-lon 轨迹
- **THEN** map 渲染近似圆形 polyline，居中且保持纵横比，背景为 `TrackTechColors.Background`

#### Scenario: 正例 — cursor 高亮位置点
- **WHEN** `cursorAbsoluteTs == samples[50].absoluteTsMs`
- **THEN** sample[50] 对应 lat-lon 位置画 outlined dot（外圈 `TrackTechColors.Purple` 空心 stroke + 内圈 `TrackTechColors.Purple` 实心），其他 sample 不高亮

#### Scenario: 反例 — 空 sample 列表
- **WHEN** `samples.isEmpty()`
- **THEN** map 显示 "NO TRACK DATA" 占位，不抛异常

#### Scenario: 反例 — n=1 单 sample
- **WHEN** `samples.size == 1`
- **THEN** map 仅画 1 个点（无 polyline）+ 若 cursor 命中则该点高亮

#### Scenario: 反例 — 触摸事件 0 影响（无 onCursorChange）
- **WHEN** 用户触摸 TrackPolylineMap 区域
- **THEN** 组件 **MUST NOT** 触发任何 callback / 不改变 cursor 状态（签名上 0 callback 参数）

---

### Requirement: MockTelemetry helper 边界与生产代码隔离

`MockTelemetry.kt` MUST 落在 `feature/test/src/test/java/.../ui/components/` 路径（src/test source set），生产 APK **MUST NOT** 包含此文件。helper 仅供 contract test 与开发期 manual preview 使用。`mockSingleLap(n, lapDurationMs)` MUST 生成 (a) 等时间间隔 sample / (b) 正弦波 speedKmh / (c) 圆周 lat-lon / (d) 中央差分 accelerationG / (e) 默认 3 sector 等分。

#### Scenario: 正例 — mockSingleLap 默认参数
- **WHEN** 调用 `mockSingleLap()`（n=100, lapDurationMs=60_000）
- **THEN** 返回 LapTelemetry 含 100 sample，sample[i] 的 elapsedMsInLap == i * 60_000 / 99，speedKmh 在 50-150 区间正弦波形，sectorBoundaries 含 3 元素等分

#### Scenario: 正例 — mockMultiLap 三圈不同 pace
- **WHEN** 调用 `mockMultiLap()`（n=3）
- **THEN** 返回 List<LapTelemetry>.size == 3，三圈 lapDurationMs 分别 60s / 62s / 58s，wallClock 顺序递增

#### Scenario: 反例 — 生产 APK 不含 MockTelemetry
- **WHEN** 编译 release APK 后扫 dex 类清单
- **THEN** 0 命中 `com.blazepush.feature.test.ui.components.MockTelemetryKt`（src/test source set 不进 main classpath）

#### Scenario: 反例 — 生产 .kt 文件 import MockTelemetry 必须 fail
- **WHEN** `feature/test/src/main/` 下任意 .kt 文件出现 `import com.blazepush.feature.test.ui.components.mockSingleLap` 或 `mockMultiLap`
- **THEN** 本 round contract test 加 grep gate（扫 src/main 下所有 .kt 文件），命中 → grep gate fail

---

### Requirement: V2 视觉规则贴合

4 个组件 + mock helper preview MUST 严格遵守 V2 视觉规则（`gps-app/CLAUDE.md` "UI 视觉约束" 节）：

1. DSEG7 字体（Mechanical）**MUST 仅** 用于纯数字仪表瞬时读数（如 cursor tooltip 处的 "speed = 132"，单位 km/h 经 unit 参数分离）
2. 时间字符串 / "NO DATA" 占位 / 坐标轴 tick label MUST 用 Score（Italic Bold SansSerif）
3. 所有裸 `Text(...)` 调用 MUST 加 `maxLines = 1, overflow = TextOverflow.Ellipsis`（走 `MetricNumber` 等已封装组件不计在内——封装组件内部已硬编码 maxLines/overflow）
4. **MUST NOT** 使用 `TrackTechTypography.MetricHero/Medium/Small`（已 @Deprecated）
5. **MUST NOT** 引入字号自适应库

#### Scenario: 正例 — cursor tooltip 速度数字走 Mechanical
- **WHEN** SpeedTimeChart 在 cursor 位置渲染 tooltip "speed = 132 km/h"
- **THEN** 数字 "132" 用 `MetricNumber(value = "132", kind = MetricKind.Mechanical, unit = "km/h")`，单位 "km/h" 走 unit 参数（不进 Mechanical 字体）

#### Scenario: 正例 — 坐标轴 tick label 走 Score
- **WHEN** SpeedTimeChart 渲染 x 轴 tick "1:00 / 2:00 / 3:00"
- **THEN** 时间字符串 Text 调用 `style = TrackTechTypography.ScoreSmall`（Italic Bold SansSerif），不使用 Mechanical 字体

#### Scenario: 反例 — 裸 Text 缺 maxLines 必须 fail
- **WHEN** 4 组件中任意裸 `Text(...)` 调用未带 `maxLines = 1` + `overflow = TextOverflow.Ellipsis`
- **THEN** 本 round contract test 加 grep gate（扫 4 个组件 .kt 文件），裸 `Text(` 调用必须出现 maxLines/overflow 字面量；走 `MetricNumber` 等封装组件的调用不计在内（内部已硬编码）；命中 fail

#### Scenario: 反例 — 引入 autoSize 必须 fail
- **WHEN** 任意组件出现 `BasicText.autoSize` / 第三方 AutoSizeText / `autoSize = true` 字面量
- **THEN** 本 round contract test 加 grep gate 扫 4 组件源文件，命中 → fail

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

W1 round 后续负责扩展 `core/domain/.../model/LapTelemetry.kt` 加 `LapTelemetry` 容器 + `PerformanceTelemetry` 容器；**MUST NOT** 修改 `LapTelemetrySample` 字段签名。

#### Scenario: 正例 — 字段签名严格对齐 entry sketch
- **WHEN** 本 round land 的 `LapTelemetrySample` 与 entry sketch §1 字段类型 / 名称 / 顺序对比
- **THEN** 7 个字段（`absoluteTsMs / elapsedMsInLap / lat / lon / speedKmh / bearingDeg / accelerationG`）逐一匹配，nullable 标注一致（仅 bearingDeg 与 accelerationG 是 `Double?`）

#### Scenario: 正例 — W1 round 扩展容器不动 sample 类
- **WHEN** W1 round 后续在 `LapTelemetry.kt` 文件内追加 `LapTelemetry` data class 与 `PerformanceTelemetry` data class
- **THEN** `LapTelemetrySample` 字段签名 0 改动，本 round 已有的 chart 组件继续编译通过

#### Scenario: 反例 — sample 字段签名偏离必须 fail
- **WHEN** 本 round land `LapTelemetrySample` 时字段名 / 类型 / nullable 标注与 entry sketch §1 不一致（如 `absoluteTsMs: Int` 而非 Long，或 `accelerationG: Double` 而非 Double?）
- **THEN** 本 round contract test 加 grep gate 锁字段字面量（包含 nullable `?` 标注），命中偏离 → fail

---

### Requirement: 测试策略 — 拆分纯逻辑函数 + Composable 拼装

每组件 MUST 提供至少 1 个内部纯函数（如 `computeChartCoordinates` / `findNearestSampleIndex` / `computeSectorBounds` / `computeMapBoundingBox`），把数据 → canvas 坐标 / 触摸 → sample 索引等转换从 Composable 拆出，使其可被 JUnit4 contract test 单独验证。纯函数可定义在任一组件文件内顶层（`internal fun`），同包其他组件直接复用（如 `computeChartCoordinates` 同时被 SpeedTimeChart 和 AccelTimeChart 使用）。Composable 内部仅做 layout 参数透传 + Canvas 调用。

每组件 contract test MUST 覆盖至少 4 case：(1) 正常路径 / (2) 空列表边界 / (3) null cursor / (4) 边界 sample（n=1）。AccelTimeChart 加 (5) 全 null / (6) 部分 null。SectorBar 加 (7) 空 boundaries。

#### Scenario: 正例 — 纯函数可独立单测
- **WHEN** contract test 直接调用 `computeChartCoordinates(samples, canvasSize, axis)` 不启动 Composable runtime
- **THEN** 函数返回 List<Offset> 与预期值匹配（如 mockSingleLap(n=100) 的 sample[50] x 坐标 ≈ canvasWidth * 0.5）

#### Scenario: 正例 — findNearestSampleIndex 二分查找
- **WHEN** `findNearestSampleIndex(samples, touchElapsedMs = 30_000L)` 在 mockSingleLap(n=100, lapDurationMs=60_000) 数据上调用
- **THEN** 返回索引 ≈ 49（30_000 / (60_000/99) ≈ 49.5）

#### Scenario: 反例 — Composable 内部含纯逻辑必须重构
- **WHEN** Composable 函数体内直接做"sample → canvas 坐标"逐项转换 / "touchX → sample 索引" binarySearch（未提取为纯函数）
- **THEN** 本 round contract test 加 grep gate 检查 4 个组件 .kt 文件 MUST 出现 `internal fun computeChartCoordinates` / `internal fun findNearestSampleIndex` 等纯函数声明，缺失 → fail

#### Scenario: 反例 — contract test case 不足必须 fail
- **WHEN** 任一组件 ContractTest 文件 case 数 < 4（或 AccelTimeChart < 6 / SectorBar < 7）
- **THEN** 本 round 编译期 grep gate 扫 ContractTest 文件 `@Test` 注解出现次数，下界断言 fail

