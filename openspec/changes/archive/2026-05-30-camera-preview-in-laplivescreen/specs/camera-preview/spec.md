# camera-preview 规格增量

## ADDED Requirements

### Requirement: LapLiveScreen 嵌入相机实时预览（PreviewView 垫底 + HUD 浮上层 + opt-in 开关）

`LapLiveScreen` SHALL 提供一个默认关闭（opt-in）的相机开关；开启并授权后，CameraX `Preview` use-case 绑 `PreviewView` 显示后置相机取景，渲染在 root `Box` 最底层当背景，现有圈速 HUD（top strip / 2x2 dashboard 或 abnormal banner / HOLD TO END）保持浮在预览之上（屏上合成）。本 round MUST NOT 录制、MUST NOT 持久化、MUST NOT 产生任何 mp4/文件输出。

实现 MUST 满足：

1. **默认关 opt-in**：`cameraEnabled` 初值 MUST 为 `false`；进入 `LapLiveScreen` 默认 MUST NOT 显示预览、MUST NOT 请求相机权限、MUST NOT 占用相机。
2. **懒请求权限**：用户开启 toggle 且权限未授予时，MUST 用 `RequestMultiplePermissions()` launcher 请求 `RequiredCameraPermissions.forSdk(Build.VERSION.SDK_INT)`（= `[CAMERA, RECORD_AUDIO]`），复用 `PermissionRequestOutcome.from(perms, result)` 分流。
3. **授权后显示**：`AllGranted` 时 MUST 置 `cameraEnabled = true` 并渲染 `CameraPreview`；`MissingPermissions` 时 MUST 保持 `cameraEnabled = false`（toggle 视觉回关）+ 提示，永久拒绝（`!shouldShowRequestPermissionRationale`）MUST 引导跳 app 设置页。
4. **层叠顺序**：`CameraPreview` MUST 是 root `Box` 第一个子元素（最底），HUD `Column` MUST 保持其后绘制（浮在预览之上）；本 round MUST NOT 改横屏 orientation 锁（`DisposableEffect` LANDSCAPE）。
5. **Preview-only use-case**：MUST 只绑 CameraX `Preview` use-case（`CameraSelector.DEFAULT_BACK_CAMERA`），MUST NOT 绑 `VideoCapture`、MUST NOT 引入任何文件写入路径（录制是 round 3）。
6. **生命周期解绑**：MUST 用 `DisposableEffect` 在离开 / 关闭相机 / Composable dispose 时 `cameraProvider.unbindAll()` 释放相机（避免相机被占 + 耗电泄漏）。
7. **无相机机型降级**：MUST 用 round 1 `CameraAvailability.hasCamera` 查询；无相机时 toggle MUST disabled / 隐藏且 MUST NOT 请求权限 / MUST NOT bind（不崩）。
8. **公共协议边界**：MUST NOT 触碰 `TestSessionViewModel` 圈速逻辑 / binary writer / crossing / GPS 接收链路（A56 + 公共协议不可改）。

#### Scenario: 默认关 + 开启授权后显示预览
- **GIVEN** 用户进入横屏 `LapLiveScreen`，相机开关默认关闭，无预览
- **WHEN** 用户点击相机开关开启，系统弹 CAMERA + RECORD_AUDIO 请求且全部 grant
- **THEN** `PermissionRequestOutcome.from()` 返回 `AllGranted`，`cameraEnabled = true`
- **AND** `PreviewView` 在 root Box 最底显示后置相机取景，2x2 HUD 浮在预览之上正常显示
- **AND** 横屏锁不变、圈速计时不受影响

#### Scenario: 反例——权限拒绝则开关回弹回关闭态
- **GIVEN** 用户点击相机开关开启
- **WHEN** 用户拒绝 CAMERA 或 RECORD_AUDIO（返回 `MissingPermissions`）
- **THEN** `cameraEnabled` MUST 保持 `false`，toggle 视觉回到关闭态
- **AND** MUST NOT 显示预览、MUST NOT 崩溃、MUST NOT 误判为已授权显示黑屏预览
- **AND** 给出提示；若永久拒绝（`!shouldShowRequestPermissionRationale`）MUST 引导跳 app 设置页

#### Scenario: 反例——无相机机型不崩
- **GIVEN** 设备无相机硬件（`CameraAvailability.hasCamera` 回调 `false`）
- **WHEN** 用户进入 `LapLiveScreen`
- **THEN** 相机开关 MUST disabled / 隐藏，MUST NOT 请求相机权限、MUST NOT 调用 `bindToLifecycle`
- **AND** 圈速功能正常，应用 MUST NOT 崩溃
- **AND** 若实现未做 hasCamera gate 直接 bind 导致无相机设备崩溃，该 scenario fail

#### Scenario: 反例——本 round 无 mp4 输出（预览不烧进录制，mp4 干净留 round 3）
- **GIVEN** 相机预览已开启显示
- **WHEN** 检查本 round 全部代码路径
- **THEN** MUST NOT 存在任何 `VideoCapture` 绑定 / `Recorder` / 文件写入 / mp4 输出
- **AND** HUD 浮在预览上仅是 Compose 屏上合成，不进任何文件
- **AND** 若实现引入了 VideoCapture 或文件写入（提前触碰 round 3 录制），该 scenario fail

#### Scenario: 反例——离开屏幕 / 关闭开关 MUST 解绑释放相机
- **GIVEN** 相机预览已开启且相机被绑定
- **WHEN** 用户离开 `LapLiveScreen`（popBackStack）或关闭相机开关
- **THEN** `DisposableEffect` 的 `onDispose` MUST 调 `cameraProvider.unbindAll()` 释放相机
- **AND** 相机 MUST NOT 被持续占用 / 持续耗电
- **AND** 若实现未在 dispose 解绑导致相机泄漏，该 scenario fail
