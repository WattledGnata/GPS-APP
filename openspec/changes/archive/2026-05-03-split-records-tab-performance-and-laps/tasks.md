## 实施任务（依赖顺序）

本 change 把 `RecordsHomeScreen` 的 PERFORMANCE / LAPS Segmented control 从空切换改造为真实视图分发，并补完两个视图的骨架（数据仍占位）。覆盖：

- §0 grep 预检
- §1 数据类 + placeholder 数据集
- §2 PerformanceView 拆分 + Speed Curve Canvas + 3 条 Recent Runs（含 PB 高亮）
- §3 LapsView 新建：CURRENT TRACK RECORD 卡 + Track 信息行 + 3 metric tile + Session History
- §4 标题栏 filter icon 占位
- §5 主 Column body 用 when 分发到 PerformanceView / LapsView
- §6 编译/测试门槛
- §7 真机视觉验证（manual gate）
- §8 commit + 合流门槛

参考 `proposal.md` / `design.md` / `specs/records-home-segmented-views/spec.md`。

---

## 0. grep 预检（apply 阶段开工前一次性执行）

- [x] 0.1 **TrackTechRow API 兼容性核实**：

  ```bash
  grep -n "fun TrackTechRow" /Users/wattledgnata/traeProjects/gps-app/feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/TrackTechRow.kt
  ```

  确认 `TrackTechRow` 是否接受 `accentColor` / `tintColor` 参数。
  - 若支持 → PB 高亮项可用 accent 紫色
  - 若不支持 → 不扩 baseline API，PB 视觉差异化仅靠 leadingIcon (trophy) + subtitle 含 `"Personal Best"` 文字差异化兜底

- [x] 0.2 **CutCornerPanel API 与 cutCornersDiagonal preset 核实**：

  ```bash
  grep -n "cutCornersDiagonal\|cutCornersAll\|fun CutCornerPanel" /Users/wattledgnata/traeProjects/gps-app/feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/CutCornerPanel.kt
  ```

  确认 baseline 提供哪些切角 preset，CURRENT TRACK RECORD 卡片用 `cutCornersDiagonal` 还是其他。

- [x] 0.3 **MetricTile API 与字段核实**：

  ```bash
  grep -n "fun MetricTile" /Users/wattledgnata/traeProjects/gps-app/feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/MetricTile.kt
  ```

  确认 `MetricTile` 接受参数：`label` / `value` / `unit` / `status` / `accentColor`，以及 `valueSize`（如 Hero / Medium / Small）形态。

- [x] 0.4 **filter icon 可用性核实**：

  ```bash
  grep -rn "FilterAlt\|filled.Tune" /Users/wattledgnata/traeProjects/gps-app/feature/test/src/main --include="*.kt"
  ```

  确认 `Icons.Filled.FilterAlt` / `Icons.Filled.Tune` 在项目内是否已被引用过；都没有也无妨（Material Icons 默认包内必含一个）。

- [x] 0.5 **EmojiEvents (trophy) icon 核实**：

  ```bash
  grep -rn "EmojiEvents" /Users/wattledgnata/traeProjects/gps-app/feature/test/src/main --include="*.kt"
  ```

  确认 trophy icon 位置；不在已有 import 也没关系，新加 import 即可。

---

## 1. 数据类 + placeholder 数据集

- [x] 1.1 在 `RecordsHomeScreen.kt` 文件**末尾**（所有 Composable 之后）添加：

  ```kotlin
  private data class RecentRun(
      val type: String,
      val value: String,
      val time: String,
      val isPB: Boolean,
  )

  private val placeholderRecentRuns: List<RecentRun> = listOf(
      RecentRun("0-100 km/h", "4.58 s", "Today, 10:35", false),
      RecentRun("100-0 km/h", "38.2 m", "Today, 10:32", false),
      RecentRun("0-100 km/h", "4.21 s", "May 18, 2024", true),
  )

  private data class LapSessionRow(
      val date: String,
      val laps: Int,
      val bestLap: String,
  )

  private val placeholderLapSessions: List<LapSessionRow> = listOf(
      LapSessionRow("May 18, 2024", 4, "1:32.457"),
      LapSessionRow("May 12, 2024", 6, "1:33.884"),
      LapSessionRow("Apr 29, 2024", 5, "1:34.210"),
  )

  private data class CurrentTrackRecord(
      val trackName: String,
      val bestLapTime: String,
      val bestLapDate: String,
      val length: String,
      val direction: String,
      val sessions: Int,
      val totalLaps: Int,
  )

  private val placeholderTrackRecord = CurrentTrackRecord(
      trackName = "Shanghai Tianma",
      bestLapTime = "1:32.457",
      bestLapDate = "May 18, 2024",
      length = "3.063 km",
      direction = "Clockwise",
      sessions = 8,
      totalLaps = 56,
  )
  ```

- [x] 1.2 编译验证：`./gradlew :feature:test:compileDebugKotlin`（此时数据类未被使用，应只 warning，不 error）

---

## 2. PerformanceView 拆分 + Speed Curve Canvas + 3 条 Recent Runs

- [x] 2.1 **新建 `@Composable private fun PerformanceView(context: android.content.Context)`**（参数留 context 用于 Toast）：

  内部结构（自上而下）：

  - **3 metric tile Row**：
    ```
    BEST 0-100 (4.21, s, Purple)
    BEST BRAKE (36.8, m, Red)
    TOTAL RUNS (24, null, Cyan)
    ```
    与 baseline 的 BEST 100-0 / RUNS 命名差异：本 round 改为 BEST BRAKE / TOTAL RUNS（与渲染图一致）

  - **SPEED CURVE 卡片**：保留 `CutCornerPanel` 包装，标题 `"SPEED CURVE"` (UiTextLabel + Cyan) + 副标题 `"(0-100 km/h)"`（小字 TextMuted）；Canvas body 改造为：
    - 内边距 32.dp 给坐标轴标签
    - 横轴 6 tick (`0` `1` `2` `3` `4` `5` + `s`)，纵轴 4 tick (`0` `50` `100` `150` + `km/h`)
    - cyan 渐近曲线（`Path` + `quadraticBezierTo` 或 `cubicTo`，5 秒内到 ~100 km/h，10 秒到 ~150）
    - 100 km/h 处水平虚线（`PathEffect.dashPathEffect`） + 4.21 s 处垂直虚线
    - 交点画 cyan 圆点（半径 4.dp）
    - 圆点上方用 Compose `Box(Modifier.absoluteOffset(...))` 渲染圆角矩形气泡 + 文字 `"100 km/h"` + `"4.21 s"`（不在 Canvas 内 drawText，避免字体测量复杂）

  - **RECENT RUNS 列表**：section 标题 `"RECENT RUNS"`（UiTextLabel + Cyan）+ `placeholderRecentRuns.forEach { run -> ... }` 渲染 3 条 `TrackTechRow`：
    - `run.isPB == false` → leadingIcon 按 `run.type` 选 `Icons.Filled.Speed`（0-100）/ `Icons.Outlined.DoNotDisturbOn`（100-0）；title `run.type`；subtitle `"${run.value} · ${run.time}"`
    - `run.isPB == true` → leadingIcon `Icons.Filled.EmojiEvents`（trophy）；title `"PB ${run.type}"` 或保持 `run.type` 视 TrackTechRow accent 支持情况；subtitle `"${run.value} · ${run.time} · Personal Best"`
    - 全部 onClick → `Toast.makeText(context, "Run detail placeholder", Toast.LENGTH_SHORT).show()`

- [x] 2.2 **删除 baseline 的 `SpeedCurvePlaceholder` private 函数**（被 §2.1 内联到 PerformanceView 内的 SPEED CURVE 卡片替代；若 baseline 内 `SpeedCurvePlaceholder` 还有其他调用方先 grep 确认）

  ```bash
  grep -rn "SpeedCurvePlaceholder" /Users/wattledgnata/traeProjects/gps-app/feature --include="*.kt"
  ```

  预期：仅 `RecordsHomeScreen.kt` 自身命中 1 次（定义） + 1 次（baseline 调用）；删除定义 + 删除调用。

- [x] 2.3 编译验证：`./gradlew :feature:test:compileDebugKotlin`

---

## 3. LapsView 新建

- [x] 3.1 **新建 `@Composable private fun LapsView(context: android.content.Context)`**：

  内部结构（自上而下）：

  - **CURRENT TRACK RECORD 大 CutCornerPanel**：
    - 横向 Row 布局：左半 weight=1，右半 weight=1（或左右 60/40）
    - **左半 Column**：
      - `"CURRENT TRACK RECORD"` (UiTextLabel + Purple)
      - `placeholderTrackRecord.trackName`（`"Shanghai Tianma"`，RacingTitle 系列 + TextPrimary，足够大）
      - Spacer
      - `"BEST LAP"` (UiTextLabel + Cyan or Purple)
      - `placeholderTrackRecord.bestLapTime`（`"1:32.457"`，MetricMedium）
      - `placeholderTrackRecord.bestLapDate`（`"May 18, 2024"`，UiTextSmall + TextSecondary）
    - **右半 Box**：
      - 收藏星 icon `Icons.Filled.Star`（顶部右对齐，**不**包裹 clickable）
      - 居中 / 偏下 Canvas 绘制赛道简笔预览：
        - `Path` 起点 `(0.2 * size.width, 0.5 * size.height)`，4-5 段 `cubicTo` 形成不规则闭合环
        - `drawPath(path, color = TrackTechColors.Cyan, style = Stroke(width = 2.dp.toPx()))`
        - 起点画 cyan 小圆 `drawCircle(color = TrackTechColors.Cyan, radius = 3.dp.toPx(), center = startPoint)`

  - **Track 信息行 TrackTechRow**：
    - leadingIcon `Icons.Filled.LocationOn`
    - title `placeholderTrackRecord.trackName`
    - subtitle `"${placeholderTrackRecord.length} · ${placeholderTrackRecord.direction}"`（`"3.063 km · Clockwise"`）
    - onClick → `Toast.makeText(context, "Track detail coming next round", Toast.LENGTH_SHORT).show()`

  - **3 metric tile Row**：
    - `BEST LAP` (`placeholderTrackRecord.bestLapTime`，accent Purple，valueSize 视 MetricTile API 选 Medium)
    - `SESSIONS` (`placeholderTrackRecord.sessions.toString()`，accent Cyan)
    - `TOTAL LAPS` (`placeholderTrackRecord.totalLaps.toString()`，accent Cyan)

  - **SESSION HISTORY 列表**：
    - section 标题 `"SESSION HISTORY"`（UiTextLabel + Cyan or Purple）
    - `placeholderLapSessions.forEach { session -> ... }` 渲染 3 条 `TrackTechRow`：
      - title `"${session.date} · ${session.laps} Laps · Best ${session.bestLap}"`
      - subtitle null 或 `null`（看 TrackTechRow API 是否支持单行）；若需要 leadingIcon，用 `Icons.Filled.Flag` 或不加
      - onClick → `Toast.makeText(context, "Session detail placeholder", Toast.LENGTH_SHORT).show()`

- [x] 3.2 编译验证：`./gradlew :feature:test:compileDebugKotlin`

---

## 4. 标题栏 filter icon 占位

- [x] 4.1 **改造 `RecordsHomeScreen` 主 Column 内的 `Records` 标题渲染**：

  baseline 是单 `Text("Records", ...)`。改为 Row：

  ```kotlin
  Row(
      modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
  ) {
      Text(
          text = "Records",
          style = TrackTechTypography.RacingTitleLarge,
          color = TrackTechColors.TextPrimary,
      )
      Icon(
          imageVector = Icons.Filled.FilterAlt,
          contentDescription = "Filter",
          tint = TrackTechColors.TextSecondary,
          modifier = Modifier
              .size(24.dp)
              .clickable {
                  Toast.makeText(context, "Filter coming next round", Toast.LENGTH_SHORT).show()
              },
      )
  }
  ```

  注意点：
  - filter icon 位于主 `Column` 标题行内，**不在** `when (selectedSegment) { ... }` 分发分支内
  - `Modifier.clickable` 需 `import androidx.compose.foundation.clickable`（baseline 已有，复用）
  - `Icons.Filled.FilterAlt` 需新加 `import`

- [x] 4.2 编译验证

---

## 5. 主 Column body 用 when 分发

- [x] 5.1 **重写 `RecordsHomeScreen` 主 `Column` body**（保留外层 background / verticalScroll / padding / spacedBy 不变）：

  ```kotlin
  Column(
      modifier = modifier
          .fillMaxSize()
          .background(TrackTechColors.Background)
          .verticalScroll(rememberScrollState())
          .padding(vertical = 16.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
      // 标题 + filter icon (§4.1)
      RecordsTitleRow(context = context)

      // SegmentedControl 保留 baseline 不变
      SegmentedControl(
          options = listOf("PERFORMANCE", "LAPS"),
          selected = selectedSegment,
          onSelect = { selectedSegment = it },
          modifier = Modifier.padding(horizontal = 16.dp),
      )

      // 视图分发
      when (selectedSegment) {
          "PERFORMANCE" -> PerformanceView(context = context)
          "LAPS" -> LapsView(context = context)
      }

      Spacer(Modifier.height(16.dp))
  }
  ```

  其中 `RecordsTitleRow(context)` 是 §4.1 内嵌或抽出的标题 Composable。

- [x] 5.2 **删除 baseline 主 Column 内的所有 PERFORMANCE 内容**（baseline 行 68-133：3 metric tile Column + SpeedCurvePlaceholder 调用 + 5 条 RECENT RUNS Column），它们已被 §2.1 PerformanceView 内联替代。

- [x] 5.3 编译验证 + grep 自检：

  ```bash
  grep -n 'when (selectedSegment)\|"PERFORMANCE" ->\|"LAPS" ->' /Users/wattledgnata/traeProjects/gps-app/feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/RecordsHomeScreen.kt
  ```

  预期：3 个命中（when 表达式 + 两个分支）。

---

## 6. 编译/测试门槛

- [x] 6.1 `./gradlew :feature:test:compileDebugKotlin` BUILD SUCCESSFUL
- [x] 6.2 `./gradlew :app:compileDebugKotlin` BUILD SUCCESSFUL
- [x] 6.3 `./gradlew :feature:test:testDebugUnitTest` 全绿（现有测试零回归，特别 `TrackTechAppShellPagerTest` 与 `TabGatingPolicyTest`）
- [x] 6.4 `./gradlew :core:bluetooth:testDebugUnitTest :core:domain:test :core:data:testDebugUnitTest` 全绿（数据层零改动）
- [x] 6.5 **grep 自检**：

  ```bash
  # 数据类全 private
  grep -n "data class \(RecentRun\|LapSessionRow\|CurrentTrackRecord\)" /Users/wattledgnata/traeProjects/gps-app/feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/RecordsHomeScreen.kt

  # PERFORMANCE / LAPS 字面量命中
  grep -nE "\"BEST 0-100\"|\"BEST BRAKE\"|\"TOTAL RUNS\"|\"CURRENT TRACK RECORD\"|\"Shanghai Tianma\"|\"BEST LAP\"|\"SESSIONS\"|\"TOTAL LAPS\"|\"SESSION HISTORY\"|\"Personal Best\"|\"4.21\"|\"36.8\"|\"24\"|\"1:32.457\"|\"56\"|\"3.063 km\"|\"Clockwise\"" /Users/wattledgnata/traeProjects/gps-app/feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/RecordsHomeScreen.kt

  # 不接数据层
  grep -n "TestResultRepository\|TelemetryRepository\|TrackCatalog\|TestSessionViewModel" /Users/wattledgnata/traeProjects/gps-app/feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/RecordsHomeScreen.kt
  ```

  预期：
  - 3 个 `private data class` 命中
  - 17 个文本字面量分布在两个视图内
  - 数据层零命中

---

## 7. 真机视觉验证（manual gate）

- [x] 7.1 安装到真机：

  ```bash
  ANDROID_SERIAL=8KE0219522008434 ./gradlew :app:installDebug
  ```

  （华为 8KE0219522008434，项目默认真机；vivo V2405A 中途装机被设备权限弹窗拦截后改装华为；本轮含 BEST LAP / SESSIONS / TOTAL LAPS 三个 metric tile 等高修复 = `Row.height(IntrinsicSize.Max)` + 各 tile `Modifier.fillMaxHeight()`）

- [x] 7.2 验证清单：
  - **PERFORMANCE 视图**（默认选中）：
    - 标题栏右侧 filter icon 可见 + 点击弹 `"Filter coming next round"` Toast
    - 3 metric tile 横向等宽：BEST 0-100 4.21 s 紫 / BEST BRAKE 36.8 m 红 / TOTAL RUNS 24 cyan
    - SPEED CURVE 卡片 Canvas：cyan 曲线渐近 + 100 km/h 横虚线 + 4.21 s 竖虚线 + 交点圆点 + 标注气泡
    - RECENT RUNS 列表 3 条；第 3 条 trophy icon + Personal Best 副文（视觉与前 2 条有区分）
  - **LAPS 视图**（点 LAPS segment 切换）：
    - 切换瞬时（无动画过渡）
    - CURRENT TRACK RECORD 大卡片：左半 Shanghai Tianma + 1:32.457 + May 18, 2024；右半 cyan 赛道闭合曲线 stub + 收藏星 icon
    - Track 信息行：定位 icon + Shanghai Tianma + 3.063 km · Clockwise
    - 3 metric tile：BEST LAP 1:32.457 / SESSIONS 8 / TOTAL LAPS 56
    - SESSION HISTORY 列表 3 条：May 18, 2024 · 4 Laps · Best 1:32.457 等
  - **来回切换**：PERFORMANCE → LAPS → PERFORMANCE 多次，状态保留（segment 内本身无 scroll state，无明显回归点）

- [x] 7.3 视觉偏差点（如曲线弯度、字体粗细、色彩比例、CutCorner 切角大小、与渲染图的对比偏差）作为 follow-up backlog 记录到 commit message body，不在本 round 内修补。**真机签收时新发现的项目级视觉规则**：成绩/时间/记录型 metric 不应用 DSEG7 七段字体（七段仅用于仪表瞬时读数如 SPEED）—— 跨多文件，记录到 §9 follow-up backlog，下一 round 起 `differentiate-metric-typography-mechanical-vs-score` 全局处理。

---

## 8. Commit + 合流门槛

- [x] 8.1 **Spec 验证**：`openspec validate split-records-tab-performance-and-laps --strict` 返回 `Change ... is valid`

- [x] 8.2 **grep 自检**（最终汇总）：
  - `private fun PerformanceView` + `private fun LapsView` 各命中 1 次
  - `when (selectedSegment)` 命中 1 次
  - 3 个 `private data class` (`RecentRun` / `LapSessionRow` / `CurrentTrackRecord`)
  - PERFORMANCE 视图 6 个关键字面量 (`BEST 0-100` / `BEST BRAKE` / `TOTAL RUNS` / `Personal Best` / `4.21` / `36.8`)
  - LAPS 视图 8 个关键字面量 (`CURRENT TRACK RECORD` / `Shanghai Tianma` / `BEST LAP` / `SESSIONS` / `TOTAL LAPS` / `SESSION HISTORY` / `1:32.457` / `Clockwise`)
  - filter icon 命中：`Icons.Filled.FilterAlt` + `Filter coming next round` Toast 文案
  - 数据层零命中：`TestResultRepository` / `TelemetryRepository` / `TrackCatalog` / `TestSessionViewModel`

- [x] 8.3 **下游零回归**：
  - `:core:bluetooth:testDebugUnitTest` ✅
  - `:core:domain:test` ✅
  - `:core:data:testDebugUnitTest` ✅
  - `:app:compileDebugKotlin` ✅
  - `:feature:test:testDebugUnitTest` 全绿（含 `TrackTechAppShellPagerTest` / `TabGatingPolicyTest` / 既有测试零回归）

- [x] 8.4 **真机验证**已完成（§7.2 验证清单全过；BEST LAP / SESSIONS / TOTAL LAPS 三 tile 等高问题已修复并复测；七段字体跨页面规则反馈作为 follow-up 记录在 §9）

- [ ] 8.5 **commit**：`feat(ui): Records tab 拆分为 PERFORMANCE / LAPS 两个真实视图 · 占位数据`

  body 要点：
  - **records-home-segmented-views capability 新建**：Segmented control 真实视图分发（baseline 是空切换 / 同一份内容）；PERFORMANCE 视图（3 metric tile + SPEED CURVE Canvas stub + 3 条 RECENT RUNS 含 PB 高亮）；LAPS 视图（CURRENT TRACK RECORD 大卡含赛道 cyan 简笔预览 stub + Track 信息行 + 3 metric tile + 3 条 SESSION HISTORY）
  - **filter icon 占位**：标题栏右侧加 `Icons.Filled.FilterAlt`，点击 Toast `"Filter coming next round"`；两个视图都显示
  - **数据全占位**：3 个 `private data class` (`RecentRun` / `LapSessionRow` / `CurrentTrackRecord`) + 3 个 `private val placeholder*` 集中在 `RecordsHomeScreen.kt` 文件末尾
  - **零改动**：`core/*` / `simulator/*` / 4 个 home screen 之外的 tracktech 文件 / `TestSessionViewModel` / `TestResultRepository` / BLE 链路 / RaceChrono BLE 协议 / `SegmentedControl` 视觉 / `MetricTile` / `TrackTechRow` / `CutCornerPanel` API
  - **测试**：本 round 不新增单元测试（纯 UI placeholder，外部测试无可观察契约）；现有 `:feature:test:testDebugUnitTest` 全套零回归
  - **真机验证**：vivo V2405A · 7.2 验证清单 PERFORMANCE / LAPS / 切换 / filter icon 全部通过
  - **合流门槛**：openspec validate --strict ✅ / grep 自检全部通过 ✅

  格式约束：
  - Conventional Commits
  - body 含 capability 名 + 受影响文件清单（仅 `RecordsHomeScreen.kt`）+ 真机验证状态
  - Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>

---

## 9. Post-apply follow-up backlog（不在本 change scope，记录到 commit message）

- 真接 `TestResultRepository.testResultsFlow` 渲染真实 RECENT RUNS 列表 + PERFORMANCE 顶部 metric 真实统计 —— 独立 round
- 真实 lap session 记录持久化 + LAPS 视图真接历史数据 —— 独立 round（依赖 A56 后续）
- SPEED CURVE 真接历史 0-100 加速曲线数据（替换 Canvas stub）—— 独立 round
- 赛道几何真渲染（替换 stub 闭合曲线，接 PresetTrackCatalog 坐标）—— 独立 round
- filter UI 设计 + 实现（按时间 / 按测试类型 / 按赛道）—— 独立 round
- CURRENT TRACK RECORD 卡的 ⭐ 收藏功能 + 持久化 —— 独立 round
- 列表项点击进详情页（run detail / session detail）—— 独立 round
- PerformanceView / LapsView 视觉与渲染图对比偏差精细化（曲线弯度、字体粗细、色彩比例、CutCorner 切角大小）—— 独立 round
- **`differentiate-metric-typography-mechanical-vs-score`**（项目级视觉规则修订，**优先级最高**）：拆分 `TrackTechTypography.MetricHero/Medium/Small`（DSEG7 七段）为 Mechanical（仪表瞬时读数 = SPEED / SATS / RATE）+ Score（成绩记录型 = LAP TIME / 0-100 时间 / BEST BRAKE 距离 / 计数）两套；`MetricNumber` 加 `kind` 参数；批量更新 `TestHomeScreen` LATEST RESULT、`TrackTechTestExecutionScreen` ELAPSED TIME、`RecordsHomeScreen` 全部成绩/记录型 MetricTile 切到 Score；保留 SPEED hero / DeviceHomeScreen Quick Status Row 用 Mechanical（七段）
