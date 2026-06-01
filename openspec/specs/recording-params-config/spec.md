# recording-params-config Specification

## Purpose
TBD - created by archiving change recording-params-config-screen. Update Purpose after archive.
## Requirements
### Requirement: 录制参数配置入口（齿轮图标，仅 Idle 态，浮层形态）

系统 SHALL 在相机预览页（横滑录制页）提供齿轮图标入口，打开**浮在预览之上的 `RecordingSettingsOverlay` 浮层**（不跳路由 / 不开 activity，design Decision 1 实施期修订）；该入口 MUST 仅在 `RecordingState.Idle`（非录制态）可用，录制中 MUST 禁用（disabled），从交互层杜绝录制中改参数（design Decision 7 / memo M1·M7）。浮层打开期间 `LapLiveScreen` MUST 不离开 composition（保持横屏 + 相机绑定），改参数经 rebind 即时反映到预览。

#### Scenario: Idle 态点齿轮打开浮层
- **WHEN** 当前未录制（`RecordingState.Idle`），用户点录制页齿轮
- **THEN** 系统 SHALL 在预览页之上显示 `RecordingSettingsOverlay`（左侧露出实时预览、右侧参数面板），MUST NOT 导航离开 LapLiveScreen / 切竖屏

#### Scenario: 浮层内改参数实时反映到预览
- **WHEN** 浮层打开、用户改某参数（如曝光/对焦/前后置）
- **THEN** config 经 repository 落库 → LapLiveScreen rebind → 相机预览 SHALL 用新 config 重绑，预览即时反映效果（相机绑定全程不因导航中断）

#### Scenario: 反例——录制中齿轮 MUST 禁用
- **WHEN** 正在录制（非 Idle）
- **THEN** 齿轮入口 MUST disabled、MUST NOT 可打开浮层，从而 MUST NOT 在录制中触发改参数 / rebind

### Requirement: 清晰度可配置且 4K 做设备能力降级

系统 SHALL 支持 `RecordingResolution` 三档 `UHD_4K / FHD_1080P / HD_720P`。纯函数 `resolveEffectiveResolution(requested, supported)`（RecordingResolution 域，CameraX-free）SHALL 按设备支持集合决定实际生效分辨率（降级）；引擎按生效分辨率构造 `QualitySelector.fromOrderedList(...)` + `FallbackStrategy`（而非 `from(UHD)`），在不支持 4K 的设备上 MUST 自动降级、MUST NOT 崩溃；UI MUST 显示实际选中（可能被降级）。

#### Scenario: 设备支持 4K 时录得 4K
- **WHEN** 用户选 `UHD_4K` 且设备支持集合含 `UHD_4K`
- **THEN** `resolveEffectiveResolution(UHD_4K, {UHD_4K,FHD_1080P,HD_720P})` SHALL 返回 `UHD_4K`

#### Scenario: 设备不支持 4K 时降级 1080p
- **WHEN** 用户选 `UHD_4K` 但设备支持集合仅 `{FHD_1080P, HD_720P}`
- **THEN** `resolveEffectiveResolution(UHD_4K, {FHD_1080P,HD_720P})` SHALL 返回 `FHD_1080P`，UI SHALL 显示"已降级为 1080p"

#### Scenario: 反例——绝不用 from(UHD) 在不支持设备上崩溃
- **WHEN** 引擎按 4K 配置 bind
- **THEN** 实现 MUST 走 `fromOrderedList` + `FallbackStrategy` 路径，MUST NOT 出现 `QualitySelector.from(Quality.UHD)` 这种在不支持设备上直接崩溃的调用

### Requirement: 麦克风开关

系统 SHALL 支持 `audioEnabled: Boolean`。开启时录制 MUST 调 `prepareRecording(...).withAudioEnabled()` 带音轨；关闭时 MUST NOT 调用 `withAudioEnabled()`，录制文件 MUST NOT 含音轨。

#### Scenario: 开麦录制带音轨
- **WHEN** `audioEnabled = true` 录制一段
- **THEN** 输出 mp4 SHALL 含音频轨

#### Scenario: 关麦录制无音轨
- **WHEN** `audioEnabled = false` 录制一段
- **THEN** 实现 MUST NOT 调 `withAudioEnabled()`

#### Scenario: 反例——关麦后文件仍含音轨
- **WHEN** `audioEnabled = false` 录制完成
- **THEN** 输出 mp4 MUST NOT 含音频轨（若含则视为违反，测试 fail）

### Requirement: 前后置摄像头切换（变更经 rebind）

系统 SHALL 支持 `cameraFacing: CameraFacing(BACK / FRONT)`。变更 MUST 经 `unbindAll() + bindToLifecycle()` 重新绑定（CameraX `Recorder` 绑定后参数不可变，design Decision 7）。因入口 Idle-only，切换 MUST NOT 发生在录制中。

#### Scenario: 选前置摄像头
- **WHEN** 用户在 Idle 态选 `FRONT` 并返回录制页
- **THEN** 引擎 SHALL 用 `CameraSelector.DEFAULT_FRONT_CAMERA` rebind，预览显示前置取景

#### Scenario: 切换触发重新绑定
- **WHEN** `cameraFacing` 从 BACK 改为 FRONT
- **THEN** 引擎 SHALL 调 `unbindAll()` 后再 `bindToLifecycle()`，MUST NOT 试图修改已绑定 use-case

#### Scenario: 反例——录制中 MUST NOT 改 facing
- **WHEN** 正在录制
- **THEN** 设置入口 disabled（上一 Requirement 保证），因此 facing MUST NOT 在录制中被改 / 触发 rebind

### Requirement: 曝光 EV 补偿（范围运行时查询 + clamp）

系统 SHALL 支持 `exposureCompensationEv: Int`，应用经 `cameraControl.setExposureCompensationIndex(ev)`。设置屏 MUST 运行时读 `cameraInfo.exposureState.exposureCompensationRange` 渲染滑块范围，MUST NOT 硬编码 -5~+5（memo M5）；纯函数 `clampEv(requested, range)` SHALL 把越界请求夹到范围内，引擎 MUST NOT 传越界 index。

#### Scenario: 设备范围内设 EV
- **WHEN** 设备 range = -4~+4，用户设 EV +1
- **THEN** `clampEv(1, -4..4)` SHALL 返回 1，引擎调 `setExposureCompensationIndex(1)`

#### Scenario: UI 滑块按设备范围渲染
- **WHEN** 设备 `exposureCompensationRange = -2..2`
- **THEN** 设置屏曝光滑块范围 SHALL 为 -2~+2，MUST NOT 显示硬编码的 -5~+5

#### Scenario: 反例——越界 EV 被 clamp 不直接下发
- **WHEN** 持久化中存的 EV = +5 但设备 range 仅 -2~+2
- **THEN** `clampEv(5, -2..2)` SHALL 返回 2，引擎 MUST NOT 调 `setExposureCompensationIndex(5)`（越界，测试 fail）

### Requirement: 对焦模式（连续自动 / 锁定无限远）

系统 SHALL 支持 `focusMode: FocusMode(CONTINUOUS_AUTO / LOCKED_INFINITY)`。`LOCKED_INFINITY` 时 MUST 对 `Preview.Builder` 用 `Camera2Interop.Extender` 设 `CONTROL_AF_MODE = OFF` + `LENS_FOCUS_DISTANCE = 0f`（无限远）；`CONTINUOUS_AUTO` 时 MUST NOT 附加该 interop（走 HAL 默认连续自动）。设备忽略 manual AF 时 MUST 安全退化（不崩）。

#### Scenario: 选锁定无限远附加 Camera2Interop
- **WHEN** `focusMode = LOCKED_INFINITY` 时 bind
- **THEN** 实现 SHALL 对 `Preview.Builder` 附加 `CONTROL_AF_MODE_OFF` + `LENS_FOCUS_DISTANCE = 0f`

#### Scenario: 选连续自动不附加 interop
- **WHEN** `focusMode = CONTINUOUS_AUTO` 时 bind
- **THEN** 实现 MUST NOT 附加对焦 Camera2Interop option，走 HAL 默认连续自动对焦

#### Scenario: 反例——设备不支持 manual AF 时安全退化
- **WHEN** `LOCKED_INFINITY` 但设备不支持 `CONTROL_AF_MODE_OFF`
- **THEN** 应用 MUST NOT 崩溃（HAL 忽略该 option 退化为连续自动即可），FileLogger MUST 记录所选 focusMode 以便路测诊断

### Requirement: 录制参数持久化跨会话

系统 SHALL 用 DataStore Preferences（`RecordingPreferencesRepository`，仿 `RecentTracksStore`）持久化 `RecordingConfig`，key 集中 `RecordingPrefsKeys`。读出为 `Flow<RecordingConfig>`，缺 key（新安装）MUST 走 `RecordingConfig.DEFAULT` 字段默认值，MUST NOT 因缺值崩溃。

#### Scenario: 改参数跨会话保留
- **WHEN** 用户改 `resolution=HD_720P, audioEnabled=false`，杀进程重进 app
- **THEN** repository roundtrip SHALL 读回 `HD_720P` + `audioEnabled=false`

#### Scenario: 新安装缺 key 走默认
- **WHEN** 全新安装、DataStore 无任何 recording key
- **THEN** repository SHALL 返回 `RecordingConfig.DEFAULT`（FHD_1080P / 30 / 开麦 / 后置 / 连续自动 / EV 0）

#### Scenario: 反例——DataStore 未就绪首帧 MUST NOT 崩
- **WHEN** 进录制页时 config Flow 尚未发出首值
- **THEN** 录制页 MUST 用 `RecordingConfig.DEFAULT` 兜底，MUST NOT 空指针 / 崩溃 / 黑屏

### Requirement: 本轮不暴露 60fps（compileSdk 34 边界）

本轮 compileSdk 保持 34、CameraX 1.3.4，60fps 精确锁定需升级（memo M3/M6）。`RecordingConfig.targetFps` 字段 SHALL 保留作扩展点（默认 30），但 `RecordingSettingsScreen` MUST NOT 暴露 60fps 选项（避免"伪选项"——给了选项但实际帧率不受控）。

#### Scenario: 设置屏帧率区不含 60fps
- **WHEN** 渲染 `RecordingSettingsScreen`
- **THEN** UI MUST NOT 出现可选的 "60fps" 控件

#### Scenario: targetFps 默认保持 30
- **WHEN** 读取任意持久化或默认 config
- **THEN** `targetFps` SHALL 等于 30

#### Scenario: 反例——出现 60fps 选项视为违反
- **WHEN** 设置屏被加入 60fps 选项
- **THEN** 视为违反本轮 compileSdk 34 边界约束，相关 UI 测试 / 人工 gate MUST fail

