## Context

Phase 2 第二刀。L0 全定（横屏 / 录音频 / 有预览）。复用 round 1 的 `core:camera`（CameraX 1.3.4 + camera-view PreviewView）+ `core.domain.permission`（RequiredCameraPermissions / PermissionRequestOutcome 三态）。本 round 把 PreviewView 嵌 `LapLiveScreen` 当背景层 + 现有 HUD 浮其上 + 加相机开关（默认关），**不录制、不持久化**。复杂度 **medium**（改公共 UI 主屏 + 引入新 module 依赖到 feature:test + CameraX lifecycle 绑定真机踩坑；不改 schema/不改公共协议）。

技术 baseline 核实锚点（apply 期 #3 自查，grep 验证）：
- `feature/test/src/main/.../ui/tracktech/LapLiveScreen.kt`：`fun LapLiveScreen` L63-148；横屏锁 `DisposableEffect` L78-86；root `Box` L100-137；HUD `Column` L106-136；abnormal banner L116 / `Lap2x2Dashboard` L124。
- `feature/test/build.gradle.kts` L50-53 dependencies 块（有 core:domain/data/bluetooth，**无 core:camera**）；L60-67 Compose 依赖块（activity.compose / compose-bom / ui / material3 / foundation / material-icons-extended 全有）。
- `MainActivity.kt` L93-109：`rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions())` + `PermissionRequestOutcome.from(pendingPermissions, result)` + `LaunchedEffect` 触发 `permissionLauncher.launch(...)` 范式。
- `core.domain.permission.RequiredCameraPermissions.forSdk(sdkInt)` = `[CAMERA, RECORD_AUDIO]`；`PermissionRequestOutcome` = `AllGranted` / `MissingPermissions(permissions)`（无 PermanentlyDenied 独立 case；永久拒绝靠 `Activity.shouldShowRequestPermissionRationale` 在调用方判定）。
- `core.camera.CameraAvailability.hasCamera(context, onResult)`（round 1，主线程回调，无相机机型 → false）。

## Decisions

### Decision 1：预览形态 = PreviewView 垫底 + 现有 HUD 浮上层（vs 全屏切换 / 画中画窗口）
- **选**：CameraX `Preview` use-case 绑 `PreviewView`，放 `LapLiveScreen` root `Box`（L100）**最底层**（第一个子元素），现有 HUD `Column`（L106）保持其后绘制 → Compose 后绘者在上，HUD 浮预览之上（屏上合成，本 round 不录不烧录）。
- **Alt A（拒绝）· 全屏预览/HUD 二选一切换**：进相机模式整屏给预览、退出才显 HUD → 跑圈时看不到实时圈速仪表，违背 LapLiveScreen「跑圈主屏」定位；且 round 3 录制要 HUD + 预览同屏（HUD 屏上叠不烧录），切换式架构要返工。
- **Alt B（拒绝）· 画中画小窗预览**：预览缩小角窗 → 取景构图视角不全（user 明确「要对准构图」，§9.3），且小窗在横屏 2x2 仪表布局里挤占空间。
- **rationale**：`docs/design/phase2-video-pipeline-entry-readiness.md` §9.3 已拍板「PreviewView 垫底 + HUD 浮上层（不烧进 mp4）」，本 round 直接落地。Box 子元素绘制顺序天然实现层叠（无需 zIndex），预览 `fillMaxSize` 当背景、HUD Column 原样浮上，改动最小。round 3 录制时 mp4 录 VideoCapture 干净画面、HUD 留离线叠，架构一致不返工。

### Decision 2：CameraPreview 宿主 module = feature:test（vs core:camera）
- **选**：`CameraPreview` Composable 写在 `feature:test`（`ui/tracktech/CameraPreview.kt`，与 LapLiveScreen 同包），`feature:test` build.gradle.kts 加 `implementation(project(":core:camera"))` 拿 PreviewView + ProcessCameraProvider。
- **Alt A（拒绝）· 写在 core:camera**：core:camera 当前是纯 Kotlin/CameraX 模块（无 Compose buildFeatures），要写 Composable 须给 core 模块加 Compose BOM + compose plugin → Compose 依赖下沉污染 core 层，违背「core 层不含 UI」分层（LapLiveScreen 等所有 Compose UI 都在 feature 层）。
- **Alt B（拒绝）· 复制 PreviewView 创建逻辑进 feature 不依赖 core:camera**：要绕过 core:camera 直接 import androidx.camera.* → feature:test 仍需加 CameraX 依赖，等于绕远路且 hasCamera 降级逻辑无法复用 round 1 的 `CameraAvailability`。
- **rationale**：分层一致（Compose 留 feature 层）；`feature:test` 已有完整 Compose 栈（build.gradle.kts L60-67），只需加一条 `core:camera` 依赖即可 import `androidx.camera.view.PreviewView` / `androidx.camera.lifecycle.ProcessCameraProvider`（CameraX 这些是 transitive，经 core:camera 的 implementation 暴露不到 feature → 需 feature:test 自身也声明 camera 依赖；故 feature:test 直接 `implementation(project(":core:camera"))` 后 CameraX 类对 feature:test 不可见，**须额外把 camera-view/camera-lifecycle 列为 feature:test 的 api 依赖或 feature:test 直接加 CameraX 依赖**）。采用：**core:camera build.gradle.kts 把 camera-view + camera-lifecycle 从 `implementation` 提为 `api`**（让 PreviewView/ProcessCameraProvider 类透出给依赖方），feature:test 仅 `implementation(project(":core:camera"))` 即可见。此改动是 round 1 模块的依赖可见性扩展，仍属 core:camera 内部，不新增第三方依赖。

### Decision 3：CameraX Preview 绑定与解绑生命周期（DisposableEffect + unbindAll）
- **选**：`AndroidView(factory = { PreviewView(it) })`；在 `DisposableEffect(lifecycleOwner, previewView)` 内 `ProcessCameraProvider.getInstance(context)` 的 future addListener 回调里 `cameraProvider.unbindAll()` 后 `bindToLifecycle(lifecycleOwner, DEFAULT_BACK_CAMERA, preview)`，`preview.setSurfaceProvider(previewView.surfaceProvider)`；`onDispose { cameraProvider?.unbindAll() }` 解绑释放相机。
- **Alt A（拒绝）· 在 AndroidView update lambda 里绑定**：update 每次重组都跑，重复 bind 会抛 already-bound / 闪烁；且没有对称的解绑点 → 相机泄漏（离屏不释放）。
- **Alt B（拒绝）· 持有 provider 在 ViewModel**：相机生命周期挂 ViewModel 会跨越 Composable 进出，且 TestSessionViewModel 是圈速链路核心，绝不混入相机句柄（污染圈速职责 + 公共协议边界）。
- **rationale**：`DisposableEffect` 是 Compose 绑定外部资源的标准范式（同 LapLiveScreen L78 横屏锁、L321 计时器）；`unbindAll` 在 onDispose 保证离开 LapLiveScreen / 关相机时立即释放相机（避免相机被占 + 耗电）。`bindToLifecycle` 接 `LocalLifecycleOwner` → 自动随 STARTED/STOPPED 暂停恢复预览。getInstance future 用 `ContextCompat.getMainExecutor` 主线程回调（同 round 1 CameraAvailability 范式）。

### Decision 4：相机开关默认关（opt-in）+ 权限懒请求时机
- **选**：`cameraEnabled` 默认 `false`（`remember { mutableStateOf(false) }`）。toggle 点击且当前关 → 先 `rememberLauncherForActivityResult(RequestMultiplePermissions())` launch `RequiredCameraPermissions.forSdk(Build.VERSION.SDK_INT)`；回调 `PermissionRequestOutcome.from`：`AllGranted` → `cameraEnabled = true`（渲染 CameraPreview）；`MissingPermissions` → `cameraEnabled` 保持 false（toggle 视觉回关）+ Toast 提示，若 `!shouldShowRequestPermissionRationale`（永久拒绝）→ 提示引导跳 app 设置页 Intent。已授权时再点 toggle 直接翻 `cameraEnabled` 不重复请求。
- **Alt A（拒绝）· 默认开 / 进屏即请求**：违背 round 1 决策 4（懒请求，不无故占相机）+ §9.4 预览 opt-in；不录视频的用户被强占相机耗电。
- **Alt B（拒绝）· toggle 与权限解耦（先开 cameraEnabled 再异步请求）**：会出现「toggle 已开但权限被拒、预览黑屏」中间态，状态不一致。
- **rationale**：opt-in + 懒请求符合 §9.4 + round 1 决策 4 + 最小惊扰；权限结果驱动 `cameraEnabled`（先权限后开关）保证「开关开 = 预览必显」状态一致。`MissingPermissions` 不区分本次拒/永久拒（`PermissionRequestOutcome` 无 PermanentlyDenied case），靠调用方 `shouldShowRequestPermissionRationale` 在 Toast 文案/跳设置上分流（同 round 1 design 决策 3 约定）。

### Decision 5：无相机机型降级（hasCamera gate）
- **选**：toggle 渲染前用 round 1 `CameraAvailability.hasCamera(context) { available -> hasCamera = it }`（`LaunchedEffect(Unit)` 查一次）。`hasCamera == false` → toggle disabled / 隐藏 + 不请求权限（无相机请求相机权限无意义）。`hasCamera == true` 才显示可点 toggle。
- **Alt A（拒绝）· 不查 hasCamera 直接 bind**：无相机机型 `bindToLifecycle` 抛异常 / 预览永久黑屏，体验崩。
- **rationale**：round 1 已造 `CameraAvailability.hasCamera`（uses-feature camera required=false → 无相机设备仍可装 app 用圈速），本 round 消费它做 toggle 可用性 gate，符合 round 1 design 决策 5（无相机机型录制 disabled）。

## Risks

- **相机被占用 / bind 失败**：另一 app 占相机或 `bindToLifecycle` 抛异常 → 预览黑屏 / crash。**mitigation**：bind 包 `runCatching`，失败则 `cameraEnabled = false` + Toast「相机不可用」+ FileLogger.e 记录（road-test-first 日志兜底）；不让异常冒泡崩 LapLiveScreen。
- **无相机机型**：见 Decision 5，`hasCamera` gate 拦在请求权限/bind 之前。**mitigation**：toggle disabled，不触发任何相机调用。
- **横屏 PreviewView 比例**：LapLiveScreen 锁 LANDSCAPE，PreviewView 默认 `FILL_CENTER` 可能拉伸/裁切。**mitigation**：`previewView.scaleType = PreviewView.ScaleType.FILL_CENTER`（填满背景，HUD 浮上不受影响）；真机小屏（vivo V2405A）验证比例不畸变 + HUD 不被预览顶偏。本 round 预览只是背景取景，不录制故比例无精度要求；round 3 录制比例由 VideoCapture QualitySelector 单独定。
- **预览不烧进录制（本 round 无录制）**：HUD 浮在预览上是 **Compose 屏上合成**，本 round 不存在任何 VideoCapture/文件输出路径 → 不可能烧录。**mitigation**：spec 显式反例 scenario 声明「本 round 无 mp4 输出，mp4 干净留 round 3」；round 3 录制时 VideoCapture 只绑相机 Surface（不绑 Compose HUD），保证 mp4 干净。本 round risks 段锁死「MUST NOT 引入任何 VideoCapture / 文件写」。
- **权限懒请求与横屏锁交互**：请求权限弹系统框时 Activity 可能短暂 onPause；横屏锁 DisposableEffect 不受影响（onDispose 只在真正离开 LapLiveScreen 触发）。**mitigation**：权限 launcher 不动 orientation；真机验证请求弹框后回到横屏预览正常。
- **公共协议 / 圈速链路边界**：本 round MUST NOT 触碰 `TestSessionViewModel` 圈速逻辑 / binary writer / crossing / orientation 锁。**mitigation**：相机句柄全在 `CameraPreview` Composable 局部 + DisposableEffect，不进 ViewModel；apply 期 #16 自查 verify 0 触碰 LapTelemetry/reader/binary。
