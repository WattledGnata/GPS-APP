# Tasks: recording-persist-across-pages-and-hud-indicator

## Task 1：CameraRecordingEngine 重构 bind/unbind/attach/detach

**文件**：`feature/test/src/main/java/com/blazepush/feature/test/recording/CameraRecordingEngine.kt`

**改动**：
1. 类内新增 `private var preview: Preview? = null`（保存 Preview 实例，供 attach/detach 用）。
2. 新增 `bind(lifecycleOwner, context, config)` 方法：内部逻辑与原 `bindUseCases` 相同，但不接受 `previewView`，`Preview.Builder().build()` 创建时调用 `setSurfaceProvider(null)`（不连 surface）。`FileLogger.d(TAG, "bind: 触发 reason 由调用方传入")`。
3. 新增 `unbind(context)` 方法：若录制中先 `stopRecording()`，再 `cameraProvider.unbindAll()`，`FileLogger.d(TAG, "unbind: reason=...")`。
4. 新增 `attachPreviewSurface(previewView)` 方法：`preview?.setSurfaceProvider(previewView.surfaceProvider)` 或 `FileLogger.d(TAG, "attachPreviewSurface: preview=null, no-op WARN")`。
5. 新增 `detachPreviewSurface()` 方法：`preview?.setSurfaceProvider(null); FileLogger.d(TAG, "detachPreviewSurface: 录制中 detach surface，VideoCapture 继续")`。
6. 旧 `bindUseCases` / `unbindAll` 保留但标 `@Deprecated`（兼容，防编译断）。

**Done condition**：`:feature:test:compileDebugKotlin --offline` 通过，四个新方法可见，旧方法 deprecated 但不删。

## Task 2：RecordableCameraPreview 改为仅 attach/detach surface

**文件**：`feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/RecordableCameraPreview.kt`

**改动**：
1. `DisposableEffect` 内：进 Composable → `engine.attachPreviewSurface(previewView)`；`onDispose` → `engine.detachPreviewSurface()`。
2. 移除 `DisposableEffect` 内对 `engine.bindUseCases` 和 `engine.unbindAll` 的调用。
3. `FileLogger.d("CamRec", "RecordableCameraPreview: attachPreviewSurface on enter / detachPreviewSurface on dispose")`。

**Done condition**：`RecordableCameraPreview` 内不再调用 `engine.bindUseCases` / `engine.unbindAll`；编译通过。

## Task 3：LapLiveScreen 顶层控制绑定

**文件**：`feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/LapLiveScreen.kt`

**改动**（在 LapLiveScreen Composable 内，HorizontalPager 之前加）：
1. 获取 `val screenLifecycleOwner = LocalLifecycleOwner.current`（screen 级 lifecycle）。
2. 收集 `val recordingState by recordingEngine.recordingState.collectAsState()`（LapLiveScreen 顶层，复用给 RecIndicator 和绑定条件）。
3. 添加 `LaunchedEffect(pagerState.settledPage, recordingState)` 块：
   ```
   val isRecording = recordingState is RecordingState.Recording
   if (pagerState.settledPage == 1 || isRecording) {
       FileLogger.d("CamRec", "bind: settledPage=${pagerState.settledPage} isRecording=$isRecording")
       recordingEngine.bind(screenLifecycleOwner, context, RecordingConfig.DEFAULT)
   } else {
       FileLogger.d("CamRec", "unbind: 省电释放 settledPage=${pagerState.settledPage} isRecording=$isRecording")
       recordingEngine.unbind(context)
   }
   ```
4. 修改 LapLiveScreen 顶层已有的 `DisposableEffect(Unit)` 的 `onDispose` 块，加入录制安全收尾：
   ```kotlin
   onDispose {
       activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
       view.keepScreenOn = false
       // screen 销毁资源安全
       if (recordingState is RecordingState.Recording) {
           FileLogger.d("CamRec", "screen 销毁：录制中，先 stopRecording")
           recordingEngine.stopRecording()
       }
       FileLogger.d("CamRec", "screen 销毁：unbind camera")
       recordingEngine.unbind(context)
   }
   ```
   注意：`recordingState` 在 `DisposableEffect` 的 `onDispose` lambda 中使用时需要 capture，用 `val stateOnDispose = recordingState` 在 DisposableEffect 外缓存（Compose snapshot 机制）——或改用 `recordingEngine.recordingState.value` 直接读 StateFlow 当前值（更安全）。实现时用 `recordingEngine.recordingState.value` 读即时值。
5. `LapHudPage` 调用时传入 `recordingState` 和 `recordingEngine`（新增两个参数）。

**Done condition**：LapLiveScreen 内包含绑定条件 LaunchedEffect；`DisposableEffect` 的 onDispose 调用 `recordingEngine.stopRecording()` 和 `recordingEngine.unbind()`；编译通过。

## Task 4：LapHudPage 加 RecIndicator

**文件**：`feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/LapLiveScreen.kt`

**改动**：
1. `LapHudPage` 函数签名增加 `recordingState: RecordingState` 和 `onStopRecording: () -> Unit`（而不是直接传 engine，保持 Composable 纯度）。
2. 在 `LapHudPage` 的 `Box` 内，加一个 `Box` 覆盖层（`Modifier.align(Alignment.TopEnd).padding(12.dp)`）：
   ```kotlin
   if (recordingState is RecordingState.Recording) {
       RecIndicator(
           startedAtWallClock = recordingState.startedAtWallClock,
           onStop = onStopRecording,
           modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
       )
   }
   // hasCamera 横滑提示仍保留在 Alignment.CenterEnd
   ```
3. 新建 `RecIndicator` private Composable（在同文件）：
   - 参数：`startedAtWallClock: Long, onStop: () -> Unit, modifier: Modifier = Modifier`
   - `var elapsedMs by remember { mutableLongStateOf(0L) }`
   - `LaunchedEffect(startedAtWallClock) { while(true) { delay(1000); elapsedMs = System.currentTimeMillis() - startedAtWallClock } }`
   - 红点闪烁：`val dotAlpha by rememberInfiniteTransition(label="rec_dot").animateFloat(0.3f→1f, TweenSpec(800ms, RepeatMode.Reverse))`
   - 布局：`Row(verticalAlignment = CenterVertically, modifier = modifier.clickable(onClick = onStop).padding(4.dp))`，内含红点 `Box(Modifier.size(8.dp).clip(CircleShape).background(TrackTechColors.Red.copy(alpha=dotAlpha)))` + `Spacer(4.dp)` + 时长 `Text("mm:ss", style=UiTextLabel, color=TrackTechColors.Red, maxLines=1, overflow=Ellipsis)`。
   - 时长格式：`"%02d:%02d".format(elapsedMs/60000, (elapsedMs/1000)%60)`。

**Done condition**：LapHudPage 在 Recording 态渲染 RecIndicator；非 Recording 态无 RecIndicator；编译通过。

## Task 5：FileLogger 密集埋点校验（apply 期自查）

在 apply 期对以下 FileLogger 调用做 grep 验证（不新建文件，仅在 CameraRecordingEngine.kt / LapLiveScreen.kt 内检查）：

- `bind:` 触发（记原因 settledPage / isRecording）
- `unbind:` 触发（记"省电释放" 或"screen 销毁"）
- `attachPreviewSurface` / `detachPreviewSurface`（含"录制中 detach surface，VideoCapture 继续"）
- `screen 销毁：录制中，先 stopRecording` / `screen 销毁：unbind camera`
- HUD 点击 stop（`onStopRecording` 调用处加 `FileLogger.d("CamRec", "HUD RecIndicator 点击 stop")`）

**Done condition**：以上关键 tag/msg 在源文件中 grep 可找到。

## Task 6：编译验证

命令：
```
/Users/wattledgnata/.gradle/wrapper/dists/gradle-8.9-all/34ncldp5ayui479swhyf2hcth/gradle-8.9/bin/gradle :feature:test:compileDebugKotlin --offline -p /Users/wattledgnata/traeProjects/gps-app
/Users/wattledgnata/.gradle/wrapper/dists/gradle-8.9-all/34ncldp5ayui479swhyf2hcth/gradle-8.9/bin/gradle :app:compileDebugKotlin --offline -p /Users/wattledgnata/traeProjects/gps-app
```

**Done condition**：两条命令均 BUILD SUCCESSFUL，0 errors。

## §10 Follow-up Backlog

- `round-6-camera-lifecycle-service`：录制托管到 LifecycleService（后台继续录制 + 前台通知），完全独立于 Activity lifecycle。本 round 仅最小资源安全（onDispose stop+unbind），不支持 app 后台或 screen 关闭继续录制。参见 `docs/design/` 待沉淀 memo。

- `recording-params-config-screen`：基于用户调研 RaceChrono 参数清单（清晰度/帧率/编码/麦克风/摄像头/对焦/曝光/防抖 8 项），建立录制参数配置屏 + DataStore 持久化 + 引擎接入；帧率精确控制（60fps）需评估 CameraX 升级到 1.4.0+（compileSdk 35）；4K 清晰度 MUST 做设备能力 fallback。完整设计（CameraX 1.3.4 API 可行性 / 方案对比 / 实施约束 / 立项估算 2.3 天 / medium 复杂度）见 `docs/design/recording-params-config-deferred.md`。
