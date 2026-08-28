## Context

**起源**：Phase 1 entry sketch（`docs/design/phase-1-entry-data-contracts.md`）把单圈数据图表分到 4 worktree 并行——本 round 是 W2 UI 组件库轨道。entry sketch §3 已给出 4 个 Composable 签名（`SpeedTimeChart` / `AccelTimeChart` / `SectorBar` / `TrackPolylineMap`）+ `MockTelemetry` helper 命名建议。本设计文档负责把签名变成可实施的设计——决定**画图实现路径**、**触摸事件协议**、**视觉 vs V2 主题贴合**、**测试策略**等开放问题。

**当前 baseline 的 chart 实现参考**：
- V1 时代 `feature/test/.../ui/components/SpeedChart.kt` / `GForceChart.kt` 用 Jetpack Compose `Canvas` API + 手绘 path，无第三方 chart 库依赖；G round 加过 `wrapInCard: Boolean = true` 参数让 V2 详情页按需嵌套
- 所有 chart 实现都是**自绘**——项目历史拒绝引入第三方 chart 库（如 MPAndroidChart / Vico）以控包体 + 控 Compose 兼容性

**约束**：
- `LapTelemetrySample` 类型签名稳定（entry sketch §1 定签 + W1 实施期不再改），但**类型本身在 W1 round 实施完成前**，本 round 代码引用 `core/domain/.../model/LapTelemetry.kt` 可能编译失败 → 解决方案见 §Decisions D1
- V2 视觉规则（CLAUDE.md "UI 视觉约束" 节）：DSEG7 仅纯数字仪表瞬时读数 / metric/row/label Text MUST `maxLines = 1 + Ellipsis`
- 与 W1/W3/W4 文件级 0 交叉，全部新建文件
- 单测策略：纯逻辑（数据 → canvas 坐标 / cursor → 索引）走 JUnit4；Compose UI 行为（preview / 触摸事件）尽量用 `@Preview` 人工验收 + 极简 instrumented 测试，不强求 Compose UI test framework（Robolectric 配置成本高 + 与项目惯例不符）

## Goals / Non-Goals

**Goals**：
- 4 个 Composable 组件 + 1 个 mock helper + 4 个 contract test 独立可编译、可 `@Preview`
- 组件签名严格对齐 entry sketch §3，cursor 状态外置协议（无内部 `MutableStateOf`）
- 视觉贴合 V2（cut-corner 边框 / TrackTechColors 调色板 / Mechanical 字体仅用于纯数字仪表瞬时读数）
- mock helper 在测试 source set 内，生产代码 0 引用，且其数据满足 `LapTelemetrySample` 字段约束（速度单调或正弦波 / lat-lon 圆周可视 / sector 边界等距分段）
- contract test 覆盖：cursor callback 触发 / 空 sample 列表 / null cursor / nullable `accelerationG` 处理 / 边界 sample（n=1）

**Non-Goals**：
- ❌ 多组件组屏（属 Tier 2 `lap-detail-screen-with-cursor` round）
- ❌ 真实 repository 数据接入（属 Tier 2 round；本 round 仅消费 mock）
- ❌ `accelerationG` 派生算法（属 W3 `lap-comparison-time-align` round 的 pure function 范畴；本 round 假设字段已由上游填好或为 null）
- ❌ TrackPolylineMap 第三方 map SDK 集成（user 拍板 = A 纯 polyline）
- ❌ SectorBar 点击交互（user 拍板 = B 纯展示，无 `onSectorClick` callback）
- ❌ chart 字号自适应（V2 规则禁止 autoSize 库；坐标轴标签靠数据预处理缩短）
- ❌ 横屏适配（K round `enforce-portrait-orientation` 已强制竖屏）
- ❌ 单测 Compose UI 触摸事件模拟（成本高 + 项目惯例不用；改用纯 logic 函数 + `@Preview` 人工 + cursor 索引转换的纯函数单测）

## Decisions

### D1：W1 类型契约依赖与本 round 编译可独立

**问题**：`LapTelemetrySample` 由 W1 round 在 `core/domain/.../model/LapTelemetry.kt` 落地。本 round 编译时若 W1 未合回，import 会失败。如何让本 round 在 W1 之前可独立编译？

**Alternatives**：
- **A. 等 W1 合回再启动本 round** —— 串行化掉了 entry sketch 的并行设计意图，违反"W2 不依赖 W1 实施完成"约定
- **B. 在 `core/domain/.../model/LapTelemetry.kt` 提前 land sample 类** —— entry sketch §1 明确授权 W1 "新建" 该文件（"LapTelemetry（W1 新建于 `core/domain/.../model/`）"），W2 越界违反看板 §5 W1 独占路径约定；W1 未来 rebase 时该文件已存在但仅含 sample 类 → W1 的 spec task "新建该文件" 会误命中
- **C. 本 round chart 组件内定义 `internal data class FakeLapTelemetrySample`（W1 合回后通过一次 sed 替换 import）** —— W2 不触碰 `core/domain/...` 独占路径；fake → real 切换 = 5 处 import 替换（4 个生产 chart 文件 + MockTelemetry test helper），rebase 冲突面积最小；但有 L1 review 指出的"chart 组件内 public API 类型定义被 fake 类占位"风险——Tier 2 round 接真实类型时需同步 import 替换
- **D（选定）. 在 `core/domain/.../model/LapTelemetry.kt` 落地 `LapTelemetrySample` data class（签名严格对齐 entry sketch §1），commit message 标注"W2 提前 land sample 类骨架；W1 后续追加 LapTelemetry / PerformanceTelemetry 容器类与 repository 方法"** —— 入口处"谁先 land"不重要（签名为契约，不变），且 W1 启动时已知 sample 类存在 → 直接消费 + 扩展容器；同时看板 §5 W1 行的"独占路径"描述需更新（把"新建 LapTelemetry.kt"改为"扩展 LapTelemetry.kt 追加容器类与 repository 方法"）+ §6 共享文件登记追加 W2 条目

**选定 D 的理由**：(1) entry sketch §7 "类型契约稳定性" 约束的是签名不变，不是"谁先 land"——sample 类签名在 entry sketch 已定稿，W2 提前落地不改变语义；(2) W1 round 启动时按 §7 约束直接消费已有 sample 类 + 扩展容器与方法，无需重新定义 → 无重复定义冲突；(3) chart 组件 import 真实 `LapTelemetrySample` 类型（非 fake），Tier 2 round 接线 0 改动。

**操作约束**：
- W2 worktree 内 land `core/domain/.../model/LapTelemetry.kt`，**仅含 `LapTelemetrySample` data class**（不含 `LapTelemetry` / `PerformanceTelemetry` 容器类——那是 W1 scope）
- 同步更新看板 §5 W1 行：把"core/domain/.../model/LapTelemetry.kt 新建"改为"core/domain/.../model/LapTelemetry.kt 追加 LapTelemetry / PerformanceTelemetry 容器类"；W1 的 specs/tasks 若有"新建该文件"描述需同步修正为"在已有文件上追加容器类"
- 看板 §6 共享文件登记**追加** W2 条目（`core/domain/.../model/LapTelemetry.kt`，W2 落地 sample 类、W1 后续追加容器类；函数级不重叠）
- W2 land 时 commit message 明确写 "land LapTelemetrySample data class skeleton (W1 will extend with container + repository)"，避免 W1 session 误以为本 round 越界

**拒绝 A**：违反并行设计；拒绝 B：越界 W1 独占路径 + W1 rebase 误命中；拒绝 C：chart 组件内 fake 类占位导致 Tier 2 接线需额外 import 替换 + L1 review 指出的"3-class 架构混乱"风险。

### D2：Chart 自绘实现路径——`Canvas` 还是第三方库

**问题**：SpeedTimeChart / AccelTimeChart / TrackPolylineMap 用什么画图？

**Alternatives**：
- **A（选定）. Compose `Canvas` API + 手绘 path** —— 与 V1 `SpeedChart.kt` 一致，全 Compose 内，cursor 拖动 / tooltip / 多组件同步等定制化交互手写更直接
- **B. 使用项目已有的 Vico chart 库** —— `feature/test/build.gradle.kts:75-76` 已引入 `com.patrykandpatrick.vico:compose:2.0.0-alpha.28` + `vico:compose-m3`（历史 round 加入但生产代码 0 引用）。**实际拒绝理由**：(1) Vico 的触摸事件协议不支持自定义 `detectDragGestures` 逐帧 emit callback（cursor 外置状态协议 D3 需要精细控制）；(2) V2 cut-corner panel 容器内 Vico chart 的 clip 行为不可控；(3) cursor 高亮需逐帧 path 重绘，Vico 原生不支持。**不是因为"新外部依赖"**——依赖已存在
- **C. 复用 V1 `SpeedChart.kt` 内的 path 绘制 helpers** —— V1 helpers 消费 `List<GpsDataPoint>`（`core/domain/.../model/TestModels.kt`），本 round 消费 `List<LapTelemetrySample>`（不同 data class），重构数据接口成本高于重写；V1 helpers 未抽为独立函数（混在 Composable 内），不可直接复用
- **D. 引入 MPAndroidChart** —— XML View 系，需 AndroidView 包装 + 主题不可控，项目惯例拒绝

**选定 A 的理由**：(1) 与 V1 `SpeedChart.kt` 实现风格一致；(2) cursor 拖动 + tooltip + 多组件同步触摸事件等定制化交互在 Compose Canvas `pointerInput` + `detectDragGestures` 中直接写，Vico 无法满足；(3) V2 视觉规则严苛（cut-corner / TrackTechColors）→ 自绘可控；(4) 性能足够（单圈 ≤ 100 sample，绘制成本 <16ms）

### D3：Cursor 触摸协议（外置 vs 内置 state）

**问题**：用户拖动 SpeedTimeChart 上的 cursor → 4 个组件都要同步高亮该 absoluteTs。状态如何流转？

**Alternatives**：
- **A. 组件内置 `MutableStateOf<Long?>(null)`** —— 单组件可用，多组件无法同步，违反 entry sketch §3 签名约定
- **B（选定）. 状态外置：`cursorAbsoluteTs: Long?` 入参 + `onCursorChange: (Long) -> Unit` 出参** —— 状态由父屏 `remember { mutableStateOf<Long?>(null) }` 持有，所有组件从同一 state 读 + 通过 callback 写

**Rationale B**：
- entry sketch §3 已约定签名
- 多组件同步唯一靠外部状态——内置 state 会让组件间各自有自己的 cursor，永远不同步
- Tier 2 父屏拿到 state ownership → 可以方便加额外逻辑（如 cursor 范围 clamp / 自动播放等）
- 单测可直接对 callback 做断言，不需要 Compose UI test

**触摸事件实现**：
- 用 `Modifier.pointerInput(samples)` + `detectDragGestures` / `detectTapGestures` 拾取触摸点
- 触摸 x 坐标 → 通过 `binarySearch(samples) { it.elapsedMsInLap.compareTo(touchElapsedMs) }` 找最近 sample → emit `sample.absoluteTsMs` 经 `onCursorChange`
- TrackPolylineMap **不接受触摸**（cursor 由其他 chart 驱动；map 仅显示）— 签名上无 `onCursorChange` 参数（entry sketch §3 已约定）
- SectorBar 同样**不接受触摸**（user 拍板 B 纯展示）— 无 callback

### D4：`accelerationG` nullable 处理（user 拍板 = A）

**问题**：entry sketch §1 标 `accelerationG: Double?`（W1 不强制填，W3 round 可派生）。AccelTimeChart 何时显示 / 何时占位？

**Alternatives**：
- **A（选定）. AccelTimeChart MUST 处理 null**：(i) 全 null → 显示"NO ACCEL DATA"占位文字（V2 主题色 + Score 字体）+ 不画曲线；(ii) 部分 null → 跳点不连线（segment 跳过 null sample）
- **B. mock 数据保证非空 + 父屏 gate**：违反"组件 API 自给自足"原则；Tier 2 父屏责任过重

**Rationale A**：
- 组件 API 应在自身层面 graceful degrade，不依赖父屏过滤
- 全 null 不显示空白比"图表内容空+轴正常"更明确（用户立刻知道"该数据缺失"）
- 部分 null 跳点是 chart 行业常见处理；不连线说明数据缺失段，比插值更诚实

**实现细节**：
- 入口判断：`if (samples.isEmpty() || samples.all { it.accelerationG == null }) → 占位 UI`
- 部分 null：foldRight 时把 path 在 null sample 处 close + start new path

### D5：TrackPolylineMap 底图策略（user 拍板 = A 纯 polyline）

**问题**：地图组件用什么底图？

**Alternatives**：
- **A（选定）. 纯 polyline**：黑/V2 主题色背景 + polyline 线 + cursor 处高亮点；自动 fit lat-lon bounding box 到 canvas
- **B. 加坐标网格**（lat/lon 等距网格）—— 视觉更复杂，不强制需要
- **C. 集成第三方 map SDK**（Google Maps / Mapbox / 高德）—— scope 爆炸 + V2 主题冲突 + 引入第三方 SDK 不符项目惯例

**Rationale A**：
- V2 mechanical/track-tech 主题与外部地图 SDK（白底地图 / 蓝色道路）冲突
- 用户场景是"看圈速轨迹形状 + cursor 同步位置"，不需要真实地理坐标参考——polyline 形状 + cursor 已足够
- 实现成本最低，无新依赖

**实现细节**：
- 计算 sample 中 lat/lon 的 `(minLat, maxLat, minLon, maxLon)` bounding box
- 用 `min(canvas.size.width / lonRange, canvas.size.height / latRange)` 算 scale，保持纵横比 + 居中
- polyline 绘制：`Path` + `lineTo` 顺序连点；背景色用 `TrackTechColors.Background`
- cursor 高亮：找到 cursor 对应的 sample 索引 → 在该位置画 V2 风格 outlined dot（外圈 `TrackTechColors.Purple` 空心 stroke + 内圈 `TrackTechColors.Purple` 实心）
- 边界处理：sample n=0 → 显示"NO TRACK DATA"占位；n=1 → 仅一个点

### D6：SectorBar 数据语义对齐 entry sketch §3

**问题**：SectorBar 接受 `sectorBoundaries: List<Long>` + `lapStartWallClock` + `lapEndWallClock`，元素含义需明确。

**Decision**：
- `sectorBoundaries` 含起点 absoluteTs（== `lapStartWallClock`）+ 各 sector 起点 absoluteTs（**不含**终点 == `lapEndWallClock`）
- 总 sector 数 == `sectorBoundaries.size`（如 [t0, t1, t2] → 3 sector：t0-t1 / t1-t2 / t2-lapEnd）
- 每 sector 宽度按时间比例 = `(boundary[i+1] - boundary[i]) / lapDurationMs`（最后 sector = `(lapEnd - boundary.last) / lapDurationMs`）
- cursor 位置：`(cursorAbsoluteTs - lapStartWallClock) / lapDurationMs` 比例 → x 坐标
- 高亮当前 sector：cursor 落在哪段 → 该段填 `TrackTechColors.Purple`

**反例处理**：
- `sectorBoundaries.isEmpty()` → 单 sector（== full lap），不分段显示
- `sectorBoundaries.first() != lapStartWallClock` → log warning + 仍按比例画（不强失败）
- `cursorAbsoluteTs` 越界（< lapStart 或 > lapEnd）→ 不高亮，cursor 视觉化为 cap 在边界

### D7：MockTelemetry helper 数据形态

**问题**：mock 数据要"看起来像"真实圈速 telemetry。

**Decision**：
- `mockSingleLap(n=100, lapDurationMs=60_000)`：
  - sample 间隔 = `lapDurationMs / (n - 1)`
  - `speedKmh`：正弦波 `100 + 50 * sin(2π * i / n)`（峰 150 / 谷 50 / 平均 100，模拟弯道加减速）
  - `lat/lon`：圆周 `centerLat + radius * sin(2π * i / n)` / `centerLon + radius * cos(2π * i / n)`（中心 31.0/121.0 半径 0.005°）
  - `accelerationG`：从 speedKmh 中央差分派生（边界点 null）
  - `bearingDeg`：相邻 sample 由 lat/lon 算
  - `lapStartWallClock = 1700000000000L`（固定基准）
- `mockMultiLap(n=3)`：复用 mockSingleLap 但每圈 lapDurationMs 不同（60s / 62s / 58s）+ wallClock 顺序递增
- `sectorBoundaries`：`mockSingleLap` 默认 3 sector（lapDurationMs / 3 等分）

**生产引用边界**：
- `MockTelemetry.kt` 落 `feature/test/src/test/java/.../ui/components/`（src/test 路径）→ Gradle 编译时不进 main classpath → 生产 APK 0 引用
- contract test 直接 import MockTelemetry helper
- **`@Preview` 不放在生产文件内**：src/main ↔ src/test 是 Gradle source set 铁律边界，src/main 不能 import src/test → 生产 .kt 文件内无法调 MockTelemetry helper。本 round **不在生产 .kt 文件内放 `@Preview`**（项目 src/main 当前 `@Preview` 命中数 = 0，本 round 不是引入 @Preview 的合适时机）；视觉验收走 contract test 纯函数覆盖 + Tier 2 真机首次联动签收。若后续项目决定全面引入 `@Preview`，可放在 `src/debug/` source set（debug variant 编译可见），但那是独立 round scope
- `mockMultiLap(n=3)` 的 wallClock 序列化约束：圈 i 的 `lapEnd` MUST 严格 < 圈 i+1 的 `lapStart`（间隔 = 1000ms），避免 mock 数据自身 timestamp 重叠

### D8：V2 视觉规则贴合

**问题**：chart 内的坐标轴标签 / 图例 / cursor 提示文字怎么遵守 V2 §1/§2/§3？

**Decision**：
- **DSEG7 字体使用范围**（CLAUDE.md §1）：
  - cursor 处 tooltip 显示"speed = 132 km/h"瞬时读数 → MAY 用 `MetricKind.Mechanical`（纯数字 132，单位 "km/h" 走 unit 参数，分离）
  - 坐标轴 tick label "1:00 / 2:00 / 3:00"（时间字符串）→ **MUST** 用 Score（`TrackTechTypography.ScoreSmall` / Italic Bold）
  - "NO ACCEL DATA" 占位 → Score
- **maxLines + Ellipsis**（CLAUDE.md §2）：所有 Text 调用 MUST 加 `maxLines = 1, overflow = TextOverflow.Ellipsis`
- **TrackTechColors**：调色板严格用 `feature/test/.../ui/tracktech/TrackTechColors.kt`——背景 `TrackTechColors.Background` / 主线 `TrackTechSemantic.TelemetryLine`（== `Cyan`）/ cursor `TrackTechColors.Purple` / 占位文字 `TrackTechColors.TextMuted`
- **不引入 autoSize 库**（CLAUDE.md §3）：标签长度靠"数据层缩短"——mock 数据的时间标签直接生成 "1:00" 而非 "00:01:00.000"

### D9：测试策略

**问题**：Compose 组件如何测？

**Alternatives**：
- **A. Compose UI test framework**（`createComposeRule()` + `performTouchInput { swipeRight() }` + `assertIsSelected()`）—— `gradle/libs.versions.toml:47` 已定义 `androidx-ui-test-junit4`，`feature/test/build.gradle.kts:91` 已 `androidTestImplementation`（库已在依赖中，0 额外引入成本）。**能覆盖触摸协议**：`performTouchInput { swipeRight() }` → assert `onCursorChange` 被调用 + 传入值正确 → spec Requirement #2 的反例 scenario "拖动 → onCursorChange" 有自动化路径。**实际额外成本**：需写 `@RunWith(AndroidJUnit4::class)` + `createComposeRule()` 的 instrumented test 文件（非 unit test），项目当前 0 先例
- **B（选定）. 拆分纯逻辑函数 + Composable 拼装**（不引入 Compose UI test）：
  - **纯逻辑提取**：`computeChartCoordinates(samples, canvasSize, axis)` / `findNearestSampleIndex(samples, touchX)` / `computeSectorBounds(boundaries, lapStart, lapEnd, totalWidth)` 等纯函数 → JUnit4 contract test
  - **Composable 拼装**：组件内部把纯逻辑函数结果 fed 给 Canvas/Layout → contract test 覆盖核心 logic 行为
  - **触摸事件 callback 测试**：把 cursor index → absoluteTs 转换提取为纯函数 `mapCursorIndexToAbsoluteTs(samples, index)` 单测；`pointerInput` 内部仅调用纯函数 + emit callback
  - **触摸协议本身（`detectDragGestures → onCursorChange`）自动化覆盖 = 0** —— 仅靠 grep gate 锁字面量 + Tier 2 真机首次联动签收

**选定 B 的理由**：(1) 本 round 是项目**首次**引入 Compose 组件库（src/main 当前 `@Preview` 命中数 = 0，Compose UI test 命中数 = 0），同时引入 Compose UI test framework + 4 个 chart 组件 + cursor 触摸协议的范围太大，risk 叠加不可控；(2) cursor 触摸协议的核心逻辑（触摸 x → sample 索引 → absoluteTs）已由 `findNearestSampleIndex` + `mapCursorIndexToAbsoluteTs` 两个纯函数覆盖，`detectDragGestures` 内部仅是 Compose gesture detector 调这两个纯函数 + emit callback 的 glue code；(3) follow-up round `add-compose-ui-test-for-cursor-drag`（tasks §11.4）专门补齐触摸协议自动化覆盖，不阻塞本 round 交付

**Contract test 覆盖**（每组件至少 4 case）：
1. **正常路径**：100 sample mock → coordinates 计算正确 / cursor index 找对
2. **空 sample 列表**：`samples.isEmpty()` → 占位文字逻辑不抛异常
3. **null cursor**：`cursorAbsoluteTs == null` → 不高亮 / 不抛异常
4. **边界 sample（n=1）**：单 sample → 不画线 / cursor 拾取退化为该 sample
5. **AccelTimeChart 全 null**：`samples.all { accelerationG == null }` → 走占位分支
6. **AccelTimeChart 部分 null**：跳点不连线 → segment 拆分正确
7. **SectorBar 空 boundaries**：单 sector full lap
8. **SectorBar boundaries 不以 lapStart 起头**：log warning + 仍渲染（不抛异常）

### D10：TrackPolylineMap 触摸交互（与 cursor 高亮的关系）

**问题**：TrackPolylineMap 签名只接受 `cursorAbsoluteTs`，不接受 `onCursorChange`。用户能不能在 map 上拖 cursor？

**Decision（沿用 entry sketch §3 签名）**：
- TrackPolylineMap **不接受触摸**——仅显示 cursor 对应位置的高亮点
- cursor 由 SpeedTimeChart / AccelTimeChart 驱动；map 是 read-only "where am I" 视图
- **Rationale**：Tier 2 详情屏的 UX 模型是"时间轴主导"——chart 是 cursor 输入源，map 是 cursor 输出表现。map 上拾取位置反推 cursor 时间需要"位置 → 最近 sample 索引"，但同一位置可能多次经过（圈速场景常见），歧义无法 graceful 解决。Tier 2 round 若需 map 拾取，再单独立项。

## Risks / Trade-offs

- **[Risk] D1 W2 提前 land `LapTelemetrySample` 与 W1 后续合回起冲突**
  → **Mitigation**：(1) 严格按 entry sketch §1 签名 land，不偏；(2) commit message 明确边界（W1 仅扩展容器与 repository）；(3) 看板 §6 共享文件登记 + W1 启动时核查；(4) 若 W1 实施期发现签名需调整 → user 拍板，rebase 时本 round + W1 同步修

- **[Risk] D2 Compose Canvas 自绘性能 / 触摸事件准确度问题**
  → **Mitigation**：单圈 ≤ 100 sample → 绘制 <16ms 充裕；触摸拾取用 binarySearch O(log n) → 实时响应；如 Tier 2 真机验证发现卡顿 → 单独立项 perf 优化
  → **C4 修订（phase1-hardening-w2-w3-w4-mimo-debt round 透明声明）**：本 round 性能 baseline "<16ms" 是基于"100 sample × Compose drawPath ballpark estimate"，**无量化测量**。若真实 lap 数据 sample 数 > 1000（25Hz × 60s），baseline 不一定成立。**量化 baseline 由 Tier 2 `lap-detail-screen-with-cursor` round 真机首次组屏签收**（与 Tier 2 OQ1 性能 baseline 同类活，避免重复跑 benchmark）；本 round 维持 ballpark estimate + 透明声明此局限。

- **[Risk] D3 cursor 状态外置在 mock preview 时不直观**
  → **Mitigation**：preview Composable 用 `remember { mutableStateOf<Long?>(initialTs) }` 包一下；contract test 直接断言 callback 触发

- **[Risk] D4 nullable accelerationG 在真实数据下行为不可预知（W1 是否填？）**
  → **Mitigation**：本 round 不依赖 W1 行为；contract test 覆盖 (a) 全 null / (b) 部分 null / (c) 全有 三种场景，Tier 2 round 接真实数据时按实际表现验收

- **[Risk] D7 mock helper 在 src/test 不能被 src/main `@Preview` 引用**
  → **Mitigation**：生产 .kt 文件内的 `@Preview` 块就地内联少量 mock 数据（10 sample 足够预览）；MockTelemetry helper 仅供 contract test 用——避免源集边界违规

- **[Risk] D8 V2 视觉规则在 chart 内部小字号文字（轴 tick）触发歧义**
  → **Mitigation**：tick label 一律走 Score Italic Bold（CLAUDE.md §1 表）；不在 chart 内用 Mechanical（避免与时间字符串混淆）

- **[Risk] D9 拆分纯逻辑函数与 Composable 边界把握不当**
  → **Mitigation**：纯逻辑函数命名一致（`computeChartCoordinates` / `findNearestSampleIndex` / `computeSectorBounds`）+ 放 `internal` 顶层函数 + 单测直接调；Composable 仅做 layout 参数透传 + Canvas 调用 + `pointerInput` 内部仅调纯函数 + emit callback

- **[Risk] D9 触摸协议（`detectDragGestures → onCursorChange`）自动化测试覆盖 = 0**
  → **Mitigation**：(1) 核心逻辑（触摸 x → sample 索引 → absoluteTs）已由 `findNearestSampleIndex` + `mapCursorIndexToAbsoluteTs` 纯函数覆盖；(2) `detectDragGestures` 内部仅是 Compose gesture detector 调纯函数 + emit callback 的 glue code；(3) follow-up round `add-compose-ui-test-for-cursor-drag`（tasks §11.4）专门补齐；(4) Tier 2 真机首次联动签收作为最终 gate。**`androidx-ui-test-junit4` 已在依赖中**（`libs.versions.toml:47` / `feature/test/build.gradle.kts:91`），follow-up round 引入成本 = 0

- **[Trade-off] 本 round 不在生产文件内引入 `@Preview`**
  → 项目 src/main 当前 `@Preview` 命中数 = 0（本 round 是项目首个 Compose 组件库，但不同时引入 @Preview）；视觉验收走 contract test 纯函数覆盖 + Tier 2 真机首次联动签收；后续项目全面引入 @Preview 可放 `src/debug/` source set（独立 round scope）

## Migration Plan

无 schema migration / 无 protocol migration。纯新增 UI 文件。

合回顺序参考看板 §4.1：
1. worktree 内编译 + 全单测绿
2. ff-only 合回 `feature/track-tech-v2`
3. 主区合回态再次编译确认
4. 真机验证：本 round **MUST NOT** 单独装机——组件无独立屏入口，靠 Tier 2 round 装机时联动验证；本 round 真机 gate **跳过**（在 tasks.md §9 透明声明）
5. user 拍板 push 顺序（与 W1/W3/W4 互不依赖；远端 kt-format-checker 顺序由 user 决定）

## Open Questions

无 open question——上述 decisions 全部已定。
