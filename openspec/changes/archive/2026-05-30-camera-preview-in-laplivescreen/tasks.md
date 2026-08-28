# Tasks

## 0. apply 前自查（#3/#14/#16/#17）
- [x] 0.1 #3 grep 锚点对齐：`grep -n "fun LapLiveScreen\|DisposableEffect\|Box(\|Column(\|Lap2x2Dashboard" feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/LapLiveScreen.kt` 确认 root Box L100 / HUD Column L106 / 横屏锁 L78-86 行号未漂移；`grep -n "project(" feature/test/build.gradle.kts` 确认 dependencies 块（无 core:camera）；`grep -n "RequestMultiplePermissions\|PermissionRequestOutcome.from\|rememberLauncherForActivityResult" app/src/main/java/com/blazepush/MainActivity.kt` 确认 launcher 范式 L93-109
- [x] 0.2 #16：本 round 0 触碰 LapTelemetry / reader / binary writer / crossing / TestSessionViewModel 圈速逻辑；verify 相机句柄全在 CameraPreview Composable 局部，不进 ViewModel
- [x] 0.3 #14：本 round 无 DAO / fake DAO 改动（无 Room schema 触碰），N/A 但记录确认
- [x] 0.4 #17：apply 期每 task 比对 design Decision 1-5；偏离立即暂停走修订（road-test-first 下不调 Opus 子 agent，CC 自审 + 日志）
- [x] 0.5 road-test-first 日志锚点声明：CameraPreview bind 成功/失败、权限 AllGranted/MissingPermissions、hasCamera 结果、cameraEnabled 翻转 MUST 埋 `FileLogger.d/e`（落 filesDir/debug_log.txt，路测 adb pull 诊断）

## 1. feature:test 依赖 core:camera（拿 PreviewView / ProcessCameraProvider）
- [x] 1.1 `core/camera/build.gradle.kts`：把 `libs.androidx.camera.view` + `libs.androidx.camera.lifecycle` 两条从 `implementation` 提为 `api`（让 PreviewView / ProcessCameraProvider 类透出给依赖方 feature:test）；camera-core/camera2/video 保持 implementation（done condition：core:camera 编译通过，api 暴露 androidx.camera.view + androidx.camera.lifecycle）
- [x] 1.2 `feature/test/build.gradle.kts` dependencies 块（L50-53 后）加 `implementation(project(":core:camera"))`（done condition：feature:test 可 import `androidx.camera.view.PreviewView` / `androidx.camera.lifecycle.ProcessCameraProvider` / `com.blazepush.core.camera.CameraAvailability`）

## 2. 新增 CameraPreview Composable
- [x] 2.1 新建 `feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/CameraPreview.kt`（首行 `// @IgnoreFormatCheck`）：`@Composable fun CameraPreview(modifier: Modifier)` —— `AndroidView(factory = { ctx -> PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } })`
- [x] 2.2 `DisposableEffect(lifecycleOwner, previewView)`：`ProcessCameraProvider.getInstance(context)` future addListener（`ContextCompat.getMainExecutor`）回调内 `runCatching { provider.unbindAll(); provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview) }`，`preview.setSurfaceProvider(previewView.surfaceProvider)`；失败 `FileLogger.e` + 不冒泡（done condition：bind 异常不崩，预览黑屏降级）
- [x] 2.3 `onDispose { runCatching { cameraProvider?.unbindAll() } }` 解绑释放相机（Decision 3 + spec MUST 6）
- [x] 2.4 lifecycleOwner 取 `LocalLifecycleOwner.current`；MUST NOT 绑 VideoCapture / MUST NOT 任何文件写（spec MUST 5）

## 3. LapLiveScreen 接入预览层 + 相机开关
- [x] 3.1 `LapLiveScreen.kt`：加状态 `var cameraEnabled by remember { mutableStateOf(false) }` + `var hasCamera by remember { mutableStateOf(false) }`（默认关，spec MUST 1）
- [x] 3.2 `LaunchedEffect(Unit) { CameraAvailability.hasCamera(context) { hasCamera = it } }` 查一次（Decision 5 / spec MUST 7）
- [x] 3.3 权限 launcher：`val launcher = rememberLauncherForActivityResult(RequestMultiplePermissions())` { result -> when(PermissionRequestOutcome.from(perms, result)) { AllGranted -> cameraEnabled=true; MissingPermissions -> cameraEnabled=false + Toast + （!shouldShowRequestPermissionRationale 跳设置 Intent） } }（复用 MainActivity L93-109 范式 / spec MUST 2-3）
- [x] 3.4 root `Box`（L100）内 HUD `Column`（L106）**之前**插入预览层：`if (cameraEnabled && hasCamera) { CameraPreview(Modifier.fillMaxSize()) }`（最底，HUD 后绘浮上 / Decision 1 / spec MUST 4）
- [x] 3.5 相机 toggle UI（V2 视觉单行）：小图标按钮（material-icons-extended 的相机图标），点击：若 `!cameraEnabled` 且权限未授 → `launcher.launch(RequiredCameraPermissions.forSdk(Build.VERSION.SDK_INT).toTypedArray())`；已授权 → 直接翻 `cameraEnabled`；`hasCamera==false` → disabled。放 top strip 区或 root Box 角落（不破坏现有 HUD 布局 / Text 单行 Ellipsis 规则）
- [x] 3.6 横屏锁 DisposableEffect（L78-86）**不动**（spec MUST 4）

## 4. 测试
- [x] 4.1 `feature/test/src/test/.../ui/tracktech/CameraPreviewContractTest.kt`（首行 `// @IgnoreFormatCheck`）：grep/字面量契约——验证生产代码 CameraPreview 只绑 Preview use-case（断言源码 `grep VideoCapture` 0 命中 + `bindToLifecycle` 含 `Preview` 不含 `VideoCapture`），锁死 spec MUST 5「无录制」反例（用 `projectRoot()` helper 解析路径，参 PresetTrackAssetTest 范式避 working dir 坑）
- [x] 4.2 权限三态分流单测复用既有 `PermissionRequestOutcomeTest`（已存在，verify RequiredCameraPermissions=[CAMERA,RECORD_AUDIO] 走 from 返回 AllGranted/MissingPermissions），本 round 不新写权限分流逻辑（spec MUST 2）；若无对应断言则补 `cameraEnabled` 状态机的纯函数单测（grant→true / deny→false）

## 5. 编译 + 真机
- [x] 5.1 `./gradlew :core:camera:compileDebugKotlin :feature:test:compileDebugKotlin :app:assembleDebug` BUILD SUCCESSFUL（core:camera api 暴露 + feature:test 见 PreviewView）
- [ ] 5.2 **真机验证（user 配合，road-test-first 攒批）**：(a) 默认进屏无预览不请求权限；(b) 开 toggle → 弹 CAMERA+RECORD_AUDIO → grant → 横屏预览显示 + 2x2 HUD 浮上；(c) deny → toggle 回关 + 提示；(d) 永久拒绝跳设置；(e) 离开屏幕相机释放（不持续占用）；(f) **小屏 vivo V2405A** 验证横屏 PreviewView 比例不畸变 + HUD 不被顶偏 + 文字单行不换行（V2 视觉 gate）

## 6. follow-up（不在本 round · §10 backlog）
- [ ] 6.1 `camera-recording-and-gps-sync`（round 3，architectural）：VideoCapture 启停 + 首帧 `recordingStartedAtWallClock=currentTimeMillis()` 精确锚定 + 录制状态机；mp4 录干净画面（HUD 不烧录，本 round 已确立 Compose 屏上叠架构）
- [ ] 6.2 `recording-toggle-and-indicator`（round 5，medium）：录制中 REC 红点 / 录制时长 indicator（时长走 Score 字体非 DSEG7，单行 Ellipsis）
- [ ] 6.3 `session-video-metadata-persist`（round 4，medium）：`TelemetrySessionEntity` 加 `videoFilePath/videoStartedAtWallClock` + v5→v6 strict migration（前置 `restore-strict-migrations-pre-release`）
- [ ] 6.4 跨 round 文件占用：本 round 改 `LapLiveScreen.kt`，与 `redesign-realtime-delta-projection-search`（已合回主区）历史改过同文件 DELTA tile；rebase 前看板 §6 登记 LapLiveScreen 跨 round 占用确认无 conflict surface
