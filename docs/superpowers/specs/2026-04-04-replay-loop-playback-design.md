# Replay Loop Playback Design

**Goal:** 让 simulator 在 `REAL_TRACK_REPLAY` 场景下播放完当前 replay 资产后，自动从第一帧重新开始，形成连续循环播放。

## Background

当前 `SimulatorViewModel.startReplayDataUpdate()` 只会对 `ReplayPlaybackPlanner.plan(replay.samples)` 产出的 `frames` 执行一次顺序遍历。最后一帧发完后，协程结束，BLE 侧不再继续推进 replay。对当前调试场景来说，这会让真机观察窗口很短，重复验证时需要人工重新开始广播。

本次只解决“单个 replay 自动循环播放”问题，不扩展为播放列表、进度拖动、手动重置或多 replay 管理。

## Scope

### In scope
- `TestScenario.REAL_TRACK_REPLAY` 场景下自动循环播放当前 replay asset
- 保持现有 replay asset 加载方式不变
- 保持现有 `ReplayPlaybackPlanner` 输出格式不变
- 保持 `stopAdvertising()` / `setScenario()` 的取消语义有效
- 补最小单测覆盖 replay 播放到结尾后重新从第一帧开始的行为

### Out of scope
- 新增 UI 控件（循环开关、重置按钮、进度条）
- 新增手动重置逻辑
- 改 replay JSON schema
- 改 BLE 协议编码
- 改非 replay 场景的播放逻辑

## Recommended Approach

采用 **方案 A：在 `SimulatorViewModel.startReplayDataUpdate()` 外层增加循环**。

原因：
- 改动最小，职责最清晰
- 循环播放是运行时行为，放在 ViewModel 播放控制层最合适
- 不需要把“是否循环”下沉到 `ReplayPlaybackPlanner`
- 不会引入 BLE 重启或 UI 状态重建

## Design

### 1. Playback lifecycle

`startReplayDataUpdate()` 维持现有前置步骤：
1. 读取 asset JSON
2. 解析为 `ReplaySession`
3. 规划为 `frames`

变更点仅在发射阶段：
- 现状：`for (frame in frames)` 单次遍历后结束
- 目标：在协程存活期间持续重复遍历 `frames`

伪代码：

```kotlin
while (isActive) {
    for (frame in frames) {
        ensureActive()
        if (frame.delayMillis > 0) delay(frame.delayMillis)
        emit(frame)
    }
}
```

其中 `emit(frame)` 继续复用当前逻辑：
- `generator.applyReplaySample(frame.sample)`
- `generateGpsMainData()` / `generateGpsTimeData()`
- `manager.updateGpsData(...)`
- 更新 `_uiState`
- 输出 replay frame log

### 2. Loop boundary behavior

每轮结束后直接回到 `frames[0]`，不插入额外 pause，不补过渡帧。

这意味着：
- UI 和 BLE 数据会在圈尾后直接跳到圈头第一帧
- 这是预期行为，不额外平滑
- 对当前“反复调试单圈 replay”的目标是可接受的

### 3. Cancellation behavior

循环必须保持现有可取消性：
- `stopAdvertising()` 取消 `dataUpdateJob` 后，replay 循环停止
- `setScenario()` 切换场景时，旧 `dataUpdateJob` 先被取消，再启动新场景逻辑
- ViewModel 销毁时，协程取消后循环终止

因此实现里必须在循环边界或每帧前检查协程活跃状态，避免 cancel 后继续发射旧帧。

### 4. UI/state behavior

不新增 UI 控件。

现有 `SimulatorUiState` 保持不变：
- `currentSpeed`
- `currentLatitude`
- `currentLongitude`
- `satellites`
- `frequency`

这些值在每一轮 replay 的首帧重新写入，因此用户能直接看到 replay 自动重新开始。

## Test Plan

### Unit tests

在 simulator 单测里增加最小覆盖：
- 验证 replay 播放逻辑在到达结尾后不会自然停住，而是能重新回到第一帧继续发射
- 验证 cancel 后循环不会继续运行

优先复用现有 replay 相关测试风格，避免大规模重构。

### Verification commands

至少执行：
- `./gradlew :simulator:testDebugUnitTest --tests ...`
- `./gradlew :simulator:assembleDebug`

如需真机验证，再安装到讯飞设备并确认：
- 进入 replay 场景
- 开始广播
- 在一个完整圈结束后，速度/坐标重新跳回首帧并继续更新

## Risks

### Jump at loop boundary
- 风险：圈尾到圈头存在明显跳点
- 处理：本次接受该行为，不做平滑

### Non-cancellable looping bug
- 风险：如果未正确检查取消状态，可能在 stop 后继续发射
- 处理：循环里显式检查协程活跃状态，并用单测锁住

## Decision

本次采用：
- **自动循环播放**
- **不新增手动重置**
- **不新增 UI 控件**
- **不改 planner / asset schema**
