# Tasks

## 0. apply 前自查（#3/#14/#16/#17）
- [ ] 0.1 #3 grep 锚点：core.domain.permission 包实际类（PermissionRequestOutcome / RequiredBluetoothPermissions）+ settings 模块注册机制 + libs.versions.toml 结构 + app build.gradle 依赖块 + MainActivity 权限流（grep RequiredBluetoothPermissions / PermissionRequestOutcome / RequestMultiplePermissions 确认范式）
- [ ] 0.2 #16：本 round 不改公共数据契约/不动圈速链路；新 module + 权限扩展，verify 0 触碰 LapTelemetry/reader/binary
- [ ] 0.3 真机配合声明：本 round 真机验证 CAMERA+RECORD_AUDIO 三态流，需 user 手机（vivo 10AF5T0XE3004ZX 或华为）

## 1. 新建 core:camera module
- [ ] 1.1 建 `core/camera/build.gradle.kts`（mirror core/data 的 namespace/compileSdk/minSdk/kotlin 配置；android library）
- [ ] 1.2 `settings.gradle(.kts)` 注册 `include(":core:camera")`（确认现有模块注册写法后对齐）
- [ ] 1.3 `core/camera/src/main/AndroidManifest.xml`（空骨架 + 后续 uses-feature 视放 app 还是 module）

## 2. CameraX 依赖
- [ ] 2.1 `gradle/libs.versions.toml` [versions] 加 `cameraX = "1.3.x"`（选与 AGP 8.5.1/Kotlin 1.9.x 兼容稳定版）
- [ ] 2.2 [libraries] 加 5 件：androidx-camera-core / camera-camera2 / camera-lifecycle / camera-video / camera-view
- [ ] 2.3 `core/camera/build.gradle.kts` implementation 这 5 个；app build.gradle 加 `implementation(project(":core:camera"))`

## 3. manifest 权限
- [ ] 3.1 app `AndroidManifest.xml` 加 `<uses-permission CAMERA>` + `<uses-permission RECORD_AUDIO>`（紧随现有 BLE/Location 块）
- [ ] 3.2 加 `<uses-feature android:name="android.hardware.camera.any" android:required="false" />`

## 4. 权限流（复用 core.domain.permission）
- [ ] 4.1 `core/domain/.../permission/RequiredCameraPermissions.kt`：`forSdk(sdkInt): List<String>` = [CAMERA, RECORD_AUDIO]（mirror RequiredBluetoothPermissions）
- [ ] 4.2 相机权限请求 Composable / use-case（复用 PermissionRequestOutcome 三态 + RequestMultiplePermissions launcher；permanent-deny 跳设置 Intent）
- [ ] 4.3 hasCamera 可用性查询（CameraX ProcessCameraProvider，无相机机型 → 录制 disabled）—— 仅查询不开相机
- [ ] 4.4 **本 round 不接线到启动点**；接线点（preview/开关）留 round 2/5 注释 TODO

## 5. 测试
- [ ] 5.1 `RequiredCameraPermissionsTest`：forSdk 返回 [CAMERA, RECORD_AUDIO]（纯函数单测）
- [ ] 5.2 权限三态分流单测（复用 PermissionRequestOutcome，构造 grant/deny/permanent result 断言三态）
- [ ] 5.3 manifest contract test（grep CAMERA/RECORD_AUDIO/uses-feature 字面量防回退）

## 6. 编译 + 真机
- [ ] 6.1 `:core:camera:compileDebugKotlin` + `:app:assembleDebug` BUILD SUCCESSFUL（CameraX 依赖 resolve）
- [ ] 6.2 **真机三态验证（user 配合）**：触发请求 → grant（AllGranted）/ deny（Denied 可重试）/ permanent-deny（跳设置）；确认 app 启动不弹相机权限（懒请求）

## 7. follow-up（不在本 round）
- [ ] 7.1 camera-preview-in-laplivescreen（round 2）：PreviewView 嵌 LapLiveScreen 横屏 + 接线相机权限请求点
- [ ] 7.2 camera-recording-and-gps-sync（round 3）：VideoCapture + 首帧 wallClock 锚定
- [ ] 7.3 restore-strict-migrations-pre-release：Phase 2 session-video-metadata-persist schema 改前置（见 phase1.yaml disposition）
