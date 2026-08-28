## Context

Lap Live 的驾驶 HUD 已有 DELTA、CURRENT、LAST、BEST，但没有当前速度或可持续增长的数据年龄。速度权威是 `TestSessionViewModel.filteredSpeedKmh`；心跳权威是当前连接代次的 Main 接收时刻与动态静默阈值。

## Goals / Non-Goals

**Goals:**

- 同屏显示可信速度、GPS 状态、Main Hz、卫星数、数据年龄和四个圈速指标。
- Main 新鲜且 readiness 为 ARMED 时显示滤波速度，包括可信的 0。
- Main stale、无 Main、BLE 断开或定位尚不可信时显示 `--`。

**Non-Goals:**

- 不修改门线、圈速判定、滤波算法、GPS readiness 状态机、视频、Session 生命周期或持久化。

## Decisions

### 1. Main freshness 复用动态 deadline

UI 使用 `elapsedRealtime - mainFrameReceivedAtElapsedRealtimeMs` 计算 age。仅当 BLE CONNECTED、当前代次已有 Main、`isStale == false` 且 age 小于动态 deadline 时视为新鲜，deadline 上限为 1000ms。

### 2. 可信速度需要新鲜 Main 与 ARMED

数值只复用 `filteredSpeedKmh`，不使用缓存的 `GpsData.speed`。等待 Main、定位中和恢复稳定阶段显示 `--`。

### 3. 使用纯 presentation mapper

纯 Kotlin mapper 输出 LIVE、ACQUIRING_FIX、STABILIZING、WAITING_MAIN、STALE、DISCONNECTED 及对应显示值，由 JVM 测试覆盖。

### 4. 中心速度岛与顶部心跳

速度岛位于四宫格中心，左右 tile 向外对齐；顶部单行显示状态、Hz、卫星和 age。BLE 断开仍使用既有硬中断提示。

## Risks / Trade-offs

- 低矮设备的最终可读性仍需横屏真机验收。
- 100ms UI ticker 只更新展示，不修改 GPS 接收、滤波、计时或遥测。

## Migration Plan

纯 UI 和 presentation 变更，无数据迁移；回滚代码即可恢复原 HUD。
