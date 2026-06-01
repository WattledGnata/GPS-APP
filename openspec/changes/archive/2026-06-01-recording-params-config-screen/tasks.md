## 1. 锚点校验（apply 启动前必跑，防 rebase 漂移 · v3 盲点 #3/#4）

- [x] 1.1 grep 验证引擎锚点：`bind(`@123 / `when(config.resolution)`@146 / `DEFAULT_BACK_CAMERA`@156 均命中。
- [x] 1.2 grep 验证状态锚点：`RecordingState.Idle`（RecordingState.kt:21）+ `_recordingState`@76-77 存在。
- [x] 1.3 grep 验证 DataStore 先例：`RecentTracksStore.kt` `by preferencesDataStore`@33 + 双构造@44-48 形态确认。
- [x] 1.4 grep 确认齿轮入口宿主：`CameraPreviewPage`（LapLiveScreen.kt:551）REC/STOP TextButton 控件区，齿轮落 BottomStart。

## 2. RecordingConfig 扩展 + 纯函数（可单测，无硬件依赖）

- [x] 2.1 `RecordingResolution` 加 `UHD_4K`/`HD_720P`；新增 `enum CameraFacing`、`enum FocusMode`（RecordingConfig.kt）。
- [x] 2.2 `RecordingConfig` 加 `audioEnabled`/`cameraFacing`/`focusMode`/`exposureCompensationEv`，`DEFAULT` 同步；旧 `bind(DEFAULT)` 不破。
- [x] 2.3 纯函数 `resolveEffectiveResolution(requested, supported)`（RecordingResolution 域，CameraX-free，UHD→FHD→HD 降级）。
- [x] 2.4 纯函数 `clampEv(requested, range)`（coerceIn 夹边界）。

## 3. 持久化层（DataStore Preferences，仿 RecentTracksStore）

- [x] 3.1 `recording/RecordingPrefsKeys.kt`：5 个 key 集中（string/boolean/int），targetFps 不持久化。
- [x] 3.2 `datastore/RecordingPreferencesRepository.kt`：双构造 + `configFlow`（缺 key/枚举解析失败→DEFAULT）+ `update()`。
- [x] 3.3 `di/AppModule.kt` recordingModule 注册 `single { RecordingPreferencesRepository(androidContext()) }`。

## 4. 设备能力探测

- [x] 4.1 `recording/RecordingCapabilityDetector.kt`：免绑定（`availableCameraInfos` + `CameraSelector.filter`）查 supported qualities + EV range，封装 `RecordingCapabilities`（+FALLBACK）。
- [x] 4.2 `di/AppModule.kt` 注册 `single { RecordingCapabilityDetector() }`。

## 5. 引擎接入（CameraRecordingEngine 按 config 应用 · 含 FileLogger 埋点）

- [x] 5.1 `bind()` 分辨率：`buildQualitySelector` 走 `fromOrderedList` + `FallbackStrategy.lowerQualityOrHigherThan`（绝不 from(UHD)）；先 `resolveEffectiveResolution` 算生效档。
- [x] 5.2 摄像头按 `config.cameraFacing` 选 selector（替换硬编码 BACK）。
- [x] 5.3 麦克风：`startRecording` 内按 `boundConfig.audioEnabled` 条件 `withAudioEnabled()`。
- [x] 5.4 对焦：`@OptIn(ExperimentalCamera2Interop)` `applyFocusInterop`，LOCKED_INFINITY→AF_MODE_OFF + LENS_FOCUS_DISTANCE=0f；CONTINUOUS_AUTO 不附加。
- [x] 5.5 曝光：bind 后 `applyExposure` → `setExposureCompensationIndex(clampEv(...))`，不支持则跳过。
- [x] 5.6 FileLogger 埋点：bind apply（req/effective/facing/audio/focus/ev）+ applyFocusInterop + applyExposure + startRecording audioEnabled。另：幂等守卫加 config 比对（config 变才 rebind）。

## 6. 配置屏 UI（RecordingSettingsScreen + 齿轮入口）

- [x] 6.1 `ui/settings/RecordingSettingsOverlay.kt`（实施期修订：route 屏→浮层）：5 类控件（清晰度 chips 灰显 / 麦克风 Switch / 前后置 chips / 对焦 chips / 曝光 Slider 范围取 caps.evRange 不硬编码，拖动 draft 松手落库）；Track Tech V2 视觉 + 全 Text maxLines=1+Ellipsis；右侧 360dp 半透明面板 + 左侧 dismiss 区露实时预览。
- [x] 6.2 UI 无 60fps 选项（帧率不渲染，spec 反例锁）。
- [x] 6.3 读写经 `RecordingPreferencesRepository`（configFlow 单一真相源 + update 即存）；进浮层按 facing 探测 caps。
- [x] 6.4 齿轮入口：`CameraPreviewPage` BottomStart `⚙ 设置` TextButton，`enabled = recordingState is Idle`（录制态 disabled）；点击置 `showRecordingSettings=true` 显示 overlay（不跳路由 → 横屏保持 + 相机不解绑见效果）。
- [x] 6.5 浮层渲染在 `CameraPreviewPage` Box 末尾（绘制最上层）；**移除**原 NavHost route `recording_settings` + navController 导航（实施期修订）。

## 7. 单元测试

- [x] 7.1+7.2+7.3 `RecordingConfigTest.kt`：DEFAULT 值 + `resolveEffectiveResolution`（5 case）+ `clampEv`（4 case）= 10 测试，全绿。
- [x] 7.4 `RecordingPreferencesRepositoryTest.kt`：roundtrip + 缺 key→DEFAULT + partial update = 3 测试，全绿。

## 8. 编译 + road-test-first gate

- [x] 8.1 `:feature:test:compileDebugKotlin` + `:feature:test:testDebugUnitTest --offline` 全绿（新 13 测试 + 全模块无回归）。
- [x] 8.2 `:app:assembleDebug --offline` 构建 apk OK（74M）。FileLogger 埋点锚点：`CamRec` tag（bind apply / applyFocusInterop / applyExposure / startRecording audioEnabled）+ `RecCapDetect` tag（detect caps）+ `RecSettings` tag（进屏探测）。
- [ ] 8.3 【真机·串行 gate·需 user 放行】华为 `8KE0219522008434` 攒批验证：① 进设置改清晰度/麦克风/前后置/曝光/对焦无限远 → 录一段验生效；② 4K 设备能力降级显示；③ 对焦无限远取景变化；④ 录制态齿轮 disabled。done：user 路测签收或开修复 round。

## 9. 实施期偏差透明声明（#17 / spec drift）

- [x] 9.1 纯函数从 spec 原 `resolveEffectiveQuality(Quality 域)` 改 `resolveEffectiveResolution(RecordingResolution 域)`——为可 JVM 单测（CameraX-free）。属 spec normative 措辞对齐（非 design Decision 修订），已 inline 改 spec 4K 降级 Requirement 三 scenario。
- [x] 9.2 新增 `implementation(libs.androidx.camera.camera2)` 到 feature/test（对焦 Camera2Interop 需编译期可见）。artifact 已在 core:camera implementation 引入（运行时 classpath 已有），非新下载依赖；同步更正 build.gradle.kts 旧注释（原写"暂不引入 camera-camera2"）。design Decision 4 早已选定 Camera2Interop，此为实施细节非 design drift。
- [x] 9.3 【road-test 反馈修订】Decision 1 形态 route 屏 → overlay 浮层（`RecordingSettingsOverlay`）。真机暴露 route 屏会丢横屏 + 看不到实时效果。已改码（CameraPreviewPage 内 showRecordingSettings state + 浮层；移除 NavHost route + navController 导航）+ 同步 design Decision 1 修订note + spec 入口 Requirement 三 scenario。属交互形态修订（非数据流/capability 边界），road-test-first 模式下 CC 自审 + 重验，未调 Opus 子 agent。曝光滑块 onValueChangeFinished 落库防 rebind 风暴。

## 10. Follow-up backlog（延期，不在本轮）

- [ ] 10.1 `upgrade-compilesdk-35` + 60fps：升 CameraX 1.4+ / compileSdk 35（工程级，AGP/Lint 连锁）。memo §4.3。
- [ ] 10.2 H.265 编码：运行时 `EncoderProfiles` 检测 + 不支持灰显。memo M4。
- [ ] 10.3 视频防抖：`StabilizationMode` 1.3.4 稳定性真机验证 + 开启降帧率评估。memo §2.2。
- [ ] 10.4 录制前快捷面板（C 方案）：录制按钮旁 BottomSheet 露常用 2-3 项。design Decision 1 Alternative C。
- [ ] 备注：全部 follow-up 详见 `docs/design/recording-params-config-deferred.md`。
