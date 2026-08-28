## Why

Phase 2 视频管线第一刀。app-first 优先级（2026-05-30 user 拍板：手机拍摄优先服务，GoPro 透明层后话）+ L0 全定（横屏 / 录音频 / 有预览，见 `docs/design/phase2-video-pipeline-entry-readiness.md` §9）。

当前**摄像头绿场**（grep 核实生产源码 0 camera 命中）。要录视频，第一步是打通"能开相机 + 拿到权限"的地基——CameraX 依赖 + CAMERA + RECORD_AUDIO 双 dangerous 权限运行时流 + 新 module 隔离 camera 关注点。本 round **只打通权限 + 模块骨架，不预览、不录制、不持久化**（那是 round 2/3）。

**复用既有基础设施（核实）**：`core.domain.permission` 已有完整权限流范式——`PermissionRequestOutcome.from(perms, result)`（AllGranted/Denied/PermanentlyDenied 分流）+ `RequiredBluetoothPermissions.forSdk(sdkInt)` + MainActivity `PermissionRequestScreen` 用 `RequestMultiplePermissions()` launcher。相机权限**扩展这套**（加 `RequiredCameraPermissions`），不重造轮子，与现有 BLE/Location 流并存。

## What Changes

- 新建 `core:camera` module（gradle 模块 + manifest + 骨架）。
- `gradle/libs.versions.toml` + 模块 build.gradle 引入 5 个 CameraX 依赖（camera-core / camera-camera2 / camera-lifecycle / camera-video / camera-view）。
- app manifest 加 `CAMERA` + `RECORD_AUDIO`（L0-2 录音频）uses-permission + `uses-feature camera`（required=false，无相机机型仍可装但录制 disabled）。
- `core.domain.permission` 加 `RequiredCameraPermissions.forSdk()`（CAMERA + RECORD_AUDIO）+ 复用 `PermissionRequestOutcome` 分流。
- 相机权限请求流（request → granted/denied/permanently-denied → 跳设置降级），与现有 BLE/Location 流并存、不互相污染。

**不做（round 2/3）**：PreviewView 嵌入（camera-preview-in-laplivescreen）/ VideoCapture 录制（camera-recording-and-gps-sync）/ Room video 字段（session-video-metadata-persist）。

## Impact

- 新增 module `core:camera`；改 `settings`（注册模块）+ `libs.versions.toml` + app `build.gradle`（依赖 core:camera）+ app `AndroidManifest.xml`（权限）+ `core.domain.permission`（加 RequiredCameraPermissions）+ 可能 MainActivity 或新建相机权限入口。
- **真机依赖**：CAMERA/RECORD_AUDIO 是 dangerous 权限，必须真机验证 grant/deny/permanent-deny 三态流（模拟器相机不可靠）。
- 公共协议 0 改动；圈速链路 0 改动；Room schema 0 改动。
