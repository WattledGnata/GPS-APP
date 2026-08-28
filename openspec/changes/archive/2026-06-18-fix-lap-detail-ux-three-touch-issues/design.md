## Context

**当前状态**（2026-06-17）：
- `LapDetailScreen.kt`（feature/test）已落地 LazyColumn + 长按拖拽 reorder 面板布局（含 VIDEO/OVERVIEW/SPEED/ACCEL/SECTORS/TRACK 六类 panel），共享游标 `cursorAbsoluteTs` 在 SpeedTimeChart/AccelTimeChart/SectorBar/TrackPolylineMap 四组件之间联动。VIDEO panel 由 `LapPlaybackLoader.load` 异步置入 `visiblePanels`（`videoEligible` 条件渲染）。
- `LapVideoPlaybackScreen.kt`（feature/test）已落地圈时间轴主导状态机：覆盖段内视频驱动 playhead，覆盖段外黑屏 ticker 推进；段切换 `seekTo` + `play`。`PlayerView(useController = false)` 不暴露 ExoPlayer 控制条；只有底部 Slider 进度条 + 顶部 ExportButton。
- 图表组件 `SpeedTimeChart.kt:152` / `AccelTimeChart.kt:57` 当前用 `detectDragGestures { change.consume() }`，**全向消费**。
- `LapDetailScreen.kt:196` LazyColumn **未 hoist listState**，初始 firstVisibleItemIndex 由 Compose 默认行为决定。

**约束**：
- CLAUDE.md V2 视觉约束（仪表数字仅 DSEG7、其余 Score；metric/row/label Text 严格单行 + Ellipsis）保留
- M2 教训：异步加载分支 null→content MUST 用 if/else，禁 `return@Column` early-return
- 公共协议（RaceChrono BLE）/ Room schema 不在本 round 改动范围
- 加速通道（small 复杂度）：0 子 agent + Codex 单线兜底；本次 user 进一步授权"跳过 Codex"走 road-test-first，真机次日补

**stakeholders**：用户（单一）；CC 主会话 Opus 起草工件 + 实施代码。

## Goals / Non-Goals

**Goals:**

- 三处 UX bug 全闭环：图表方向手势分离 / 视频面板进屏锚定 / 全屏视频用户暂停（中央按钮 + 双击）
- 不破坏既有"共享游标 single source of truth"语义
- 不破坏既有"圈时间轴主导状态机"播放语义（userPaused 是叠加层，不替换状态机）
- 保留 V2 视觉约束（中央 IconButton 用 Material IconButton + Icons.Filled.PlayArrow 标准款；仅暂停态可见，最轻侵入画面）

**Non-Goals:**

- 不引入 `BasicText.autoSize` / 字号自适应（V2 视觉 MUST NOT）
- 不动 SectorBar / TrackPolylineMap 的手势（这两个组件目前是消费者不发起 cursor 变更，不在本 round 修改范围）
- 不加单击切换播放/暂停（保留单击给图表 detectTapGestures 取游标 + 全屏屏单击预留未来"显示/隐藏控件"扩展）
- 不重做全屏播放屏的 Slider / ExportButton / OverlayHud 布局
- 不动 `LapVideoPanel`（嵌入版视频面板，已有底部 Play/Pause 按钮）

## Decisions

### Decision 1：图表方向锁定用 `detectHorizontalDragGestures`，不用自实现 axis-lock

**选择**：将 `SpeedTimeChart.kt:152` 和 `AccelTimeChart.kt:57` 的 `detectDragGestures { change, _ -> change.consume(); ... }` 改为 `detectHorizontalDragGestures { change, _ -> change.consume(); ... }`。

**Rationale**：Compose foundation 内置 `detectHorizontalDragGestures` 自动用 horizontal touch slop 判定主方向。垂直方向移动**不触发** onDrag 回调 → 自然不消费 PointerEvent → 父级 LazyColumn 接收。

**Alternatives 考虑**：

- (A) 自实现 `awaitPointerEventScope { ... awaitFirstDown() ... 累积 dx/dy ... 比较绝对值判方向 ... }`：可控但代码量大（每个 chart ~20 行），且需要正确处理 `change.consume()` 时机 + multi-touch + cancellation 边界，引入新 bug 面更大。**拒绝**：标准 API 已经覆盖该场景。
- (B) 改 LazyColumn 用 `Modifier.nestedScroll`：父子双向协商，从 LazyColumn 一侧拦截。**拒绝**：nestedScroll 用于父子滚动联动（如折叠头），不解决"图表内拖游标抢手势"问题；且改动面波及 LazyColumn 与所有 child 之间的协议，违反"避免污染现有代码"边界。

### Decision 2：LazyColumn 视频面板锚定仅在 `videoEligible` 由 false→true 跳变时触发一次

**选择**：在 `LapDetailScreen.kt` 加：

```kotlin
val listState = rememberLazyListState()
LaunchedEffect(videoEligible) {
    if (videoEligible) {
        val idx = panelOrder.indexOf(LapDetailPanelId.VIDEO).coerceAtLeast(0)
        listState.animateScrollToItem(idx)
    }
}
LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), ...) { ... }
```

**Rationale**：bug 根因是"VIDEO panel 异步插入 visiblePanels 头部时被 LazyColumn 默认行为顶到不可见"，**只在 videoEligible 跳变那一刻触发 scroll** 即可修复（panelOrder 已含 VIDEO 在 list[0]，indexOf 返回 0）。`LaunchedEffect(videoEligible)` 在 false→true 时执行一次；进圈后用户主动滚走 VIDEO 不会再触发（key 已稳定）。

**Alternatives 考虑**：

- (A) 进屏永久 `listState.scrollToItem(0)`（无视 videoEligible 状态）：会在用户主动滚走 VIDEO 后下次重组也强拽回顶部 → 用户体验更差。**拒绝**。
- (B) 改默认 panel order 把 VIDEO 固定第 0 位 + 启动 scrollOffset = 0：依然不能解决"异步插入后被顶上方"的根因（panelOrder 已经是 VIDEO 在第 0 位）。**拒绝**。
- (C) 用 `Modifier.snapToBoundsOnGloballyPositioned` 等无 listState 方案：Compose 不存在此 API，必须用 LazyListState。

### Decision 3：全屏播放屏用 `userPaused` flag 叠加状态机，中央按钮仅暂停态可见，双击切换

**选择**：

```kotlin
// LapVideoPlaybackScreen.kt 状态机入口（while (isActive) 内）
if (userPaused) {
    if (exoPlayer.isPlaying) exoPlayer.pause()
    playheadUiWallClock = playheadWallClock  // 不推进 playhead 不消费 seek
    delay(PLAYHEAD_TICK_MS)
    continue
}
// ... 既有 segIdxNow / withinCoverage / 段感知逻辑不变 ...

// Box 顶层（取代既有 Box 根 Modifier 或叠加 pointerInput）
Box(
    modifier = Modifier
        .fillMaxSize()
        .background(Color.Black)
        .pointerInput(Unit) {
            detectTapGestures(onDoubleTap = { userPaused = !userPaused })
        },
) { ... }

// 中央 IconButton（在三态分支 content 内、Slider 之后或之前）
if (userPaused) {
    IconButton(
        onClick = { userPaused = false },
        modifier = Modifier
            .align(Alignment.Center)
            .size(80.dp)
            .background(Color.Black.copy(alpha = 0.4f), shape = CircleShape),
    ) {
        Icon(
            imageVector = Icons.Filled.PlayArrow,
            contentDescription = "play",
            tint = Color.White,
            modifier = Modifier.size(56.dp),
        )
    }
}
```

**Rationale**：

- `userPaused` 是叠加层而非替换状态机：状态机循环每 tick 只在循环开头加 1 行判断，既有覆盖段内/覆盖段外/段切换/atEnd 停驻语义全保留。
- 暂停时**不推进 playhead**（不写 `playheadWallClock = playheadFromPlayer()` 也不黑屏 ticker advance）→ 用户暂停后画面冻结在当前帧，恢复播放从同一时间点继续。
- 暂停时**不消费 seekRequest**：保留进度条拖动响应（用户暂停态下仍可拖 Slider 切位置，恢复播放从拖到的位置开始）— 此点在 reverify 时实测确认。
- 暂停时**不更新 overlay**：overlay 数据按 `lastIdx` 缓存，画面冻结时 overlay 也冻结，符合用户预期。
- 中央按钮仅 paused 可见 = 最轻侵入：播放中无任何视觉干扰；暂停时给用户明确的"点这里继续"提示。
- 双击全屏（不只是按钮中心）：标准视频播放器交互，且不和单击发生冲突（detectTapGestures 内部双击/单击优先级由 viewConfiguration.doubleTapTimeoutMillis 区分）。
- onDoubleTap 与 IconButton.onClick 通过 zIndex / Modifier order 隔离：IconButton 自己拦截 click 不冒泡到 detectTapGestures，所以中央按钮区单击是 IconButton.onClick，其余区域双击是 detectTapGestures.onDoubleTap。

**Alternatives 考虑**：

- (A) 让用户暂停直接调 `exoPlayer.pause()` 不加 flag：状态机覆盖段内 line 398-400 会立刻 `exoPlayer.play()` 拉回 → 暂停立刻被破坏。**拒绝**。
- (B) 状态机退出循环挂起：要求重新进入循环时正确恢复 playhead/段索引/atEnd 状态，复杂度高且容易引入边界 bug。**拒绝**：flag-skip-tick 是最小改动。
- (C) 中央按钮常驻可见（半透明背景）：每帧叠加视觉，违反"最轻侵入" UX 倾向；也跟 user 选择"中央按钮仅暂停态显示"冲突。**拒绝**。
- (D) 单击切换播放/暂停（替代双击）：会和图表 / 进度条 / 导出按钮的单击行为冲突；标准视频播放器单击多用于切换控件可见性。**拒绝**。

### Decision 4：spec 用 ADDED Requirements 不动既有 MODIFIED

**选择**：本 round 在 `lap-detail-screen` 与 `video-overlay-playback` 两个 capability spec 各加一个 ADDED Requirements 区块（**不** MODIFY 既有 requirement）。

**Rationale**：本 round 新增"手势方向锁定" / "进屏锚定" / "用户暂停控件"是**叠加新行为**，不改既有"加载渲染 / 共享游标 / 状态机段感知"行为。MODIFIED 操作要求复制整段 requirement 重写，容易引入 drift（v3 #4 行号锚点漂移 + v3 #15 memo 与工件不同步盲点）。ADDED 操作更窄、与既有 spec 无 conflict。

## Risks / Trade-offs

| Risk | Mitigation |
|---|---|
| `detectHorizontalDragGestures` 与 `detectTapGestures` 同时挂 chart 时，单击取游标 vs 水平滑取游标的判定先后可能冲突 | Compose 内部 horizontal touch slop > tap slop，单击在 slop 阈值内触发 tap，超过阈值触发 horizontal drag。本 round 不改两个 modifier 的挂载顺序（保持 `.pointerInput { detectDragGestures... }.pointerInput { detectTapGestures... }`）。真机验证：单击图表能取游标 + 水平拖动 cursor 流畅 |
| LazyColumn `animateScrollToItem(0)` 在 panelOrder 配置异常（VIDEO 不在 panelOrder 中）时 indexOf 返回 -1 | `coerceAtLeast(0)` 兜底；且 `videoEligible` 为 true 隐含 VIDEO 在 visiblePanels（由 line 188 的 filter 保证），实际不会触发 -1 路径 |
| `userPaused = true` 时进度条拖动是否生效未在状态机循环外路径覆盖 | 状态机循环开头判 userPaused 后 `continue` skip 剩余逻辑，但 `seekRequestWallClock` 写入由 Slider.onValueChange 直接 set → 写入路径不依赖循环；恢复播放时（userPaused=false）下一 tick 进入 line 361 的 seek 消费分支处理积压 seek。**真机验证 gate**：暂停态下拖 Slider → 画面冻结在拖到的目标位置 + 双击恢复播放从该位置继续 |
| `onDoubleTap` 与 IconButton.onClick 优先级在 Compose 默认实现中靠 zIndex + pointerEvent 路由，少数 device 可能出现双击落在按钮中心后被吃成 IconButton 单击 | 中央按钮显式 `size(80.dp)` + `.align(Alignment.Center)`，双击在按钮中心也会被 IconButton.onClick 拦截但效果都是切换 userPaused → 用户感知无差异（双击中心按钮也成功切换）|
| road-test-first 模式跳 Codex review → 实施期 / spec 内潜在缺陷只能靠真机攒批兜底 | (1) CC 主会话 §A 自审一遍（设计骨架 + scope 假闭环 + 决策最优性） (2) FileLogger 埋点：图表方向判定首次触发 / scroll 锚定触发 / userPaused 切换 / 状态机 skip tick 各埋 `FileLogger.d` 关键节点（road-test-first 兜底） (3) 真机由 user 明早补 |

## Migration Plan

- 本 round 改动均在 feature/test module 内 UI 层，无 schema migration / 无协议变更 / 无 DI 改动
- 部署：apply 后 gradle 编译 → 装 apk 到默认真机（华为 8KE0219522008434 + vivo V2405A 小屏验证 V2 视觉约束）
- 回滚：单 commit 直接 `git revert` 即可；UI 层无副作用 / 无状态污染

## Open Questions

无。三个 bug 修复路径明确，user 已就以下关键选择拍板：

- Bug 1 横向滑动指图表内 cursor 拖动（已答）
- Bug 2 进屏 scrollToItem 锚定（已答）
- Bug 3 中央按钮仅暂停态可见 + 双击全屏切换（已答）
- 推进方式 OpenSpec 加速通道 + 跳 Codex + 真机次日补（已授权）
