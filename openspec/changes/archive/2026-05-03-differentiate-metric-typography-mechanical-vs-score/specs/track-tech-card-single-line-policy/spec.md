## ADDED Requirements

### Requirement: 共享视觉组件内文本字段强制单行 + 溢出省略

`MetricNumber.kt` / `MetricTile.kt` / `TrackTechRow.kt` / `GpsDetailsScreen.DetailMetricTile`（GpsDetailsScreen 内 private 函数）这 4 个**共享视觉组件**内部的所有 `androidx.compose.material3.Text(...)` 调用 MUST 显式传入：

- `maxLines = 1`
- `overflow = TextOverflow.Ellipsis`

具体应加约束的字段：

| 文件 | 字段 / 调用点 |
|---|---|
| `MetricNumber.kt` | value `Text` + unit `Text`（约 line 40-50）|
| `MetricTile.kt` | label `Text` + status `Text`（约 line 38-56）|
| `TrackTechRow.kt` | title `Text` + subtitle `Text`（约 line 56-74）|
| `GpsDetailsScreen.kt` `DetailMetricTile` | label / value / unit / status 4 个 `Text`（约 line 605-660）|

通过这 4 个共享组件渲染的所有调用方自动受益（无需在每个调用方加约束）。

#### Scenario: MetricNumber 内 value Text 单行约束

- **GIVEN** 实施后 `MetricNumber.kt` 内 value `Text(...)` 调用
- **WHEN** 阅读传入的命名参数
- **THEN** 含 `maxLines = 1`
- **AND** 含 `overflow = TextOverflow.Ellipsis`

#### Scenario: MetricNumber 内 unit Text 单行约束

- **GIVEN** 实施后 `MetricNumber.kt` 内 unit `Text(...)` 调用（`if (!unit.isNullOrEmpty()) { Text(...) }` 分支内）
- **WHEN** 阅读传入的命名参数
- **THEN** 含 `maxLines = 1` + `overflow = TextOverflow.Ellipsis`

#### Scenario: MetricTile 内 label Text 单行约束

- **GIVEN** 实施后 `MetricTile.kt` 内 label `Text(...)` 调用
- **WHEN** 阅读传入的命名参数
- **THEN** 含 `maxLines = 1` + `overflow = TextOverflow.Ellipsis`

#### Scenario: MetricTile 内 status Text 单行约束

- **GIVEN** 实施后 `MetricTile.kt` 内 status `Text(...)` 调用（`if (!status.isNullOrEmpty()) { Text(...) }` 分支内）
- **WHEN** 阅读传入的命名参数
- **THEN** 含 `maxLines = 1` + `overflow = TextOverflow.Ellipsis`

#### Scenario: TrackTechRow 内 title Text 单行约束

- **GIVEN** 实施后 `TrackTechRow.kt` 内 title `Text(...)` 调用
- **WHEN** 阅读传入的命名参数
- **THEN** 含 `maxLines = 1` + `overflow = TextOverflow.Ellipsis`

#### Scenario: TrackTechRow 内 subtitle Text 单行约束

- **GIVEN** 实施后 `TrackTechRow.kt` 内 subtitle `Text(...)` 调用（`subtitle != null` 分支内）
- **WHEN** 阅读传入的命名参数
- **THEN** 含 `maxLines = 1` + `overflow = TextOverflow.Ellipsis`

### Requirement: TrackTechRow 布局必须为 ellipsis 提供 bounded 宽度约束

`TrackTechRow.kt` 内层布局 MUST 同步调整，让文本 `Text(...)` 被 bounded max width 测量。仅加 `maxLines = 1, overflow = Ellipsis` 而**不**配合宽度约束时，Compose 的 ellipsis 不会触发：长 subtitle 会按 intrinsic width 撑开，挤压 chevron 或溢出容器。

**MUST 满足以下布局结构**：

- 外层主 Row（含 leading + chevron）MUST **不**使用 `horizontalArrangement = Arrangement.SpaceBetween`（与 weight 路径冲突）
- 内层 leading Row（含 icon + 文本 Column）MUST 应用 `Modifier.weight(1f)`，让 leading 占据除 chevron 之外的所有剩余空间
- 文本 Column（含 title + subtitle）MUST 应用 `Modifier.weight(1f, fill = false)`，让 Text 被 bounded 测量；fill=false 让短文本不强制撑满（视觉清爽），长文本触发 ellipsis（不挤 chevron）
- chevron `Icon` 前 MUST 加 `Spacer(Modifier.width(8.dp))`（或等价固定间距 ≥ 4.dp ≤ 16.dp），避免文本贴住 chevron

#### Scenario: 外层 Row 不使用 SpaceBetween

- **GIVEN** 实施后 `TrackTechRow.kt` 内最外层 `Row(...)` 调用（包含 leading + chevron 的主 Row）
- **WHEN** 阅读 `horizontalArrangement` 参数
- **THEN** 该参数 **不**等于 `Arrangement.SpaceBetween`（默认 `Arrangement.Start` 或省略均可接受）

#### Scenario: 内层 leading Row 应用 weight(1f)

- **GIVEN** 实施后 `TrackTechRow.kt` 内层 leading Row（包含 icon + 文本 Column）
- **WHEN** 阅读 `Row(modifier = ...)` 的 modifier 链
- **THEN** 含 `.weight(1f)` 调用（让 leading Row 占据剩余空间）

#### Scenario: 文本 Column 应用 weight(1f, fill = false)

- **GIVEN** 实施后 `TrackTechRow.kt` 内层 leading Row 内的文本 `Column(modifier = ...)`
- **WHEN** 阅读 modifier 链
- **THEN** 含 `.weight(1f, fill = false)` 调用（让 Text 被 bounded 测量但不强制撑满）

#### Scenario: chevron 前有固定 Spacer

- **GIVEN** 实施后 `TrackTechRow.kt` 内 chevron `Icon(imageVector = Icons.Filled.ChevronRight, ...)` 调用
- **WHEN** 阅读 chevron 之前的兄弟元素
- **THEN** 含 `Spacer(Modifier.width(...))` 调用，width 在 `4.dp..16.dp` 范围内（推荐 `8.dp`）

#### Scenario: DetailMetricTile 4 个 Text 单行约束

- **GIVEN** 实施后 `GpsDetailsScreen.kt` 内 `DetailMetricTile` 函数 body 的 4 个 `Text(...)` 调用（label / value / unit / status）
- **WHEN** 阅读每个调用的传入命名参数
- **THEN** 各含 `maxLines = 1` + `overflow = TextOverflow.Ellipsis`

### Requirement: 各 home screen 内直接 Text 调用按字段类型加单行约束

各 home screen / execution screen 内**直接** `Text(...)` 调用（不通过 `MetricNumber` / `MetricTile` / `TrackTechRow` 组件）的"卡片 / 行 / 标签 / 数字 metric"类字段 MUST 加 `maxLines = 1, overflow = TextOverflow.Ellipsis`。

具体清单：

| 文件 | 字段 |
|---|---|
| `RecordsHomeScreen.kt` | CURRENT TRACK RECORD 卡内：`CURRENT TRACK RECORD` label / `Shanghai Tianma` 标题 / `BEST LAP` label / `1:32.457` value (line 488 ScoreMedium 那处) / `May 18, 2024` 日期 |
| `RecordsHomeScreen.kt` | `SegmentedControl` 选项 `Text`（`PERFORMANCE` / `LAPS`）|
| `RecordsHomeScreen.kt` | RECENT RUNS / SESSION HISTORY section header `Text` |
| `RecordsHomeScreen.kt` | `Records` 标题 + 副 `RecordsTitleRow` 内 `Text` |
| `TestHomeScreen.kt` | `SpeedHero` 内 `SPEED` label / `STATUS` 行 / `READY` 状态文本 |
| `TestHomeScreen.kt` | `Drive Test` 标题 + `PERFORMANCE TEST` / `LATEST RESULT` section header |
| `TrackTechTestExecutionScreen.kt` | `CURRENT SPEED` label + 速度 value + `km/h` unit |
| `TrackTechTestExecutionScreen.kt` | `ELAPSED TIME` label + 时间 value + `s` unit |
| `TrackTechTestExecutionScreen.kt` | PhaseBanner 内 phaseTag / phaseTitle / phaseSub |
| `TrackTechTestExecutionScreen.kt` | ProgressPanel 内 `0` / `100` 端点 + `0-100%` 中央 + targetLabel |
| `TrackTechTestExecutionScreen.kt` | SignalFooter 内 `SATELLITES` / `HDOP` label + value `Text` |
| `LapsHomeScreen.kt` | `Laps` 标题 + `CurrentTrackPanel` 内卡片标题 + 副文 + `RECENT BEST` label |
| `DeviceHomeScreen.kt` | Readiness Hero 主 / 副 / accent label `Text` |
| `DeviceHomeScreen.kt` | Quick Status Row 内各 tile 的字段（除走 MetricTile 的之外的直接 Text）|
| `DeviceHomeScreen.kt` | Connected Device card 的 device name + 副状态文本 |
| `DeviceHomeScreen.kt` | `Device` 标题 |
| `TrackTechBottomNav.kt` | 4 个 tab item label `Text`（`Test` / `Laps` / `Records` / `Device`）|

#### Scenario: RecordsHomeScreen CURRENT TRACK RECORD 卡内文本单行

- **GIVEN** 实施后 `RecordsHomeScreen.kt` 内 `CurrentTrackRecordCard` Composable 内的 5 个 `Text(...)` 调用（CURRENT TRACK RECORD label / trackName / BEST LAP label / bestLapTime / bestLapDate）
- **WHEN** 阅读每个调用的传入命名参数
- **THEN** 各含 `maxLines = 1` + `overflow = TextOverflow.Ellipsis`

#### Scenario: RecordsHomeScreen SegmentedControl 选项单行

- **GIVEN** 实施后 `RecordsHomeScreen.kt` 内 `SegmentedControl` 函数 body 内 option `Text(...)` 调用
- **WHEN** 阅读传入的命名参数
- **THEN** 含 `maxLines = 1` + `overflow = TextOverflow.Ellipsis`

#### Scenario: TestHomeScreen SpeedHero 内文本单行

- **GIVEN** 实施后 `TestHomeScreen.kt` 内 `SpeedHero` Composable 内的 `Text(...)` 调用（SPEED label / STATUS / READY 状态文本）
- **WHEN** 阅读每个调用的传入命名参数
- **THEN** 各含 `maxLines = 1` + `overflow = TextOverflow.Ellipsis`

#### Scenario: TrackTechTestExecutionScreen 速度 + 时间显示单行

- **GIVEN** 实施后 `TrackTechTestExecutionScreen.kt` 内 `BigSpeedDisplay` + `ElapsedTimeDisplay` + `PhaseBanner` + `ProgressPanel` + `SignalFooter` 5 个 Composable 内的所有 `Text(...)` 调用
- **WHEN** 阅读每个调用的传入命名参数
- **THEN** 各含 `maxLines = 1` + `overflow = TextOverflow.Ellipsis`

#### Scenario: TrackTechBottomNav tab item label 单行

- **GIVEN** 实施后 `TrackTechBottomNav.kt` 内 `TrackTechBottomNavItem` 函数 body 内 tab label `Text(...)` 调用
- **WHEN** 阅读传入的命名参数
- **THEN** 含 `maxLines = 1` + `overflow = TextOverflow.Ellipsis`

### Requirement: Toast / 系统对话框 / 长描述文本 MUST NOT 加单行约束

以下场景的 `Text(...)` 调用 MUST NOT 受单行约束：

- 系统 Toast（不在 Compose 控制范围）
- AlertDialog / Snackbar 内的描述文本
- 错误信息 / 帮助说明类长描述（本项目当前没有，作为防御性约定）

#### Scenario: Toast 调用不动

- **GIVEN** 实施前后 `Toast.makeText(...).show()` 调用
- **WHEN** `git diff` 这些调用
- **THEN** 零行改动（Toast 不受本 round 约束）

### Requirement: 不引入 autoSize 字号自适应

本 round MUST NOT 引入：

- 第三方 autoSize 库（如 [Compose-AutoSize-Text](https://github.com/...)）
- Compose foundation 1.7+ 的 `BasicText.autoSize` API（项目当前 `composeBom = 2023.08.00` → foundation 1.5/1.6，不可用）
- 自定义字号自适应组件

字段过长时通过 `overflow = Ellipsis` 显示省略号 fallback；如某字段确认必须显示完整内容（如赛道名），由内容设计层调整字符串（短化），不通过 UI 层字号自适应解决。

#### Scenario: 不引入 autoSize 依赖

- **GIVEN** 实施前后 `feature/test/build.gradle.kts`
- **WHEN** `git diff` 该文件
- **THEN** 零行改动（不引入新 testImplementation / implementation）

#### Scenario: 不使用 BasicText.autoSize

- **GIVEN** 实施后 `feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/` 子包全部 `.kt` 文件
- **WHEN** grep `BasicText\|autoSize\|TextAutoSize`
- **THEN** 零命中
