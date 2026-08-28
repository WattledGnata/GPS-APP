## Context

Phase 2 第一刀，绿场。L0 全定（横屏/录音频/有预览）。复用 `core.domain.permission` 既有权限范式。本 round 纯打通权限+模块骨架，不预览/不录制。复杂度 large/architectural（新 module + 多依赖集成 + 双 dangerous 权限真机踩坑）。

## Decisions

### Decision 1：新建 `core:camera` module（vs 塞进现有模块）
- **选**：新建 `core:camera`，隔离 camera 关注点 + CameraX 依赖只在此模块。
- **Alt A（拒绝）**：放 `feature:test` → camera 与圈速 UI 耦合，CameraX 依赖污染 feature 层。
- **Alt B（拒绝）**：放 `core:data` → camera 是采集/IO 设备，不属数据仓储层，错层。
- **rationale**：camera 是独立子系统，新模块边界清晰、依赖隔离、Phase 2 后续 round（preview/recording/resilience）都挂这；与 roadmap §3 underScoped1「新建 core:camera 模块（独立决策）」一致。**触发"引入新 module"强制升级场景，本 round 走 v3 标准/或 user 当时授权的 road-test-first**。

### Decision 2：CameraX（vs Camera2 / MediaRecorder）
- **选**：CameraX（camera-core/camera2/lifecycle/video/view 5 件套）。
- **Alt A（拒绝）**：Camera2 → 底层控制强但样板代码多、设备兼容性自己扛。
- **Alt B（拒绝）**：MediaRecorder + Camera1 → legacy，deprecated。
- **rationale**：CameraX lifecycle-aware + 处理设备 quirk + Preview/VideoCapture/ImageCapture use-case 统一绑定（round 2/3 直接复用），Google 官方推荐。代价：5 个依赖体量。

### Decision 3：权限流复用 `core.domain.permission` 既有范式
- **选**：加 `RequiredCameraPermissions.forSdk(sdkInt): List<String>`（CAMERA + RECORD_AUDIO）+ 复用 `PermissionRequestOutcome.from()` 的 AllGranted/Denied/PermanentlyDenied 分流 + `RequestMultiplePermissions()` launcher 范式（同 RequiredBluetoothPermissions）。
- **Alt A（拒绝）**：新写一套相机权限管理 → 与既有 BLE/Location 流重复，维护两套。
- **rationale**：基础设施现成（MainActivity:43-97 已用），扩展即可；CAMERA(API 1+) + RECORD_AUDIO(API 1+) 无 SDK 版本分支复杂度（不像 BLE 的 S+ 分流），forSdk 实现简单。

### Decision 4：权限请求入口位置 = 录制触发点懒请求（vs app 启动强请求）
- **选**：相机权限**不在 app 启动时强请求**（不阻塞非视频用户），而在**用户首次触发录制相关功能时懒请求**（round 5 录制开关 / 或 round 2 进 preview 时）。本 round 只建 `RequiredCameraPermissions` + 请求流组件，**接线点留给 round 2 preview**。
- **Alt A（拒绝）**：跟 BLE/Location 一起 app 启动强请求 → 不录视频的用户被无故要相机/录音权限，敏感+反感。
- **rationale**：视频是 opt-in 功能（L0 默认关），权限懒请求符合最小惊扰；BLE/Location 是圈速核心必需故启动请求，camera 不是。

### Decision 5：uses-feature camera required=false
- **选**：`<uses-feature android:name="android.hardware.camera.any" android:required="false" />`。
- **rationale**：无相机机型仍可装 app 用圈速功能，录制功能运行时 disabled（CameraX 查 hasCamera）。required=true 会让无相机设备无法装。

## Risks

- **双 dangerous 权限真机三态**：grant / deny（本次拒）/ permanently-denied（勾"不再询问"→ 只能跳设置）。mitigation：复用 `PermissionRequestOutcome` 三态分流 + permanent-deny 跳 app 设置页；真机逐态验证（vivo 权限弹窗行为 + 厂商 ROM 差异）。
- **CameraX 依赖版本兼容**：CameraX version 须与 AGP 8.5.1 / compileSdk / Kotlin 1.9.x 兼容。mitigation：design 期选稳定版（如 1.3.x），libs.versions.toml 集中管理，编译验证。
- **新 module gradle 配置踩坑**：namespace / compileSdk / minSdk 对齐 + Koin 注入边界。mitigation：mirror 现有 core:data / core:domain 模块的 build.gradle.kts 配置。
- **round 边界纪律**：本 round MUST NOT 开相机/预览/录制（那会提前触碰 round 2/3 + 真机录制复杂度）。验证只到"权限拿到 + 模块编译 + CameraX 依赖 resolve + hasCamera 查询可用"。
