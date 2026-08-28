## Why

I round (`add-realtime-lap-delta`，archive/2026-05-02) 上线后，2026-05-05 W4 hotfix B 真机回放（vivo V2405A 泊寓 10 分钟回放数据）暴露 DELTA tile 两个体感故障：

- **现象 1**：DELTA 显示 `-125.20s` + 灰色（stale）持续显示
- **现象 2**：跑圈中段 DELTA 从 `~10s` 突然跳到 `~5s`（5 秒 step 跳变）

根因诊断（已 verify，独立于 W4 hotfix）：`RealtimeDeltaCalculator.projectDelta` 用 `prevMatchedIdx ± forwardWindowFrames=200` 做前向滑窗优化，**隐含"连续两帧 prevMatchedIdx 变化 < 200 帧"假设**。该假设在 4 个边界场景全部破裂：

1. **lap N → lap N+1 切换（非 PB）**：reference 末尾 idx → 0 idx 跨整个 reference（典型 1500+ 帧），forwardWindow ±200 搜不到正确 segment → 误匹配 reference 末段 → `bestElapsed ≈ lapDuration` → `deltaMs = currentLapElapsedMs(刚开圈≈0) - lapDuration ≈ -125_000ms`（即现象 1）
2. **GPS 信号丢失重连**：prevMatchedIdx 卡在丢失前位置，重连后 user 已在赛道其他位置 → 同样误匹配
3. **track 切换**：reference 是 trackA，user 切 trackB → 完全不匹配
4. **GPS jitter 大跳变**：单帧 lat/lon 大幅偏移触发 failoverDistanceM=50m → stale 进 prevDeltaMs cache（cache 是错误值时永久卡住）

`maybeRebuildReference` 仅在 reference 首建 / PB 刷新时 reset prevMatchedIdx；**lap 切换（非 PB）/ GPS lost / track 切换都不重置**。spec 也无任何反例 scenario 锁死这些边界（v3 高频盲点 #5 实战体现）。

**为什么不走临时补丁**：仅修 lap 切换 reset 一条会留另外 3 个边界 bug 持续复现，每次都得回到 calculator 加 reset 路径。根本问题是 prevMatchedIdx 优化的"连续性假设"未在 spec 锁死、也无统一 reset 触发契约。**走完整 OpenSpec 设计修复**，从 alternative 决策层面解决。

## What Changes

- **Modified**: `RealtimeDeltaCalculator.projectDelta` 搜索策略重设计
- **Modified**: `RealtimeDeltaState` 跨帧状态字段（可能新增 `lastSeenLapCount` / `lastSeenTrackId` 等用于 reset 触发）
- **Modified**: `TestSessionViewModel` 跨帧状态生命周期管理（新增 lap 切换 / GPS lost / track 切换触发的 reset 路径，OR 取决于 alternative 决策直接去掉跨帧 cache）
- **Modified**: `LapLiveScreen` DELTA tile 显示行为（避免在算法 stale 时把错误 cached `prevDeltaMs` 显示为灰色 -125 等明显不合理值；可能新增"算法刚 reset / 数据未足"占位态）
- **Modified spec**: `realtime-lap-delta` capability 的 Requirement: `projectDelta 纯函数` + Requirement: `ReferenceLapIndex 数据结构` + Requirement: `LapLiveStateDeriver 重做 deltaToBestMs 派生`
- **Performance baseline**: design 期实测华为 8KE0219522008434 全量 O(n) 投影耗时（reference 1500 帧 × 25Hz），决定是否保留 prevMatchedIdx 优化
- **真机 verify gate**: 华为 8KE02 回放泊寓数据，DELTA tile -125 灰色 + 10→5 跳两个现象消失

## Capabilities

### New Capabilities

无。

### Modified Capabilities

- `realtime-lap-delta`: 修订 `projectDelta 纯函数` + `ReferenceLapIndex 数据结构` + `LapLiveStateDeriver 重做 deltaToBestMs 派生` 三个 Requirement，引入跨帧状态 reset 触发契约 + 4 边界场景反例 scenario

## Impact

**受影响代码模块（按可能改动量从大到小）**：

- `feature/test/src/main/java/com/blazepush/feature/test/usecase/RealtimeDeltaCalculator.kt` —— 核心算法重写或 reset 入参重设计
- `feature/test/src/main/java/com/blazepush/feature/test/usecase/ReferenceLapIndex.kt` —— 可能新增 metadata 字段（如 trackId）支撑 reset 触发判定
- `feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt` —— `_realtimeDeltaState` lifecycle + reset 触发路径
- `feature/test/src/main/java/com/blazepush/feature/test/usecase/LapLiveStateDeriver.kt` —— `deriveAbnormalState` 可能补 stale-with-bad-cache 状态判定
- `feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/LapLiveScreen.kt` —— DELTA tile 显示行为（覆盖错误 cache 显示）
- `feature/test/src/test/java/com/blazepush/feature/test/usecase/RealtimeDeltaCalculatorTest.kt` —— 反例 scenario 测试 4 个边界场景
- `feature/test/src/test/java/com/blazepush/feature/test/usecase/ReferenceLapIndexTest.kt` —— 反例 scenario 测试

**协议兼容性**：

无 RaceChrono BLE 协议改动；无数据库 schema 改动；无跨发射端/接收端协议改动。**纯接收端 feature/test 模块内部算法 + UI 改动**。

**双端任务**：

仅接收端 gps-app（feature/test）。发射端 simulator 0 改动。

**依赖外部 round**：

- 不阻塞 hardening round (`phase1-hardening-w2-w3-w4-mimo-debt`)：本 round 与 hardening scope 函数级 0 重叠（hardening 修 W2/W3/W4 工件级 + chart 组件 silent bug + governance；本 round 修 RealtimeDeltaCalculator 算法）
- 不阻塞 W4 hotfix B commit：W4 hotfix B 改动在 `TestSessionViewModel.kt:347-358`（cleaned 副本字段集合），与本 round 改动函数 (`updateRealtimeDelta` / `_realtimeDeltaState` lifecycle) 不重叠
- 与 follow-up `fix-gps-data-filter-warmup-sentinel` (Bug X) / `fix-gps-data-filter-mid-stream-outlier` (Bug Y) 无依赖（filter bug 是上游数据流，本 round 是下游算法消费侧；但 Bug Y 修复后中段"10→5 跳"现象的频次会进一步降低，可能影响真机 verify 信号显著度）
