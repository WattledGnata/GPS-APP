## 1. 锚点 verify（apply 前 grep verify 工件路径 / 行号 / DSL 对齐 — v3 #3 自查）

- [x] 1.1 grep `detectDragGestures { change, _ ->` 在 `SpeedTimeChart.kt` 与 `AccelTimeChart.kt` 各命中一次（baseline），命中位置 `SpeedTimeChart.kt:152-167` + `AccelTimeChart.kt:57-71` 与 design Decision 1 一致
- [x] 1.2 grep `pointerInput(samples)` 在两 chart 中各命中两次（detectDragGestures + detectTapGestures 两块），结构与 design Decision 1 描述一致
- [x] 1.3 grep `LazyColumn(` 在 `LapDetailScreen.kt` 命中一次（line 196），grep `rememberLazyListState` 命中零次（baseline，本 round 待新增）
- [x] 1.4 grep `LaunchedEffect(videoEligible)` 在 `LapDetailScreen.kt` 命中零次（baseline，本 round 待新增）
- [x] 1.5 grep `useController = false` 在 `LapVideoPlaybackScreen.kt:478` 命中一次（baseline 保留）
- [x] 1.6 grep `userPaused` 在 `LapVideoPlaybackScreen.kt` 命中零次（baseline，本 round 待新增）
- [x] 1.7 grep `while (isActive)` 在 `LapVideoPlaybackScreen.kt:357` 命中一次（状态机循环入口），实际状态机循环开头位置确认（在 line 358 `val nowRealtime = ...` 之前会插入 `if (userPaused)` 分支）

## 2. Bug 1 — 图表 cursor 横向拖动方向锁定

- [x] 2.1 `feature/test/src/main/java/com/blazepush/feature/test/ui/components/SpeedTimeChart.kt:5` — 删除 `import androidx.compose.foundation.gestures.detectDragGestures`，新增 `import androidx.compose.foundation.gestures.detectHorizontalDragGestures`
- [x] 2.2 `SpeedTimeChart.kt:153` — `detectDragGestures { change, _ ->` 改为 `detectHorizontalDragGestures { change, _ ->`；onDrag 回调内 `change.consume()` 与 cursor 计算逻辑（line 156-166）保留不动
- [x] 2.3 `SpeedTimeChart.kt` — onDragStart 回调埋 `FileLogger.v("Chart", "horizDrag start chart=Speed")`（detectHorizontalDragGestures 已自带 onDragStart 入参，比首次 flag 更准确）
- [x] 2.4 `AccelTimeChart.kt:4` — 同上 import 替换
- [x] 2.5 `AccelTimeChart.kt:58` — `detectDragGestures { change, _ ->` 改为 `detectHorizontalDragGestures { change, _ ->`；保留 `change.consume()` 与 cursor 计算
- [x] 2.6 `AccelTimeChart.kt` — 同 SpeedTimeChart 在 onDragStart 加 `FileLogger.v("Chart", "horizDrag start chart=Accel")` 埋点

## 3. Bug 2 — 视频面板加载即时占位（二轮 fix）

> 一轮（已撤销）：scrollToItem 锚定方案。真机验证 user 反馈"先看到非 VIDEO 再滑过去"——
> 根因是 LapPlaybackLoader.load 异步 + VIDEO 在数据 ready 才插入。改用"占位先到位"方案：
> 进屏立即用乐观假设让 VIDEO 占据 list[0]，加载完无缝替换内容。彻底无滑动、无视觉跳跃。

- [x] 3.1 `LapDetailScreen.kt` — 加 `var sessionHasVideo by remember { mutableStateOf<Boolean?>(null) }` 三态 state（null=待判定乐观假设/true=有视频/false=无视频）+ `var videoCtxLoadFailed by remember { mutableStateOf(false) }`
- [x] 3.2 `LapDetailScreen.kt` LaunchedEffect — 进 LaunchedEffect 立即快查 `telemetryRepository.getSession(sessionId)?.videoFilePath` 判定 sessionHasVideo（Room 单表 select ~ms）；sessionHasVideo=true 才走 LapPlaybackLoader.load；失败 set videoCtxLoadFailed
- [x] 3.3 `LapDetailScreen.kt` visiblePanels filter — 改用 `it != LapDetailPanelId.VIDEO || (sessionHasVideo != false)`（null/true 都让 VIDEO 占位入列）
- [x] 3.4 `LapDetailScreen.kt` VIDEO 渲染分支 — 用 `videoCtxReady = videoFilePath != null && videoPlaybackContext != null` 三态：ready→真 LapVideoPanel；否则占位 Box（"加载视频中…" / "视频不可用"）；占位 box `Modifier.aspectRatio(16f/9f)` 锁高度跟 LapVideoPanel 一致避免 ctx ready 后 layout 跳动
- [x] 3.5 `LapDetailScreen.kt` — 加 `import androidx.compose.foundation.layout.aspectRatio`；旧加的 `import rememberLazyListState` + `val listState` + `LazyColumn state = listState` 保留（无害，未来 reorder/scroll 监控可用）；不再有 `LaunchedEffect(videoEligible)` scroll 锚定

## 4. Bug 3 — 全屏 LapVideoPlaybackScreen 用户暂停控件

- [x] 4.1 `feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/LapVideoPlaybackScreen.kt` — 加 imports：`androidx.compose.foundation.gestures.detectTapGestures`（如未导入）、`androidx.compose.foundation.layout.size`、`androidx.compose.foundation.shape.CircleShape`、`androidx.compose.material.icons.filled.PlayArrow`（已有 Icons.Filled 范式）、`androidx.compose.material3.IconButton`、`androidx.compose.material3.Icon`、`androidx.compose.foundation.background`
- [x] 4.2 `LapVideoPlaybackScreen.kt:227` 附近（playheadUiWallClock state 后）— 新增 `var userPaused by remember { mutableStateOf(false) }`
- [x] 4.3 `LapVideoPlaybackScreen.kt:357-358`（状态机 `while (isActive)` 循环开头，在 `val nowRealtime = System.currentTimeMillis()` 之前）— 插入：
      ```kotlin
      if (userPaused) {
          if (exoPlayer.isPlaying) exoPlayer.pause()
          playheadUiWallClock = playheadWallClock
          delay(PLAYHEAD_TICK_MS)
          continue
      }
      ```
- [x] 4.4 `LapVideoPlaybackScreen.kt:460-463`（顶层 Box modifier）— 加 `.pointerInput(Unit) { detectTapGestures(onDoubleTap = { userPaused = !userPaused; FileLogger.d(TAG, "userPaused -> $userPaused via doubleTap") }) }`
- [x] 4.5 `LapVideoPlaybackScreen.kt:528` 之后（Slider 之后、VideoExportProgressOverlay 之前）— 加中央 IconButton：
      ```kotlin
      if (userPaused) {
          IconButton(
              onClick = {
                  userPaused = false
                  FileLogger.d(TAG, "userPaused -> false via centerButton")
              },
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

## 5. 编译 + grep gate

- [x] 5.1 `./gradlew :app:assembleDebug` 通过（首次 27min，二轮增量 12s）
- [x] 5.2 grep `detectHorizontalDragGestures` SpeedTimeChart.kt 3 命中 + AccelTimeChart.kt 2 命中；grep `detectDragGestures` 两文件 0 命中
- [x] 5.3 grep `rememberLazyListState` + LazyColumn `state = listState` LapDetailScreen.kt 各 1 命中（注：scroll 锚定方案撤销，仅保留 listState hoist）
- [x] 5.4 grep `userPaused` LapVideoPlaybackScreen.kt 9 命中
- [x] 5.5 grep `useController = false` LapVideoPlaybackScreen.kt 1 命中（既有不变）

## 6. apk 产出 + 真机验证

- [x] 6.1 apk 落盘 `app/build/outputs/apk/debug/BlazePush_v1.0_debug.apk`
- [x] 6.2 user 自跑 vivo V2405A `adb install -r`，三 bug 真机验证通过（含 Bug 2 二轮 占位准入 fix）

## 7. push 顺序（user 拍板）

- [x] 7.1 真机验证通过后准备 commit；本 round 涉及 4 文件 + 工件目录，单 commit
- [ ] 7.2 user 决定何时 push

## 8. 归档（push 后）

- [x] 8.1 metrics.yaml 写入（`review_mode: "road-test-first"` + `review_rounds_l1/l2: 0` + FileLogger 锚点摘要 + Bug 2 二轮 design drift 透明声明）
- [x] 8.2 `openspec archive fix-lap-detail-ux-three-touch-issues`

## 10. follow-up backlog

（本 round 无延期立项；若真机验证发现额外 UX issue 由 user 另开 round）
