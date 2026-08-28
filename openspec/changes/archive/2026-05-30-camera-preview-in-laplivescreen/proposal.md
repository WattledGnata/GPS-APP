## Why

Phase 2 视频管线第二刀（接 `camera-module-and-permission`，已归档 archive/2026-05-30）。L0 全定（横屏 / 录音频 / 有预览，见 `docs/design/phase2-video-pipeline-entry-readiness.md` §9.3 + §9.4）：**拍摄时 MUST 实时预览相机取景**（user 要对准构图）。本 round 把 CameraX `PreviewView` 嵌进圈速主屏 `LapLiveScreen`，让用户横屏跑圈时能看到取景画面，为 round 3 录制铺好预览宿主。

**当前 baseline（核实）**：
- round 1 已就绪：`core:camera` 模块（`com.blazepush.core.camera.CameraAvailability.hasCamera(context, onResult)` + CameraX 1.3.4 五件套含 camera-view 的 `PreviewView`）；`core.domain.permission.RequiredCameraPermissions.forSdk(sdkInt)` = `[CAMERA, RECORD_AUDIO]`；`PermissionRequestOutcome.from(perms, result)` 三态分流；app manifest 已声明 CAMERA + RECORD_AUDIO + `uses-feature camera.any required=false`。
- `LapLiveScreen.kt`（`feature/test/src/main/.../ui/tracktech/LapLiveScreen.kt`）：强制横屏锁（L78-86 `DisposableEffect`，`SCREEN_ORIENTATION_LANDSCAPE`）+ root `Box` L100 + HUD `Column` L106（top strip / abnormal banner L116 或 `Lap2x2Dashboard` L124 / HOLD TO END）。**当前 root Box 无任何相机层**（grep 生产 `PreviewView` 0 命中）。
- round 1 决策 4（懒请求）：相机权限 MUST NOT 在 app 启动强请求；**接线点留给本 round**（preview 入口触发懒请求）。`MainActivity.kt` L93-103 是现成的 `RequestMultiplePermissions()` launcher 范式（`rememberLauncherForActivityResult` + `PermissionRequestOutcome.from`）。

**用户场景**：用户进 `LapLiveScreen` 横屏跑圈，默认无相机预览（opt-in，避免无故占用相机/耗电）。用户点屏内相机开关 → 系统弹 CAMERA + RECORD_AUDIO 请求 → 授权后 `PreviewView` 垫在 root Box 最底显示后置相机取景，现有 2x2 HUD 浮在预览之上（屏上合成）；拒绝则开关回弹回关闭态 + 提示，永久拒绝引导跳设置。本 round **不录制**（mp4 留 round 3），预览画面不烧进任何文件。

## What Changes

- **新增 `CameraPreview` Composable**（放 `feature:test` 的 `ui/tracktech/`，与 LapLiveScreen 同层）：`AndroidView` 包 CameraX `PreviewView` + `ProcessCameraProvider` 绑 `Preview` use-case 到 `LifecycleOwner`（`CameraSelector.DEFAULT_BACK_CAMERA`）；`DisposableEffect` 在离开/重组时 `unbindAll` 解绑。**只绑 Preview use-case，不绑 VideoCapture（不录制）**。
- **`feature:test` build.gradle.kts 加 `implementation(project(":core:camera"))`**（当前只依赖 core:domain/data/bluetooth，无 camera）—— 为拿到 camera-view 的 `PreviewView` 和 camera-lifecycle 的 `ProcessCameraProvider`。
- **`LapLiveScreen` root Box（L100）最底加预览层**：仅当 `cameraEnabled && 权限已授权` 时渲染 `CameraPreview(Modifier.fillMaxSize())`；现有 HUD `Column`（L106）保持在其后绘制（Compose 后绘者在上）→ HUD 浮预览之上不变。
- **相机开关 UI（默认关 opt-in）**：`LapLiveScreen` 加一个 toggle（小图标按钮，V2 视觉单行 Ellipsis）。开 → `rememberLauncherForActivityResult(RequestMultiplePermissions())` 请求 `RequiredCameraPermissions.forSdk(Build.VERSION.SDK_INT)` → `AllGranted` 则 `cameraEnabled=true` 显示预览；`MissingPermissions` 则 toggle 回关 + Toast 提示（永久拒绝引导跳 app 设置页）。**横屏锁 L78-86 不动**。

**不做（§10 backlog）**：VideoCapture 录制 + 首帧 wallClock 锚定 + 录制状态机（`camera-recording-and-gps-sync`，round 3）；录制中 indicator / REC 红点（`recording-toggle-and-indicator`，round 5）；`TelemetrySessionEntity` video 字段 + v5→v6 migration（`session-video-metadata-persist`，round 4）。

## Impact

- 改 `feature/test/build.gradle.kts`（加 core:camera 依赖）+ 新增 `CameraPreview.kt`（feature:test）+ 改 `LapLiveScreen.kt`（root Box 预览层 + toggle + 权限懒请求 launcher）。
- **公共协议 0 改动**；圈速链路（LapTimingEngine / binary writer / crossing）0 改动；Room schema 0 改动；横屏 orientation 锁 0 改动。
- **真机依赖**：CameraX 预览 + CAMERA/RECORD_AUDIO dangerous 权限三态流 + 横屏 PreviewView 比例必须真机验（模拟器相机不可靠 + 厂商 ROM 权限弹窗差异）。road-test-first 模式攒批路测，本 round 是 V2 视觉相关 → 小屏 gate 必走。
