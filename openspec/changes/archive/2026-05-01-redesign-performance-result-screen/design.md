## Context

Track Tech V2 视觉系统已经在 `feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/` 沉淀完整组件库（`CutCornerPanel` / `MetricTile` / `MetricNumber` / `TrackTechColors` / `TrackTechTypography` / `TrackTechRow`）。圈速侧的 `LapSessionDetailScreen` 已经按 V2 重做并稳定使用，是本 round 的"参考兄弟"。

性能测试详情页 `feature/test/src/main/java/com/blazepush/feature/test/ui/screen/TestResultScreen.kt` 仍是 V1 风格（Material3 大圆角 Card + 默认 colorScheme + `Text(...) fontSize` 直写），由 `TestFlowNavigation.kt` 的 `TestNavRoute.Result` 分支直接 wire 进入。数据来源 `TestRecordEntity`（Room）+ `PerformanceTestTelemetryReader.read(dataFilePath)`（二进制流 → `GpsDataPoint` 列表 → 派生 `SpeedSegment`）。两个图表 `SpeedChart` / `GForceChart` 在 `feature/test/src/main/java/com/blazepush/feature/test/ui/components/` 下，是 V1 时期的 Compose Canvas 实现。

`split-records-tab-performance-and-laps` round 已经把 Records tab 的 PERFORMANCE 子页骨架按 V2 重做（3 metric tile + SPEED CURVE 卡 + RECENT RUNS 列表，但 `onClick` 是占位 Toast，没有真正跳转到详情页）。本 round 的工作只覆盖"详情页本身"，`onClick` 跳转的 wire-up 是后续 `wire-real-data-to-records-and-laps-tabs` round 的责任。

约束：

- V2 视觉规则：CLAUDE.md "UI 视觉约束（Track Tech V2）"
  - DSEG7 字体只用于仪表瞬时数字（本页全是结果数字 → 全部走 Score）
  - metric/row/label 类 Text MUST `maxLines = 1, overflow = TextOverflow.Ellipsis`
  - 水平多元素 Row 必须配 `weight(1f)` + `weight(1f, fill = false)` 约束才能让 ellipsis 生效
- V2 视觉色规：`docs/design/track-tech-v2-cc-guidance.md`
  - 紫色 = 主行动 / 当前态 / 选中态；cyan = GPS/BLE/轨迹/图表线；red = braking/error/peak；green = ready
  - `Records` 视觉强度低（专业图表优先），不能像执行页一样高强度
- 字段边界：`TestRecordEntity` schema 不动；UI 层不取用 `carModel`
- 多 change 并行：按 CLAUDE.md "多 change 并行协同" 走 worktree

## Goals / Non-Goals

**Goals:**

- 性能测试详情页视觉对齐 Track Tech V2，与 `LapSessionDetailScreen` 形成同一视觉语言
- 信息边界保持现状（去掉 `carModel`），不增不减字段语义
- `SpeedChart` / `GForceChart` 内部 stroke / grid / axis / 标题字体颜色保持不动，仅在文件外层加 `wrapInCard: Boolean = true` 开关，让 V2 详情页可以无 Card 嵌入（避免 V2 cut-corner 卡里嵌一个 V1 Material Card 的双层卡问题）
- Hero 主成绩按 `TestTemplate` 分支（加速→`totalTime + s`；制动→`totalDistance + m`），与现行 `TestHistoryScreen.kt` 业务语义一致
- Hero 主成绩数字使用 V2 视觉强调（紫色 + Score Hero 字体）
- V2 NavHost 注册 `performance_result/{testId}` route（独占文件 `TrackTechAppShell.kt`）；让 F round 接 RecentRuns 真实数据时一行 `navigate(...)` 即可入端
- 测试用例不依赖 Robolectric / Android Context，纯字面量 contract test 兜底视觉漂移
- 与正在并行的 round（`split-records-tab-performance-and-laps` / `add-lap-session-phase1` / `fix-lap-binary-ts-hygiene` / `add-debug-preset-track-boyu-loop`）零文件交叉，唯一共享文件 `TestFlowNavigation.kt` 在看板 §6 登记后再动

**Non-Goals:**

- 不改 `TestRecordEntity` Room schema，不做 migration
- 不改 `SpeedChart` / `GForceChart` 内部 Canvas 绘制 / stroke / grid / axis / 标题字体颜色（视觉对齐 V2 token 留下个 round；本 round 仅加 `wrapInCard` 开关）
- 不改 `PerformanceTestTelemetryReader` 二进制读取
- 不改 `calculateSegmentsFromPoints` / `calculateSegment` / `calculateSegmentDistance` 派生逻辑（整体搬迁，语义不变）
- **不改 `RecordsHomeScreen.kt`**（RecentRuns 接真实数据 + onClick 跳转完全交给 F round；本 round MUST NOT 在该文件 commit 任何 diff）
- 不改 V1 `TestFlowNavigation.kt`（dead code）
- 不接入 RecordsHomeScreen RecentRuns 列表 onClick 跳转（后续 round）
- 不删旧 `TestResultScreen.kt`（cleanup round 处理）
- 不像素级复刻 V2 效果图（按 guidance "App 大结构正确 + 视觉感觉接近"）

## Decisions

### Decision 1: 新建 `PerformanceResultScreen.kt`，不在原文件改写

**选择**：在 `feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/PerformanceResultScreen.kt` 新建文件，保留 `feature/test/src/main/java/com/blazepush/feature/test/ui/screen/TestResultScreen.kt`。

**对比**：

| 方案 | 优势 | 劣势 |
|---|---|---|
| **A 新建（采用）** | Codex review 看 diff 时，新文件全部是新代码，不混 V1/V2 上下文；旧 V1 在 cleanup round 删干净；包路径 `tracktech/` 与 V2 兄弟同住，发现性更好 | 短期内仓库里同时存在 V1 / V2 两份详情页代码 |
| **B 原地改写** | 仓库无重复 | Codex review 困难（需要在大量 diff 里区分"删 V1 + 加 V2"）；包路径 `screen/` 与 V2 兄弟分散 |

**理由**：用户明确指示"新建"。同时这是 V2 视觉重做 round 的标准做法（参考 `LapSessionDetailScreen.kt` 也是新建在 `tracktech/`，未原地改写过 V1 详情页）。

### Decision 2: 数据派生函数整体搬迁，不抽公共模块

**选择**：把 `calculateSegmentsFromPoints` / `calculateSegment` / `calculateSegmentDistance` 从 `TestResultScreen.kt` 整体复制到 `PerformanceResultScreen.kt`（作为 `private` 顶层函数），不抽到 `core/domain` 或独立 utils 模块。

**对比**：

| 方案 | 优势 | 劣势 |
|---|---|---|
| **A 整体搬迁（采用）** | 改动局限本 round；新文件自包含；旧 V1 删除时这些 private 函数也跟着删，无外部依赖 | 短期内两份代码（V1/V2 各一份） |
| **B 抽公共模块** | DRY | 改动放大到 core 模块；引入新的依赖关系；本 round 不该做 baseline 改造（CLAUDE.md "避免污染现有代码"） |

**理由**：本 round 是 UI 视觉重做，scope 严格限于 `feature/test/ui/`。派生函数下个 cleanup round 删旧文件时一并消失。如果发现独立 round 需要复用这些函数，再单独立项抽公共模块，跟 `laptime-gps-filter-integration-deferred` memo 同样的延期立项规矩。

### Decision 3: SpeedChart / GForceChart 加 `wrapInCard` 开关（不改 stroke/网格颜色）

**背景**：现 `SpeedChart` / `GForceChart` 文件结构是 `Card { Column { 标题 Row + Canvas + 时间轴 } }` —— 内部已经渲染 Material3 `Card` 容器（`feature/test/ui/components/SpeedChart.kt:44` 与 `:186`）。如果 V2 详情页直接把 `SpeedChart` 包到 `CutCornerPanel` 里，最终渲染就是"V2 cut-corner 卡里嵌一个 V1 Material 圆角 Card"，双层卡视觉。

**选择**：给 `SpeedChart` / `GForceChart` 新增 `wrapInCard: Boolean = true` 参数。默认 true（所有现有调用方零改动）；V2 详情页传 false → chart 跳过外层 `Card { ... }`，直接渲染 `Column { 标题 Row + Canvas + 时间轴 }`。stroke / grid / axis / 标题字体颜色仍保持 V1（这部分留给后续 round 对齐 V2 token）。

**对比**：

| 方案 | 优势 | 劣势 |
|---|---|---|
| **A 加 `wrapInCard` 开关（采用）** | 最小改动（每文件 +5 行）；向下完全兼容（默认值 true）；V2 详情页无双层卡；chart 内部颜色后续 round 处理，不污染本 round scope | chart 文件签名变了（虽然兼容），仍属本 round 改动 |
| **B 抽 `SpeedChartContent` 内部 Composable** | 结构更整洁（公共 `SpeedChart` 仍 Card-wrap 调用 Content；导出 internal `SpeedChartContent` 给 V2 用） | chart 文件结构动作大；引入新 internal Composable，Codex review 关注面更广 |
| **C 复用不改 chart，外面包 CutCornerPanel** | chart 文件零改动 | 双层卡（V2 cut-corner 嵌 V1 Material Card）—— **被 Codex review 否决**：违背"V2 视觉重做"目标 |
| **D 接受双层卡作为 Non-Goal** | 改动最小 | 视觉退步明显，跟 round 名字冲突（"redesign performance result screen" 但视觉做不彻底） |

**理由**：Codex review 准确指出 V1 双层卡问题（`docs/superpowers/reviews/...` 风格的 P1 反馈）。方案 A 是最小且向下兼容的修复路径：原 SpeedChart/GForceChart 调用方（如果存在 `LapDebugResultScreen` 等）无需改动，V2 详情页通过新参数无缝接入。chart 颜色对齐留给后续独立 round（已在 follow-up backlog 14.2 项）。

### Decision 3.5: Hero 主成绩按 TestTemplate 分支

**背景**：`TestRecordEntity` 包含 `totalTime`（秒）与 `totalDistance`（米）两个核心结果字段。加速测试（`acc_0_100`）的核心成绩是"0→100 用时"（`totalTime` 秒），制动测试（`brake_100_0`）的核心成绩是"100→0 刹停距离"（`totalDistance` 米）。`TestHistoryScreen.kt` line 164-166 现有显示已经按 `testTemplateId` 分支处理。

**选择**：Hero 主成绩字段按 `TestTemplate.fromId(record.testTemplateId)` 分支：

| Template | Hero value | Hero unit |
|---|---|---|
| `Acceleration0To100` | `String.format("%.2f", record.totalTime)` | `"s"` |
| `Braking100To0` | `String.format("%.1f", record.totalDistance)` | `"m"` |
| 未知 / null | `record.result`（兜底原始字符串） | 不显示 unit |

同时 Metric Row 第 1 格也按 template 分支避免与 hero 重复：

| Template | 第 1 格 label | 第 1 格 value | 第 1 格 unit |
|---|---|---|---|
| `Acceleration0To100` | `DISTANCE` | `String.format("%.1f", record.totalDistance)` | `m` |
| `Braking100To0` | `TIME` | `String.format("%.2f", record.totalTime)` | `s` |

第 2 / 3 格固定（`PEAK G` / `AVG G`），不分支。

**对比**：

| 方案 | 优势 | 劣势 |
|---|---|---|
| **A 按 template 分支（采用）** | 与 `TestHistoryScreen` line 164-166 现行业务语义一致；制动测试首屏直接看到刹停距离（用户习惯）；metric 不与 hero 重复 | 实现稍复杂（hero + metric 第 1 格都要分支） |
| **B Hero 永远固定 `totalTime + s`** | 实现简单 | **被 Codex review 否决**：制动测试核心成绩是距离不是时间；现行 `TestHistoryScreen` 已经按 template 分支显示，详情页与列表页语义不一致会困惑用户 |

**理由**：Codex review 命中点。`TestHistoryScreen` 已经把这个分支建立为业务约定（第 1 个 round 加进来时就已分支）。详情页作为列表页点进的"放大版"，hero 主成绩字段必须沿袭同一约定，否则用户会看到列表显示 `36.8 m` 但点进去 hero 显示 `4.21 s`，体验断裂。

### Decision 4: 字体角色分配

**选择**：按下表分配 `TrackTechTypography` 角色：

| 元素 | 字体角色 | accent 颜色 |
|---|---|---|
| Hero `TEST TYPE` label | `UiTextLabel` | `Cyan` |
| Hero `0-100 km/h` 类型大标题 | `RacingTitleMedium` | `TextPrimary` |
| Hero 主成绩数字 `4.21` 或 `36.8` | Score Hero (`MetricNumber` `kind = MetricKind.Score, size = MetricSize.Hero, valueColor = TrackTechColors.Purple`)，**注意 `MetricNumber` 用 `valueColor` 而非 `accentColor`**；`MetricTile` 才有 `accentColor` 参数 | `Purple`（V2 主行动/强调色） |
| Hero unit `s` | `UiTextBody` | `TextSecondary` |
| Hero `Date` / `Device` 副信息 | `OverviewRow`（`UiTextLabel` + `UiTextBody`） | `TextSecondary` / `TextPrimary` |
| `DISTANCE` / `TIME` MetricTile（第 1 格，按 template 分支） | `MetricTile` Score Medium，`accentColor` 参数 | `Cyan` |
| `PEAK G` MetricTile | `MetricTile` Score Medium，`accentColor` 参数 | `Red`（peak/braking 语义） |
| `AVG G` MetricTile | `MetricTile` Score Small，`accentColor` 参数 | `TextSecondary`（弱化，平均值不抢主成绩戏） |
| Section header `SPEED CURVE` / `G-FORCE` / `SPEED SEGMENTS` | `UiTextLabel` | `Cyan` |
| Segment row 区间 label `0–10 km/h` | `UiTextLabel` | `TextSecondary` |
| Segment row 时间 `0.42 s` / 距离 `3.2 m` | `UiTextBody` | `TextPrimary` |

**对比**：

| 方案 | 优势 | 劣势 |
|---|---|---|
| **A 全 Score（采用）** | 符合 CLAUDE.md "DSEG7 七段字体只用于仪表瞬时数字"；本页全是结果数字（带小数点字符串、单位 unit），不是仪表瞬时；与 LapSessionDetail 一致 | 视觉冲击力略弱（如果用 Mechanical 数字会更"赛车" feel） |
| **B Hero 用 Mechanical** | 视觉冲击力强 | **违反规则**：CLAUDE.md 明示带字符串/小数点是结果数字 → Score；Mechanical 字体只吃纯数字（`4.21` 含小数点） |

**理由**：用户在草案对话已明确"对的，几乎全 Score"。规则上 Mechanical 仅适用纯数字 + unit 拆分，hero 数字 `4.21` 带小数点，不能走 Mechanical。

### Decision 5（再次替换 —— 路径 A scope 转移）：入口接入边界 —— redesign 只注册 V2 NavHost route，wire-up 转给 F round

**背景演化**：

- 第一版 Decision 5：改 V1 `TestFlowNavigation.kt`（被 P0 否决：V1 是 dead code，MainActivity 直接 setContent V2 Shell）
- 第二版 Decision 5：本 round 同时做"V2 NavHost 注册 + RecordsHomeScreen RecentRuns 真实数据 + 跳转"
- 第三版（当前）：apply 阶段 task 1.x 看板核查发现 `F. wire-real-data-to-records-and-laps-tabs` round 已经在另一 worktree 推进（11 个文件未 commit 改动 + 看板 §5 line 131 明示 F round scope 包含"PERFORMANCE / LAPS 全部 mock 接真实，**删 placeholderRecentRuns** / placeholderLapSessions / LapSessionRow"）。redesign 第二版的"接 RecentRuns 真实数据 + onClick"工作与 F round 100% 重叠，写完会被 F 覆盖

**当前选择（路径 A）**：redesign round 只保留：

1. 新建 `PerformanceResultScreen.kt`（V2 详情页本身）
2. **TrackTechAppShell.kt 注册 `performance_result/{testId}` route**（独占，F round 不动 NavHost）
3. SpeedChart / GForceChart 加 `wrapInCard: Boolean = true` 开关
4. Contract test（仅锁详情页字面量 + NavHost route 字面量）
5. V1 `TestFlowNavigation.kt` 不动

**移出 redesign scope（转给 F round）**：

- `RecordsHomeScreen.kt` 不进 redesign 改动
- F round 在接 `recentRuns: StateFlow<List<TestResultSummary>>` 到 PERFORMANCE 子页 UI 时，RecentRuns row `onClick` MUST 调 `navController.navigate("performance_result/${result.id}")`
- F round 真机 gate 增加"点击真实 RecentRuns row 能进入 V2 PerformanceResultScreen"

**入口路径盘点**：

| 候选入口 | V2 现状 | 本 round 决策 |
|---|---|---|
| **A. `RecordsHomeScreen` PERFORMANCE → RecentRuns onClick** | hardcoded placeholder + Toast；F round 正在改成真实数据 | **F round 接管**：F 接真实数据时一并补 `navController.navigate(...)` |
| **B. V2 Test tab 跑完测试** | `TrackTechTestExecutionScreen` 跑完 `popBackStack` | **不做**：UX 决策点 |
| **C. V2 完整 Test History 列表** | V2 当前没有 | **不做**：F round 接 RecentRuns 后已是主入口 |
| **D. V1 `TestFlowNavigation.TestNavRoute.Result`** | dead code | **不做** |

**redesign round 内部技术决策**：

- **route 模式**：复用 `TrackTechAppShell.kt:167-177` 现有 `lap_session_detail/{sessionId}` 模式 —— `composable("performance_result/{testId}", arguments = listOf(navArgument("testId") { type = NavType.StringType })) { ... PerformanceResultScreen(testId, onBack = { navController.popBackStack() }) }`
- **redesign round 与 F round 的合回顺序**：redesign 先合（独占文件 + NavHost route 注册）；F 后合（接真实数据时把 onClick navigate 加进去）。理由：F 加 navigate 那一行依赖 NavHost 上有 route，否则 navigate 会跳到不存在的 route 触发 NavHost 异常

**对比**：

| 方案 | 优势 | 劣势 |
|---|---|---|
| **A 路径 A scope 转移（采用）** | redesign 与 F 边界清晰；不重复劳动；不会出现 redesign 写完被 F 覆盖；F 加一行 navigate 是它工作的自然延伸 | redesign 工件需第三轮 patch（删 RecordsHomeScreen 部分） |
| **B 路径 B redesign 严格按原计划** | 不动 redesign 工件 | 100% 重复劳动；F 必然覆盖 redesign 的 RecentRuns wire-up；Codex review 看两遍 |
| **C 路径 C 暂停 redesign 等 F** | 0 冲突 | 队列卡死；redesign 完全可独立做的 NavHost route + 详情页 + chart 改动也被无谓阻塞 |

**理由**：用户拍板路径 A（apply task 1 阶段拍板）。F round 正在重写整个 RecordsHomeScreen，加一行 navigate 是它工作的自然延伸；redesign round 的 NavHost route 注册是 F 那条 navigate 行的前置依赖，因此 redesign 先合回也对 F 友好。

### Decision 5.5（DELETED —— 路径 A 后已不适用）

原 Decision 5.5 是关于"split-records-tab-performance-and-laps round 串行依赖"。路径 A 后 redesign 不再动 `RecordsHomeScreen.kt`，与 split round 无任何文件交叉，串行依赖 gate 不再适用。task 1.x 已移除该 gate。

### Decision 6: Contract test 形态

**选择**：`PerformanceResultScreenContractTest.kt` 用纯字符串 grep 风格（读取 `.kt` 源文件文本，断言关键字面量出现），不依赖 Compose runtime / Robolectric。

**对比**：

| 方案 | 优势 | 劣势 |
|---|---|---|
| **A 字面量 grep（采用）** | 不依赖 Android Context；JVM 单元测试快；锁定视觉关键字面量防止后续漂移；同 RecordsHomeScreen / TabGatingPolicy 等已有 contract test 模式 | 不验证渲染正确性（只验字面量存在） |
| **B Compose UI test（`createComposeRule`）** | 验证真实渲染 | 需要 Robolectric / instrumentation；测试运行慢；与本 round "纯 UI placeholder + 不接真实数据" 不匹配 |

**理由**：本 round 数据完全来自 Room + binary，详情页本身是渲染层。视觉漂移最常见的形态是后续 round 误删 / 重命名关键字面量（label 名、section 名），grep contract 是最便宜的兜底。

## Risks / Trade-offs

- **[redesign 与 F round 顺序 / 入端 race]** → redesign 先合回（注册 route）、F 后合回（用 route）是预期顺序。如果 F 先合回（接了真实数据但 navigate 调到尚未注册的 route），运行时 NavHost 会抛 IllegalArgumentException。**Mitigation**：(1) redesign 工件已明示"redesign 先合 → F 后合"；(2) F round tasks.md 加 follow-up task 时注明"等 redesign 合回再加 navigate"；(3) F round 真机 gate 加"点击 RecentRuns 能进 V2 PerformanceResultScreen"作为接 navigate 后的回归检验；(4) 如果调度倒置，F 在加 navigate 前 grep `TrackTechAppShell.kt` 确认 `performance_result/{testId}` 字面量存在
- **[V2 NavHost route 命名冲突]** → 添加 `performance_result/{testId}` 不与现有 `lap_session_detail/{sessionId}` / `test_execution` / `gps_details` / `lap_live` 冲突。**Mitigation**：grep 验证 `performance_result` 字面量在仓库内首次出现；route 命名遵循现有"action_target/{id}"格式
- **[V1 dead code 误改 / Codex review 噪声]** → 本 round 决策不改 V1 `TestFlowNavigation.kt`，但旧 task 8 文档残留可能误导。**Mitigation**：tasks.md 把旧 task 8 显式标 "DELETED — V1 dead route, 改了无效"；contract test 不验证 V1 文件
- **[scope 转移给 F round 的责任不闭环]** → 路径 A 把 RecordsHomeScreen wire-up 转给 F round。如果 F round owner 没看到这个责任转移，可能会以为 redesign 已经做了 wire-up，导致两边都没做（典型 ownership 漂移）。**Mitigation**：redesign apply 阶段 MUST 在 F round 工件（proposal Impact + tasks.md follow-up）显式落账；看板 §5 在 F round 行加 "依赖 redesign-performance-result-screen 合回（route 注册）" 备注
- **[V1 `TestResultScreen.kt` 残留]** → 本 round 不删，可能造成"代码里有两份"的体感。**Mitigation**：proposal 明确标注"等所有 navigation 入口确认无引用后另起 cleanup round 删"；当前 grep 显示 V1 仅在 `TestFlowNavigation.kt` line 139 有引用，本 round 改完后无引用，cleanup round 可以直接物理删除
- **[Chart 颜色不完全对齐 V2]** → `SpeedChart` / `GForceChart` 内部 stroke / grid / axis / 标题字体颜色保留 V1。**Mitigation**：在 design.md 与 tasks.md 明确标记为已知 trade-off；后续独立 round 处理（已加入 follow-up backlog 14.2 项；如果用户视觉验证发现刺眼明显，提前立项）

- **[`wrapInCard` 参数误传与未来漂移]** → V2 详情页传 `wrapInCard = false`，但如果未来 chart 内部结构变化（比如把 Card 改到不同位置），`wrapInCard = false` 路径与 `true` 路径可能行为分叉。**Mitigation**：参数实现用 if-else 包裹整个 Card 容器即可（不要把 Card 内的逻辑跨条件复用）；contract test 加回归（V2 详情页 import + 调用 `wrapInCard = false` 字面量出现）
- **[carModel 字段被悄悄"忘掉"]** → 仅 UI 层不取用，数据层仍然写入。**Mitigation**：proposal 与 design 都明示"`TestRecordEntity` schema 不动"；后续如确定要废弃 carModel 概念，独立 round 做 schema migration（v4 → v5）；本 round 不做
- **[小屏 V2 视觉不通过 truncation gate]** → Hero 数字 + unit 在小屏可能拼不下。**Mitigation**：tasks.md 明确要求小屏机型（如 vivo V2405A）真机验证，按 CLAUDE.md "UI 视觉约束 §4 真机验证 gate"；如果小屏 truncate，缩成 Score Medium + 字符串简化（不引入 autoSize 库）

## Migration Plan

无 schema / 协议 migration。

部署步骤：

1. 开 worktree `.worktrees/redesign-performance-result-screen/`，切到 `feature/track-tech-v2`
2. 看板 §5 登记 round；§6 登记 `TestFlowNavigation.kt` 共享占用
3. 实施 tasks.md 各阶段（详见 tasks.md）
4. 编译里程碑（task 6.x 完）→ ff-only 合回 `feature/track-tech-v2`，看板 §5 标"合回"
5. 真机验证（华为 + 小屏）通过 → 看板 §5 标"完成"
6. Codex review 触发；通过后归档 round
7. cleanup round 立项（删 V1 `TestResultScreen.kt`）

回滚策略：

- 本 round 是新增文件 + 1 处 navigation 路由切换。回滚就是 revert commit
- `TestRecordEntity` schema 不动 → 数据层无回滚成本
- 旧 `TestResultScreen.kt` 文件保留 → 回滚后路由直接指回 V1

## Open Questions

无未决问题。原 6 个 decisions + Codex review 后追加的 Decision 3.5（hero 主成绩按 template 分支）+ Decision 3 修订（chart 加 `wrapInCard` 开关）已全部确认。
