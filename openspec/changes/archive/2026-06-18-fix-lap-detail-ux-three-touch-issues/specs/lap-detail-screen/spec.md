## ADDED Requirements

### Requirement: 图表 cursor 横向拖动方向锁定，垂直方向让 LazyColumn 接收

`SpeedTimeChart`（`feature/test/.../ui/components/SpeedTimeChart.kt`）与 `AccelTimeChart`（`feature/test/.../ui/components/AccelTimeChart.kt`）的 cursor 拖动 SHALL 使用 `detectHorizontalDragGestures`，使垂直方向手势不被消费 → 父级 `LazyColumn` 接管纵向滚动。

实现 MUST 满足：

1. **手势 API**：MUST 使用 `androidx.compose.foundation.gestures.detectHorizontalDragGestures`；MUST NOT 使用 `detectDragGestures`（全向消费）。
2. **消费策略**：水平方向触发的 `onDrag` 回调内 MUST `change.consume()`；垂直方向不进入回调，自然不消费 PointerEvent。
3. **单击 detectTapGestures 保留**：图表内 `detectTapGestures` 单击取游标的 pointerInput 不动；与 `detectHorizontalDragGestures` 共存由 Compose 内部 touch slop 优先级处理。
4. **FileLogger 埋点**：水平拖动首次触发（onDragStart 等价时机）MUST 埋一条 `FileLogger.v("Chart", "horizDrag start chart=<Speed|Accel>")`，便于 road-test-first 模式下排查"手势进入但 cursor 不动"的问题。

#### Scenario: 水平方向拖动触发 cursor 移动 + 垂直方向被 LazyColumn 接收

- **GIVEN** 用户进入 `LapDetailScreen`，VIDEO/SPEED/ACCEL 三 panel 已渲染，手指落在 `SpeedTimeChart` 区域
- **WHEN** 用户水平方向拖动手指（dx > horizontal touch slop, dy < slop）
- **THEN** `detectHorizontalDragGestures` 触发 onDrag 回调 → `cursorAbsoluteTs` 同步移动 → 4 组件高亮更新
- **AND** LazyColumn 不发生上下滚动（垂直 dy 极小不触发 LazyColumn 滚动阈值）

#### Scenario: 垂直方向拖动让 LazyColumn 滚动，cursor 不动

- **GIVEN** 用户进入 `LapDetailScreen`，手指落在 `SpeedTimeChart` 区域
- **WHEN** 用户垂直方向拖动手指（dy > slop, dx < slop）
- **THEN** `detectHorizontalDragGestures` 不触发 onDrag 回调（不消费 PointerEvent）→ `cursorAbsoluteTs` 不变
- **AND** 父级 LazyColumn 接收垂直 PointerEvent → 上下滚动到 SECTORS / TRACK panel 可见
- **AND** SpeedTimeChart cursor 高亮位置保持不变

#### Scenario: 反例——MUST NOT 使用 `detectDragGestures` 全向消费

- **GIVEN** `SpeedTimeChart.kt` 与 `AccelTimeChart.kt` 内 cursor 拖动 pointerInput 代码
- **WHEN** contract test 扫描两文件源
- **THEN** MUST NOT 出现 `detectDragGestures { change, _ -> change.consume()` 模式
- **AND** MUST 出现 `detectHorizontalDragGestures` 各一次（grep gate）
- **AND** 若实现回退到 `detectDragGestures` 全向消费，contract test fail，锁死方向锁定语义

### Requirement: 视频面板加载完成后 LapDetailScreen 自动 scroll 锚定 VIDEO panel 到可见区顶部

`LapDetailScreen` SHALL 在 `videoEligible` 由 false 翻 true（视频数据 ready）时调用一次 `listState.animateScrollToItem(panelOrder.indexOf(VIDEO))`，确保用户进屏第一眼即看到视频面板。

实现 MUST 满足：

1. **listState hoisting**：MUST 在 `LapDetailScreen` 顶层加 `val listState = rememberLazyListState()`；LazyColumn 入参 MUST 显式传 `state = listState`。
2. **触发时机**：MUST 用 `LaunchedEffect(videoEligible) { if (videoEligible) listState.animateScrollToItem(...) }`，仅 videoEligible 由 false→true 跳变时执行一次；用户主动滚走 VIDEO 后再次进入屏才会重新触发（重新组合）。
3. **target index 容错**：`panelOrder.indexOf(LapDetailPanelId.VIDEO)` 返回 -1（VIDEO 不在 panelOrder）时 MUST `coerceAtLeast(0)` 兜底；实际情况下 visiblePanels filter（line 188）保证 videoEligible=true 时 VIDEO 在列表中。
4. **不强拽回顶**：videoEligible 已 true 后用户主动滚走 VIDEO，MUST NOT 二次触发 scroll 回去（LaunchedEffect 仅 false→true 触发一次，true 期间重组不重跑）。
5. **FileLogger 埋点**：scroll 锚定触发时 MUST 埋 `FileLogger.d("LapDetail", "video panel anchor scrollToItem idx=...")`。

#### Scenario: 进屏视频数据 ready 后自动 scroll 锚定 VIDEO

- **GIVEN** 用户进入 `LapDetailScreen(sessionId=S1, lapIndex=0)`，该 session 有视频
- **WHEN** `LaunchedEffect(sessionId, lapIndex)` 异步加载 `LapPlaybackLoader.load` 返回非 null，`videoFilePath` / `videoPlaybackContext` 被置入 state，`videoEligible` 由 false 翻 true
- **THEN** `LaunchedEffect(videoEligible)` 调 `listState.animateScrollToItem(panelOrder.indexOf(VIDEO).coerceAtLeast(0))`
- **AND** LazyColumn 可见区顶部为 VIDEO panel
- **AND** `FileLogger.d` 记录 anchor 触发 + idx

#### Scenario: 用户主动滚走 VIDEO 后不强拽回顶

- **GIVEN** 用户进入 `LapDetailScreen`，VIDEO panel 已 anchor 到可见区顶部
- **WHEN** 用户主动向下滚动 LazyColumn 到 SECTORS panel 位置
- **THEN** LazyColumn 滚到 SECTORS panel；VIDEO 已滚出可见区
- **AND** `LaunchedEffect(videoEligible)` 不重新触发（videoEligible 仍是 true 未跳变）
- **AND** LazyColumn scroll 位置保持用户当前滚动结果，MUST NOT 强拽回 VIDEO

#### Scenario: 反例——无视频 session 进屏 MUST NOT 触发 scroll 锚定

- **GIVEN** 用户进入 `LapDetailScreen(sessionId=S2, lapIndex=0)`，该 session `videoFilePath == null`（`LapPlaybackLoader.load` 返回 null）
- **WHEN** `videoEligible` 始终为 false
- **THEN** `LaunchedEffect(videoEligible)` 内 `if (videoEligible)` 分支不执行
- **AND** LazyColumn 默认 scroll 位置（从 list[0] 开始，但 VIDEO 不在 visiblePanels）
- **AND** 不应崩溃、不应触发空 scroll
