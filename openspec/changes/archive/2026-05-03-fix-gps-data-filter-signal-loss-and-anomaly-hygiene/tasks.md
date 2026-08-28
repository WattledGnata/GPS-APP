# 实施任务（严格顺序）

工作按依赖关系顺序执行，**A12 必须在 A13 之前完成并跑过测试**。A14 独立。

---

## 1. A12：信号丢失重置顺序前置

- [ ] 1.1 在 `core/domain/src/main/java/com/blazepush/core/domain/usecase/GpsDataFilter.kt` 的 `process(raw)` 方法里，把"信号丢失重置"块（`dtFromPrevious > 0.2` 时置 `previousRaw = null / previousPosition = null`）从当前位置（`calculateAcceleration` / `isPhysicalConstraintViolation` 之后）**上移**到它们**之前**。
- [ ] 1.2 确认 `calculateAcceleration(current)` 的 `prev ?: return 0.0` 早退分支存在（GpsDataFilter.kt 现有代码）—— 重置后 `previousRaw == null`，早退返回 0.0。无需改。
- [ ] 1.3 确认 `isPhysicalConstraintViolation(current)` 的 `prev ?: return false` 早退分支存在 —— 同理早退返回 false。无需改。
- [ ] 1.4 `checkPositionVelocityConsistency` 已自带 `previousPosition ?: return 1.0 to false` 和 `previousRaw ?: return 1.0 to false` 早退 —— 重置后自然走"新基准"分支，无需改。
- [ ] 1.5 **新增测试** `GpsDataFilterTest.A12_process_signalLossLongerThanThreshold_acceleratesFromNewBaseline`：
    - 喂 1 帧 `ts=0, speed=60, isTimeSynced=true` 建立 previousRaw
    - 喂 1 帧 `ts=500`（dt=500ms > 200ms）`speed=60, isTimeSynced=true`
    - 断言 `result.acceleration == 0.0`（重置后 calculateAcceleration 早退）
    - 断言 `result.isAnomaly == false`（重置后 isPhysicalConstraintViolation 早退）
- [ ] 1.6 **新增测试** `GpsDataFilterTest.A12_process_signalLossThenLargeSpeedJump_isNotSuppressedByStalePreviousRaw`：
    - 喂 1 帧 `ts=0, speed=10, isTimeSynced=true`
    - 喂 1 帧 `ts=500, speed=310, isTimeSynced=true`（300 km/h 跳变 + 500ms dt）
    - 断言 `result.isAnomaly == false`（首帧语义：跳变不被误判为物理异常，因为没有 prev 作为对比基准）
    - 断言 `result.acceleration == 0.0`（首帧）
    - 再喂 1 帧 `ts=540, speed=310, isTimeSynced=true`（40ms 正常间隔）
    - 断言 `result.acceleration == 0.0`（dv=0 / dt=0.04 = 0）
- [ ] 1.7 跑 `:core:domain:test --tests "*.GpsDataFilterTest"` 全绿后再进入第 2 步。

## 2. A13：异常帧不更新 previousRaw / previousPosition（**依赖 A12 完成**）

- [ ] 2.1 在 `GpsDataFilter.process` 的末尾状态更新处，把无条件赋值：
    ```kotlin
    previousRaw = raw
    previousPosition = raw.latitude to raw.longitude
    ```
    改为条件赋值：
    ```kotlin
    if (!isAnomaly && !isPositionAnomaly) {
        previousRaw = raw
        previousPosition = raw.latitude to raw.longitude
    }
    ```
- [ ] 2.2 **验证 A12 兜底**：连续多帧异常时，A12 的 `dt > 0.2s` 重置会在 200ms 无更新后触发 `previousRaw = null`，避免 previousRaw 永远停在旧值（详见 spec "连续异常不锁死 previousRaw" Scenario）。
- [ ] 2.3 **新增测试** `GpsDataFilterTest.A13_process_singleSpikeThenRecovery_doesNotLeakAnomalyToNextFrame`：
    - 喂 1 帧 `ts=0, speed=30, isTimeSynced=true` 建立 baseline
    - 喂 1 帧 `ts=40, speed=200, isTimeSynced=true`（速度跳变 170 km/h > maxDelta=90×0.04=3.6 km/h，触发 isAnomaly=true）
    - 断言该帧 `result.isAnomaly == true`
    - 喂 1 帧 `ts=80, speed=32, isTimeSynced=true`（恢复到合理值）
    - 断言该帧 `result.isAnomaly == false`（如果 spike 污染 previousRaw，本帧会被误判；修好后不污染）
- [ ] 2.4 **新增测试** `GpsDataFilterTest.A13_process_continuousAnomaly_previousRawEventuallyResetsByA12Guard`：
    - 喂 1 帧 `ts=0, speed=30, isTimeSynced=true` 建立 baseline
    - 连续 3 帧 `ts=40/80/120, speed=200`，都是异常，previousRaw 保持在 ts=0（A13 不更新）
    - 第 4 帧 `ts=500`（相对 previousRaw ts=0 差 500ms > 200ms，触发 A12 重置）
    - 断言第 4 帧走"新基准"路径：`acceleration == 0.0`、`isAnomaly == false`
- [ ] 2.5 跑 `:core:domain:test --tests "*.GpsDataFilterTest"` 全绿。

## 3. A14：简化 isAnomaly 分支 + 异常帧不入窗口（独立，可与 1/2 并行）

- [ ] 3.1 在 `GpsDataFilter.process` 中，把 `outputSpeed` 的 `when` 简化为：
    ```kotlin
    val outputSpeed = if (speedWindow.size >= 3) speedWindow.median() else raw.speed
    ```
- [ ] 3.2 把窗口 add 改为条件 add：
    ```kotlin
    if (!isAnomaly) {
        speedWindow.add(raw.speed)
    }
    if (!isPositionAnomaly) {
        latWindow.add(raw.latitude)
        lonWindow.add(raw.longitude)
    }
    bearingWindow.add(raw.bearing)  // bearing 不受 isAnomaly / isPositionAnomaly 影响；跨度由 circularMedian 自身保证
    ```
    （注：position 异常时经纬度窗口不 add；speed 异常时 speed 窗口不 add；bearing 不受这两类异常影响，按原逻辑加。）
- [ ] 3.3 窗口裁剪逻辑保持不变（超过 windowSize 移除最老）。
- [ ] 3.4 **新增测试** `GpsDataFilterTest.A14_process_anomalyFrame_doesNotPollutePosteriorMedianOutput`：
    - 喂 3 帧 `speed=60`（正常）填充窗口
    - 喂 1 帧 `speed=300`（异常）—— 断言不进 speedWindow
    - 喂 2 帧 `speed=60`（正常）
    - 断言 `result.speed`（median 输出）一直稳定在 60 附近，不被 300 拉偏
- [ ] 3.5 **新增测试** `GpsDataFilterTest.A14_process_positionAnomalyFrame_doesNotPollutePosteriorLatLonMedian`：
    - 同理喂正常 lat/lon → spike → 恢复，断言 median 稳定。

## 4. Spec 校验 + 文档

- [ ] 4.1 `openspec-chinese validate fix-gps-data-filter-signal-loss-and-anomaly-hygiene --strict` 通过。
- [ ] 4.2 跑 `:core:domain:test :feature:test:testDebugUnitTest :core:bluetooth:testDebugUnitTest` 全绿（确认无下游回归，尤其是战役 A 的 filter 零 delta 快照路径 `process_notTimeSynced_*` 仍通过）。
- [ ] 4.3 更新 `docs/superpowers/reviews/attack-backlog.md` 的 A12/A13/A14 状态为 🟢 `pending_review`，附 commit hash。

## 5. Commit 策略

**单 commit** 包含 A12 + A13 + A14 所有改动：
- 代码改动单一模块 `GpsDataFilter.kt`
- A12/A13 顺序依赖在 spec 里声明，但代码层面同批上线（单独 A12 后上线会让 filter 半新半旧易混淆）
- A14 与 A12/A13 在同一文件的相邻位置（`process` 方法内部）

建议 commit 消息：`fix(filter): 战役 C 信号丢失重置顺序 + 异常帧隔离（A12/A13/A14）`
