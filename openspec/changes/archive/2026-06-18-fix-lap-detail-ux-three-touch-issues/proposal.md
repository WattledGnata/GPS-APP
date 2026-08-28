## Why

单圈详情屏与全屏视频回放屏当前存在三处基础手势/可见性 UX bug，影响用户阅读单圈数据 + 控制全屏回放：

1. **图表内 cursor 横向拖动与 LazyColumn 上下滚动抢手势**：`SpeedTimeChart` / `AccelTimeChart` 用 `detectDragGestures { change.consume() }` 无方向判定全消费，手指落在图表上后即便沿垂直方向滑也被吃掉，LazyColumn 无法上下滚动。用户读图后想往下翻看 SECTORS / TRACK 时必须避开两张图表，体验割裂。
2. **视频面板默认在面板列表第 0 位，但进屏第一眼看不到它**：`videoFilePath` / `videoPlaybackContext` 由 `LaunchedEffect` 异步加载，VIDEO panel 在数据 ready 后才插入 `visiblePanels` 头部。LazyColumn 默认"新 item 插到 firstVisibleItemIndex 之前时保持当前 item 视位"→ VIDEO 被顶到不可见的上方，用户进屏第一眼看到的是 list[1] 而非视频面板。
3. **全屏 `LapVideoPlaybackScreen` 没有用户暂停手段**：圈时间轴主导状态机在覆盖段内主动 `exoPlayer.play()` 唤醒，没有任何中央播放/暂停按钮，也没有双击屏幕切换播放/暂停。用户想停下来仔细看某一帧只能拖底部 Slider，操作成本高且无法稳定停顿。

## What Changes

- **图表手势方向锁定**：`SpeedTimeChart` 与 `AccelTimeChart` 的 cursor 拖动从 `detectDragGestures` 改为 `detectHorizontalDragGestures`（Compose 标准 API，内置 horizontal touch slop 判定）。垂直方向移动不消费 → 父级 LazyColumn 接管纵向滚动；水平方向触发 cursor 移动。`detectTapGestures` 单击取游标不变。
- **LazyColumn 视频面板锚定**：`LapDetailScreen` LazyColumn 加 `rememberLazyListState()`。`LaunchedEffect(videoEligible)` 在 videoEligible 由 false 翻 true 时调一次 `listState.animateScrollToItem(panelOrder.indexOf(VIDEO))`，确保用户进屏视频面板出现在可见区顶部。已经处于可见区时（用户已 reorder 过 VIDEO 到顶部位置后再次进入）此次 scroll 为 no-op；用户主动滚走 VIDEO 后状态机已不再触发（仅 false→true 跳变触发一次）。
- **全屏播放屏中央播放/暂停按钮 + 双击切换**：`LapVideoPlaybackScreen` 加 `var userPaused by remember { mutableStateOf(false) }` state。状态机循环开头判 userPaused：true 时 `exoPlayer.pause()` 且 skip playhead 推进（保持 playhead 不漂移）。Box 中央叠加 `IconButton` 仅在 `userPaused == true` 时显示 PlayArrow 图标（轻侵入）。Box 顶层加 `Modifier.pointerInput { detectTapGestures(onDoubleTap = { userPaused = !userPaused }) }`，双击屏幕任意位置切换暂停态。

## Capabilities

### New Capabilities
（无）

### Modified Capabilities
- `lap-detail-screen`：新增"图表 cursor 拖动方向锁定"requirement + "视频面板进屏锚定"requirement；不改既有 4 组件渲染 / 共享游标 / 降级态 requirement
- `video-overlay-playback`：新增"全屏播放屏用户暂停控件"requirement（中央按钮 + 双击切换 + 状态机暂停 hook）；不改既有入口 / 导航 / 状态机段感知行为

## Impact

**代码（约 ~70 行）**：
- `feature/test/src/main/java/com/blazepush/feature/test/ui/components/SpeedTimeChart.kt:152` — `detectDragGestures` → `detectHorizontalDragGestures`
- `feature/test/src/main/java/com/blazepush/feature/test/ui/components/AccelTimeChart.kt:57` — 同上
- `feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/LapDetailScreen.kt:196` — 加 `rememberLazyListState` + `LaunchedEffect(videoEligible)` scroll 锚定
- `feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/LapVideoPlaybackScreen.kt:357-457`（状态机循环）+ 460-528（Box overlay）— 加 `userPaused` state、状态机循环开头判 userPaused、中央 IconButton（仅 paused 态可见）、Box 顶层双击 detectTapGestures

**测试**：
- `SpeedTimeChart` / `AccelTimeChart` 现有单测无需改（pointerInput 行为难单测，由真机验证兜底）
- `LapDetailScreen` / `LapVideoPlaybackScreen` 现有契约测试覆盖手势 / scroll / userPaused 的可能性低，添加 contract test 锁定 grep 锚点（`detectHorizontalDragGestures` 出现 1 次 + `rememberLazyListState` 出现 1 次 + `userPaused` state 与 onDoubleTap 各出现 1 次）

**协议 / Schema / 数据流**：均不变（无公共协议改、无 Room schema 改、无 BLE 协议改）。

**真机验证 gate**（road-test-first 模式 + 加速通道）：
- Bug 1：图表内水平方向拖动 cursor 流畅 + 垂直方向 LazyColumn 正常滚动（验证默认设备 vivo V2405A 小屏 + 华为 8KE0219522008434）
- Bug 2：每次进单圈详情屏，VIDEO panel 出现在可见区顶部（含视频数据加载有延迟的场景）
- Bug 3：全屏播放屏双击屏幕中央 → 视频停（按钮出现）；再双击 → 视频播（按钮消失）；中央按钮点击切换播放状态
