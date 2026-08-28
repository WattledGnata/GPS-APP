# camera-permission 规格增量

## ADDED Requirements

### Requirement: 相机+音频权限流（复用 core.domain.permission 范式，懒请求）

app SHALL 提供 CAMERA + RECORD_AUDIO 双 dangerous 权限的请求与三态处理流，复用既有 `core.domain.permission` 基础设施，并在录制相关功能触发点懒请求（不在 app 启动强请求）。

实现 MUST 满足：

1. **权限集**：`RequiredCameraPermissions.forSdk(sdkInt)` MUST 返回 `[CAMERA, RECORD_AUDIO]`（L0-2 录音频已定）。
2. **复用分流**：MUST 复用 `PermissionRequestOutcome.from(perms, result)` 的 AllGranted / Denied / PermanentlyDenied 三态，不新写权限分流逻辑。
3. **懒请求**：相机权限 MUST NOT 在 app 启动（MainActivity 现有 BLE/Location 流）时请求；在录制相关入口（round 2 preview / round 5 开关）触发时请求。本 round 只建流组件不强制接线启动点。
4. **并存**：相机权限流 MUST 与现有 BLE/Location 权限流并存、互不污染（独立 launcher / 独立 RequiredXxxPermissions）。
5. **manifest**：MUST 声明 CAMERA + RECORD_AUDIO uses-permission + `uses-feature camera.any required=false`。
6. **模块边界**：CameraX 依赖 MUST 只在 `core:camera` 模块；本 round MUST NOT 开相机预览/录制。

#### Scenario: 全部授权
- **GIVEN** 用户在录制入口触发相机权限请求
- **WHEN** CAMERA + RECORD_AUDIO 都 grant
- **THEN** `PermissionRequestOutcome.from()` 返回 `AllGranted`
- **AND** 录制功能可用（后续 round 接）

#### Scenario: 反例——本次拒绝
- **GIVEN** 用户拒绝其中一个权限（非永久）
- **THEN** outcome 返回 `Denied`（可再次请求）
- **AND** MUST NOT 崩溃 / MUST NOT 误判为 AllGranted

#### Scenario: 反例——永久拒绝跳设置
- **GIVEN** 用户勾"不再询问"永久拒绝
- **THEN** outcome 返回 `PermanentlyDenied`
- **AND** 流 MUST 引导跳 app 设置页（不再弹系统框）

#### Scenario: 反例——相机权限 MUST NOT 在 app 启动强请求
- **GIVEN** 不使用视频功能的用户启动 app
- **THEN** 启动流程 MUST NOT 弹 CAMERA/RECORD_AUDIO 请求（只有 BLE/Location）
- **AND** 若实现误在启动请求相机权限，该 scenario fail
