## Why

`RecordsHomeScreen` 的 PERFORMANCE / LAPS Segmented control 当前是**空切换**：`var selectedSegment by remember { mutableStateOf("PERFORMANCE") }` 状态切换了，但下面 `Column { ... }` body 始终渲染同一份内容（3 metric tile + Speed Curve placeholder + 5 个 RECENT RUNS 占位），LAPS segment 与 PERFORMANCE 完全相同。

用户提供的渲染图（`docs/design/visual-refs/...` 待落档）明确两个视图应有完全不同的结构：

- **PERFORMANCE**：3 metric tile (`BEST 0-100` / `BEST BRAKE` / `TOTAL RUNS`) + `SPEED CURVE` 卡片（坐标轴 + cyan 曲线 + `100 km/h` 标注点）+ `RECENT RUNS` 列表（trophy 图标 PB 高亮）
- **LAPS**：`CURRENT TRACK RECORD` 大卡片（赛道名 + BEST LAP + 日期 + 赛道 cyan 简笔预览 + 收藏星）+ Track 信息行（赛道名 + 长度 + 方向 + chevron）+ 3 metric tile (`BEST LAP` / `SESSIONS` / `TOTAL LAPS`) + `SESSION HISTORY` 列表

本 round 完成 Segmented 切换的真实视图分发，让 Records tab 在 UI 层达到渲染图所示状态。**数据仍为占位**（与 baseline placeholder 保持一致），真实 TestResultRepository / lap session repository 接入留给后续 round。

修复时机：上一轮 `switch-tab-shell-to-horizontal-pager` 已让 Pager 内 Records page 真正驻留，现在去补它的内容空切换问题，体感连贯。

## What Changes

- **PERFORMANCE 视图骨架**（仅当 `selectedSegment == "PERFORMANCE"` 时渲染）：
  - 顶部 3 个 MetricTile：`BEST 0-100` (4.21 s, 紫) / `BEST BRAKE` (36.8 m, 红) / `TOTAL RUNS` (24, cyan)
  - `SPEED CURVE` 卡片：CutCornerPanel + 标题 + 坐标轴 (km/h × s) + cyan 渐近曲线 + `100 km/h @ 4.21s` 虚线标注点 + 文字气泡（Canvas 绘制，不接真实数据）
  - `RECENT RUNS` 列表：3 条（前 2 条普通：0-100 4.58 s / 100-0 38.2 m + Today 时间戳；第 3 条 PB 高亮：trophy 图标 + 紫色 `PB` accent + `Personal Best` 副文字）
- **LAPS 视图骨架**（仅当 `selectedSegment == "LAPS"` 时渲染）：
  - `CURRENT TRACK RECORD` 大 CutCornerPanel：左半 `Shanghai Tianma` 标题 + `BEST LAP 1:32.457` (Metric Medium) + `May 18, 2024` 副文；右半 Canvas 绘制赛道 cyan 简笔预览（一个变形的闭合曲线 stub）+ 右上角收藏星 icon
  - Track 信息行 TrackTechRow：定位 icon + `Shanghai Tianma` + `3.063 km · Clockwise` 副文 + chevron（点击 Toast 占位）
  - 3 个 MetricTile：`BEST LAP` (1:32.457, 紫) / `SESSIONS` (8, calendar icon, cyan) / `TOTAL LAPS` (56, cyan)
  - `SESSION HISTORY` 列表：3 条 TrackTechRow（`May 18, 2024 · 4 Laps · Best 1:32.457` 等）
- **Filter icon**（右上漏斗 icon 占位）：标题栏右侧加 `Icons.Filled.FilterAlt`（或 `Tune`），点击 Toast `"Filter coming next round"`，PERFORMANCE / LAPS 两个视图都显示
- **数据模型**：所有数据 hardcoded 在文件内私有数据类（`private data class RecentRun(...)` / `LapSession(...)` / `TrackInfo(...)`），不引入领域模型
- **当前 baseline `RecordsHomeScreen` 内的 `selectedSegment == "PERFORMANCE"` 直接渲染的 Column 内容拆分**：把现有 5 个 RECENT RUNS 占位替换为 3 条（含 PB 高亮）；保留 SPEED CURVE 卡片但升级 Canvas 内容；3 metric tile 调整为 BEST 0-100 / BEST BRAKE / TOTAL RUNS；移除"BEST 100-0"（baseline 的命名）改用"BEST BRAKE"

## Capabilities

### New Capabilities

- `records-home-segmented-views`: Records tab 内 PERFORMANCE / LAPS 两个 segmented 视图各自的内容契约（顶部 metric tile 数量与语义、列表结构、卡片骨架、Canvas stub 占位等）

### Modified Capabilities

无（baseline `track-tech-app-shell` 中关于 RecordsHomeScreen 的 Requirement 是"含 `PERFORMANCE | LAPS` Segmented label + `RECENT RUNS` 字面量"骨架级要求，本 round 实现仍命中这两个字面量，向下兼容；不需要 modify 它）

## Impact

### 受影响代码

- `feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/RecordsHomeScreen.kt` — 主体改造（Segmented 切换分发 + PERFORMANCE 视图重组 + LAPS 视图新建 + Canvas stub）

### 不受影响

- `core/*` 全部模块、`simulator/*` 全部模块
- `app/*`、其它 home screen（TestHomeScreen / LapsHomeScreen / DeviceHomeScreen）
- `TrackTechAppShell` / `TrackTechBottomNav` / `TrackTechTestExecutionScreen`
- `TestResultRepository` / `TelemetryRepository` / DAO 层（不接真实数据）
- BLE / GPS 数据链路、RaceChrono BLE 协议（不动）
- SegmentedControl 视觉（不改样式，仅改它下面的视图分发）

### 协议兼容性

无协议改动。

### 双端

仅接收端（gps-app）改动；发射端（simulator）不动。

### 依赖

- `androidx.compose.foundation.Canvas` 已用于其他 tracktech 组件，无需新增依赖
- `androidx.compose.material.icons.filled.FilterAlt` / `EmojiEvents`（trophy）/ `Star` / `LocationOn` / `CalendarMonth` 在 Material Icons 默认包内（baseline 已用 `Speed` / `Flag` / `Insights` / `Bluetooth` 等）

### 测试影响

- 本 round **不**新增单元测试：RecordsHomeScreen 是纯 UI placeholder，所有数据都是文件内私有数据类，外部测试不可见；契约层完全靠 grep + 真机视觉验证兜底
- 现有 `:feature:test:testDebugUnitTest` 全套 MUST 零回归
- 真实数据接入到 ViewModel/Repository 后，**那一 round 再补领域层单元测试**（不在本 round scope）
