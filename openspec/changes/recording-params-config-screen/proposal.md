## Why

Phase 2 视频管线已落地拍摄 / 按圈回放 / 叠加导出，但**录制参数全部硬编码**：`RecordingConfig` 当前仅 `resolution: RecordingResolution`（枚举只有 `FHD_1080P`）+ `targetFps = 30`；`CameraRecordingEngine.bind(config)` 虽已接受 config，但 `when(resolution)` 只映射 `Quality.FHD`，麦克风 `withAudioEnabled()` 写死、摄像头 `DEFAULT_BACK_CAMERA` 写死、对焦 / 曝光无任何控制代码。用户每次只能用同一套固定默认值拍摄。

用户在赛道路测时明确提出需求：不同场景需要不同拍摄设置，**尤其对焦要能锁无限远**——赛道拍远景时连续自动对焦会在车辆 / 护栏 / 路面之间来回拉焦，导致画面反复虚焦，专业赛道 App（RaceChrono）都提供"锁定无限远"。当前无任何入口让用户调整。

**Baseline（已核对）**：CameraX `1.3.4`，compileSdk `34`；DataStore Preferences `1.0.0` 已在 `feature/test`（`build.gradle.kts:93`），有现成用法先例 `feature/test/.../datastore/RecentTracksStore.kt`；`CameraRecordingEngine.bind(lifecycleOwner, context, config)` 扩展点已预留。完整可行性调研见 `docs/design/recording-params-config-deferred.md`（9 章，含 RaceChrono 8 参数清单 + CameraX 1.3.4 逐项 API 评估 + M1–M7 MUST 条款）。

**用户场景**：车手到赛道，开测试前点录制页齿轮进入录制设置，选 `1080p / 关麦克风 / 后置 / 曝光 -1 / 对焦锁无限远`，回去开始跑圈，录制即按所选参数走；下次进 app 设置仍保留。

## What Changes

- **新增 `RecordingSettingsScreen`**（Compose，Track Tech V2 视觉规范）：从相机预览页（横滑那页）右上角**齿轮图标**进入；**录制态禁用**（只在 `RecordingState.Idle` 可达）。
- **`RecordingConfig` 扩展 4 个字段** + `RecordingResolution` 扩展 2 档：
  - 新字段 `audioEnabled: Boolean = true` / `cameraFacing: CameraFacing = BACK` / `focusMode: FocusMode = CONTINUOUS_AUTO` / `exposureCompensationEv: Int = 0`
  - `RecordingResolution` 加 `UHD_4K` / `HD_720P`（现仅 `FHD_1080P`）
- **新增 `RecordingPreferencesRepository`**（DataStore Preferences，仿 `RecentTracksStore`）：持久化 config，key 集中在 `RecordingPrefsKeys` object 防拼错。
- **新增 `RecordingCapabilityDetector`**：运行时查设备支持的 `Quality` 列表 + 曝光 `exposureCompensationRange`，用于 4K fallback、EV 滑块范围、不支持项 UI 灰显。
- **`CameraRecordingEngine.bind()` 按 config 应用参数**：`QualitySelector.fromOrderedList(...)` 设备能力 fallback（4K→1080p）/ `CameraSelector` 按 facing / `withAudioEnabled()` 条件调用 / `Camera2Interop(Preview.Builder)` 对焦（`AF_MODE_OFF` + `LENS_FOCUS_DISTANCE = 0f` 锁无限远）/ `cameraControl.setExposureCompensationIndex(ev)`。
- **参数变更 MUST rebind**（`unbindAll + bindToLifecycle`）；**切摄像头 MUST 先 stop 录制**；录制中不允许改参数。
- **明确不在本轮**：60fps（需升 CameraX 1.4+ / compileSdk 35，工程级变更）、H.265、防抖。本轮 compileSdk 保持 34，`targetFps` 字段保留作扩展点但 **UI 不暴露 60fps 选项**。

## Capabilities

### New Capabilities

- `recording-params-config`: 录制参数的领域模型（`RecordingConfig` 扩展字段）、持久化（DataStore）、设备能力探测、配置屏 UI 与入口、以及引擎按配置应用 CameraX 参数的契约。

### Modified Capabilities

（无。不改 `camera-preview` 既有需求——它是 round 1 的 LapLive 预览开关、明确 preview-only 不录制；本轮齿轮入口是录制页新增 affordance，归属新 capability。）

## Impact

- **模块**：`feature/test`（主，全部改动集中此模块，符合"避免污染"边界）。
- **文件**：
  - 扩展：`recording/RecordingConfig.kt`（加字段 + 枚举）、`recording/CameraRecordingEngine.kt`（按 config 应用参数）、`di/AppModule.kt`（DI 注册 repository / detector）
  - 新增：`datastore/RecordingPreferencesRepository.kt`、`recording/RecordingCapabilityDetector.kt`、`recording/RecordingPrefsKeys.kt`、`ui/settings/RecordingSettingsScreen.kt`
  - 触碰：相机预览页 composable（加齿轮入口 + 导航）、导航图（注册设置屏 route）
- **依赖**：无新增（DataStore 已在）；CameraX `1.3.4` 不变；compileSdk `34` 不变（不升级）。
- **协议兼容性**：MUST NOT 触碰 RaceChrono BLE 协议 / GPS 接收链路 / binary writer / 圈速逻辑 / crossing（A56 + 公共协议不可改边界）。本轮纯客户端录制侧，无双端改动。
- **测试**：新增单测（`RecordingConfig` 默认值 / DataStore roundtrip / `resolveEffectiveQuality` fallback 纯函数 / `clampEv` 纯函数 / `RecordingResolution → Quality` 映射 `when` 穷举）；真机验证（实际分辨率选中、前摄切换、曝光生效、对焦无限远取景变化）。
- **执行模式**：road-test-first（去 Codex + 跳 Opus 子 agent，靠 CC 自审 + FileLogger 埋点 + 真机攒批）。复杂度 medium。
