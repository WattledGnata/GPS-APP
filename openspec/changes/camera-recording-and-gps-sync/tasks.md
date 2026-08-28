# Tasks：camera-recording-and-gps-sync

## T1：core/camera build.gradle.kts — camera-video 改为 api

**文件**：`core/camera/build.gradle.kts`  
**锚点**：`implementation(libs.androidx.camera.video)` → 改为 `api(libs.androidx.camera.video)`  
**Done**：`grep 'api.*camera.video' core/camera/build.gradle.kts` 命中 1 次。

- [x] 修改 core/camera/build.gradle.kts

## T2：RecordingConfig + RecordingResolution

**文件**：`feature/test/src/main/java/com/blazepush/feature/test/recording/RecordingConfig.kt`（新建）  
**内容**：`enum class RecordingResolution { FHD_1080P }` + `data class RecordingConfig(resolution, targetFps)`  
**Done**：文件存在 + 编译过。

- [x] 新建 RecordingConfig.kt

## T3：RecordingState sealed class

**文件**：`feature/test/src/main/java/com/blazepush/feature/test/recording/RecordingState.kt`（新建）  
**内容**：sealed class + Idle / Recording(startedAtWallClock, sessionId?) / Stopping / Error(message)  
**Done**：文件存在 + 编译过。

- [x] 新建 RecordingState.kt

## T4：VideoTelemetrySync 纯函数

**文件**：`feature/test/src/main/java/com/blazepush/feature/test/recording/VideoTelemetrySync.kt`（新建）  
**内容**：`object VideoTelemetrySync` 含 `frameWallClock` + `findNearestSampleIndex`  
**Done**：文件存在 + 编译过。

- [x] 新建 VideoTelemetrySync.kt

## T5：VideoTelemetrySync 单测

**文件**：`feature/test/src/test/java/com/blazepush/feature/test/recording/VideoTelemetrySyncTest.kt`（新建）  
**锚点**：specs.md MUST 5 Scenario 1-7 全覆盖  
**Done**：`gradle :feature:test:testDebugUnitTest --tests "*.VideoTelemetrySyncTest" --offline` 绿（13 cases 全过）。

- [x] 新建 VideoTelemetrySyncTest.kt

## T6：CameraRecordingEngine

**文件**：`feature/test/src/main/java/com/blazepush/feature/test/recording/CameraRecordingEngine.kt`（新建）  
**锚点**：
- `VideoCapture<Recorder>` + `Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.FHD))`
- ~~Camera2Interop 设 fps 30~~（CameraX 1.3.4 Recorder.Builder 不实现 ExtendableBuilder，不可用；fps 由设备决定；design.md risks 已更新）
- `withAudioEnabled()`
- `VideoRecordEvent.Start` → `System.currentTimeMillis()` 取 wallClock
- `VideoRecordEvent.Finalize` → attachVideoToSession / 孤立 WARN
- `StateFlow<RecordingState>` 暴露
- `filesDir/video/` 目录创建
- 全部 FileLogger 埋点（MUST 7 清单）

**Done**：文件存在 + `gradle :feature:test:compileDebugKotlin --offline` 零错误。

- [x] 新建 CameraRecordingEngine.kt

## T7：RecordableCameraPreview Composable

**文件**：`feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/RecordableCameraPreview.kt`（新建）  
**内容**：`AndroidView(PreviewView)` + 同时绑 Preview + VideoCapture<Recorder>；引擎传入；`DisposableEffect` 内 `unbindAll` onDispose  
**Done**：文件存在 + 编译过。

- [x] 新建 RecordableCameraPreview.kt

## T8：TestSessionViewModel 加 public accessor

**文件**：`feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt`  
**锚点**：`private var activeLapSessionId: String? = null`（约 line 284）→ 加 `fun getActiveLapSessionId(): String? = activeLapSessionId`  
**Done**：`grep 'fun getActiveLapSessionId' feature/test/.../TestSessionViewModel.kt` 命中 1 次。

- [x] 修改 TestSessionViewModel.kt

## T9：LapLiveScreen CameraPreviewPage 接入

**文件**：`feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/LapLiveScreen.kt`  
**改动**：
- `LapLiveScreen` 加 `recordingEngine: CameraRecordingEngine = koinInject()` 参数
- `CameraPreviewPage` 加 `recordingEngine` + `sessionViewModel` 参数
- `RecordableCameraPreview` 替换 `CameraPreview`
- 叠加 start/stop 按钮 + 录制状态文字（无 early return）

**Done**：编译过 + 无 early return。

- [x] 修改 LapLiveScreen.kt

## T10：Koin 注册 CameraRecordingEngine

**文件**：`feature/test/src/main/java/com/blazepush/feature/test/di/AppModule.kt` + `app/src/main/java/com/blazepush/BlazePushApplication.kt`  
**Done**：`recordingModule` 加入 Koin startKoin 列表。

- [x] 修改 Koin module + Application

## T11：编译验证

**结果**：
- `:core:camera:compileDebugKotlin` BUILD SUCCESSFUL
- `:feature:test:compileDebugKotlin` BUILD SUCCESSFUL
- `:app:compileDebugKotlin` BUILD SUCCESSFUL
- `VideoTelemetrySyncTest` 13 cases BUILD SUCCESSFUL（全绿）

- [x] 运行编译 + 单测

## T12：#16 跨 round drift 验证

`attachVideoToSession(sessionId: String, videoFilePath: String, videoStartedAtWallClock: Long)` 签名与 round 2 实现完全匹配（已在 CameraRecordingEngine.kt Finalize 分支调用，参数顺序一致）。

- [x] drift 验证

## §10 Follow-up Backlog

- `round-5-rec-indicator-ui`：REC 红点精致 UI + 录制时长计数器（RecordingState.Recording.startedAtWallClock 可派生 elapsed）。
- `round-future-recording-settings`：分辨率/帧率设置屏接入 RecordingConfig（Camera2Interop fps 控制在 CameraX 1.5+ VideoCapture 层可能有更好支持）。
- `round-orphan-video-management`：孤立视频（sessionId=null）的管理 UI（手动关联 / 删除）。
- `round-thermal-throttling-guard`：发热超温降级保护（监测 ThermalManager 状态，触发自动停录）。
