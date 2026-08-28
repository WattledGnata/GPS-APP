## Why

V2 性能测试执行屏（`feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/TrackTechTestExecutionScreen.kt:74-85`）的进度条采用纯 **speed-based 线性映射**：

```kotlin
TestMode.Acceleration -> (speed / 100.0).toFloat().coerceIn(0f, 1f)
TestMode.Braking -> ((start - speed) / start.coerceAtLeast(1.0)).toFloat()
```

用户反馈："性能测试页面的进度条经常走一半儿了它才开始读条" —— 实际感知断层来自 3 个叠加因素：

1. 用户按 START 后到车真起步之间有 0.5–1 秒迟滞（松刹车踩油门），这段时间 speed = 0 → progress = 0%，**进度条死气沉沉**
2. 起步段 GPS Doppler 信号噪声大（< 10 km/h），叠加 GpsDataFilter 中位数滤波 → 速度数据在 0-3 km/h 浮动 → progress < 3% 看起来不动
3. 用户全神贯注盯前方，眼角余光发现进度条"跳"了一下时通常已经在 30-50% 段（中段加速最快） → 体感"走一半儿才开始读条"

加速曲线本身在 0-100 km/h 各 25% 速度区间的耗时差距其实没那么大（每段约 1 秒，共 5 秒），所以**真正的体感问题是"按 START 后不到 1 秒的死气期 + 起步阶段的几乎不动"**，不是中后段。

本 round 用 **B 方案：阈值起跳 + 起步状态文案** 解决这个体感断层，最小代价（一个 if 分支 + 文案 + 一个 progress < 阈值时不渲染进度条），不动现有 speed-based progress 算法本身。

## What Changes

- 修改 `feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/TrackTechTestExecutionScreen.kt`：
  - 在 `progress` 计算后追加一个 UI 状态派生：
    - `speed < LAUNCH_SPEED_THRESHOLD_KMH`（默认 `3.0`）→ UI 进入 `WAITING_FOR_LAUNCH` 状态：进度条容器渲染但 fill `width = 0`，文案区显示 `"WAITING FOR LAUNCH"`（cyan UiTextLabel）
    - `speed >= LAUNCH_SPEED_THRESHOLD_KMH` → 正常 progress 渲染（沿用原 speed-based 公式 + 百分比文案）
  - `LAUNCH_SPEED_THRESHOLD_KMH` 作为顶层 `private const val` 暴露在文件内，方便后续 tune（不引入 settings UI）
  - `Braking` 模式不需要起步阈值（按 START 时已 ≥100 km/h），逻辑跳过该分支 —— 仅 `Acceleration` 模式启用阈值
- 修改 `ProgressPanel`（line 470）签名：增加 `waitingForLaunch: Boolean` 参数；当 true 时：
  - 进度条 fill `Modifier.fillMaxWidth(0f)` —— 不绘制 fill
  - 显示文案改 `"WAITING FOR LAUNCH"`（cyan，UiTextLabel），覆盖原 `displayPct%` 数字
- 新增轻量单元测试 `TrackTechTestExecutionProgressTest.kt`（纯 JVM，不依赖 Compose runtime）：
  - 提取 progress 派生与 waitingForLaunch 派生为 pure function（在文件内 `internal`），单测覆盖：
    - 加速模式 speed = 0 → progress = 0f, waitingForLaunch = true
    - 加速模式 speed = 2.9 → waitingForLaunch = true
    - 加速模式 speed = 3.0 → waitingForLaunch = false, progress ≈ 0.03
    - 加速模式 speed = 50 → waitingForLaunch = false, progress = 0.5
    - 加速模式 speed = 100 → progress = 1.0
    - 加速模式 speed = 120（超过 100，clamp）→ progress = 1.0
    - 制动模式 speed = 100 (start) → waitingForLaunch = false, progress = 0
    - 制动模式 speed = 50 → progress = 0.5
    - 制动模式 speed = 0 → progress = 1.0

不做的事（明确 out-of-scope）：

- **不**改 V1 `TestExecutionScreen.kt:264 ProgressBar`（V1 已 dead code，待 cleanup round 整组删）
- **不**改 progress 算法本身（不引入时间基准、不引入非线性 sqrt 映射 —— 这些都是 follow-up backlog 项，本 round 只解决"起步死气"体感断层）
- **不**改阈值默认值的 tune 工具（不引入 settings UI / runtime 调整）；后续如发现 3 km/h 不合适再 tune `LAUNCH_SPEED_THRESHOLD_KMH` 常量
- **不**做制动测试的等价"等待按下刹车"状态文案（按 START 时已是 100+ km/h，制动一开始 progress 就动，无死气期）
- **不**改 `TestState.Running` 触发条件（即不解决"按 START → first GPS frame"的延迟问题，那是 BLE / GPS 链路问题，本 round 不涉及）

## Capabilities

### New Capabilities

- `test-execution-launch-threshold`: V2 性能测试执行屏起步阈值文案契约 —— 加速测试 speed 低于 `LAUNCH_SPEED_THRESHOLD_KMH` 时显示 `"WAITING FOR LAUNCH"` 文案，进度条 fill 不绘制；speed ≥ 阈值后回到正常 progress 渲染

### Modified Capabilities

无。原 progress 算法（`speed / 100`）保持不变，仅在 UI 渲染层加阈值切换。

## Impact

### 受影响代码

- **修改**：
  - `feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/TrackTechTestExecutionScreen.kt` —— `progress` 派生 + `ProgressPanel` 签名 + 文案分支
- **新增**：
  - `feature/test/src/test/java/com/blazepush/feature/test/ui/tracktech/TrackTechTestExecutionProgressTest.kt` —— pure function 单测

### 不受影响

- `core/*` 全部模块、`simulator/*` 全部模块
- `app/*`、其它 home screen
- 数据链路（`BluetoothDataSource` / `RaceChronoParser` / `GpsDataFilter`）—— 不动
- `TestState` 状态机 —— 不动
- 现有 progress 算法（`speed / 100`）—— 不动，仅外层加阈值切换
- V1 `TestExecutionScreen.kt:264 ProgressBar`（dead code，cleanup round 处理）

### 协议兼容性

无协议改动。

### 双端

仅接收端（gps-app）改动；发射端（simulator）不动。

### 多 change 并行协同

`TrackTechTestExecutionScreen.kt` 当前看板 §6 无其它 round 占用。本 round 独占该文件，无串行依赖。

### 测试影响

- 新增 1 个 pure-function 单测文件（~10 cases）
- 现有 `:feature:test:testDebugUnitTest` 全套 MUST 零回归
- 真机验证：华为 `8KE0219522008434` —— 跑加速测试观察"按 START → 起步阶段 → 中后段"三阶段进度条文案切换符合预期
