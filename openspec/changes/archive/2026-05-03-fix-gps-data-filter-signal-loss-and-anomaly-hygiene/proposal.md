# fix-gps-data-filter-signal-loss-and-anomaly-hygiene

战役 C filter 夯实，闭环 attack-backlog **A12 / A13 / A14** 三条。

## Why

对抗 review `2026-04-22-lap-timing-and-gps-adversarial-review.md` § 2.4 / 2.5 / 2.6 揭示
`GpsDataFilter.process` 三个联动问题：

### A12：信号丢失重置顺序错

当前实现顺序：

```
1. calculateAcceleration(raw)          // 用旧 previousRaw
2. isPhysicalConstraintViolation(raw)  // 用旧 previousRaw
3. 信号丢失重置（dt > 0.2s → previousRaw = null）
```

`calculateAcceleration` 和 `isPhysicalConstraintViolation` 在"重置之前"用的是
**隔了大半秒的旧 previousRaw**：
- `isPhysicalConstraintViolation` 的 `maxDelta = 90 × dt`，`dt` 大（0.5s, 1s）→
  `maxDelta = 45 / 90 km/h`，**只要速度跳变小于 90 km/h 就判"非异常"** —— 完全失效
- `calculateAcceleration = dv / dt`，大 dt 把真实的跳变稀释成小加速度，下游触发
  条件（`consecutiveTriggerCount` 基于阈值）会漏触发或误触发

**后果**：信号丢失（隧道出入口、锁星失败）正好是最容易产生假速度的场景，filter
的保护反而**最弱**。

### A13：异常帧污染 `previousRaw`，一次漂点连累多帧

当前实现：`previousRaw = raw` 和 `previousPosition = raw.latitude to raw.longitude`
**无条件执行**，无论本帧 `isAnomaly` / `isPositionAnomaly` 是否为 true。

复现路径：
1. 帧 N：GPS 跳飞 10m，被判 `isPositionAnomaly = true`
2. 帧 N+1：真实位置，但 `previousPosition` 是帧 N 的漂点
3. `checkPositionVelocityConsistency` 算出的 `distanceM` 是"漂点 → 真实点"，
   `vImpliedKmh` 爆表 → 帧 N+1 也被误判异常
4. 链式污染直到漂点被窗口挤出

**后果**：`confidence` 连续多帧走低 UI 质量灯黄/红，实际数据良好；加减速测试
触发判定基于 `filteredData.acceleration`，漂点阶段触发被污染。

### A14：`isAnomaly` 分支体等价，语义冗余

```kotlin
val outputSpeed = when {
    isAnomaly && speedWindow.size >= 3 -> {
        speedWindow.median()        // 分支 A
    }
    speedWindow.size >= 3 -> {
        speedWindow.median()        // 分支 B，与 A 完全相同
    }
    else -> raw.speed
}
```

两个分支体完全相同，`isAnomaly` 在 `outputSpeed` 层**无行为差异**。现在的语义是
"isAnomaly=true 也走 median"，与 spec（`docs/superpowers/specs/2026-03-21-gps-data-filter-design.md`）
声称的"异常时用窗口内非异常点的中位数"脱节。

更糟：`speedWindow.add(raw.speed)` 在 `isAnomaly=true` 时照加 → **异常帧进入窗口**，
后续几帧 median 被污染。`isAnomaly` 字段的唯一影响是 `calculateConfidence(confidence × 0.5)`。

### A12 → A13 依赖关系

A13 要求"异常帧不更新 previousRaw"。但若 A12 没修（重置顺序保持旧版），连续异常
帧 + 不更新 previousRaw = **previousRaw 永远停在旧值**，`dt` 越拉越大，
`maxDelta = 90 × dt` 爆炸，`isPhysicalConstraintViolation` 永远判非异常 —— 反而**更糟**。

所以 **必须 A12 先修**（重置逻辑前置、`previousRaw == null` 时 `calculateAcceleration` /
`isPhysicalConstraintViolation` 早退返回 `0.0 / false`），A13 才能安全修
（异常帧不更新 previousRaw 的同时，长期无更新时 dt 守卫会把 "previousRaw 永远
停在旧值" 退化到"首样本"语义，无副作用）。

## What

### 1. A12：重置顺序前置

改 `process(raw)` 的语句顺序：

```
BEFORE:
1. calculateAcceleration          // 用旧 previousRaw
2. isPhysicalConstraintViolation  // 用旧 previousRaw
3. 信号丢失重置

AFTER:
1. 信号丢失重置（dt > 0.2s → previousRaw = null, previousPosition = null）
2. calculateAcceleration          // 重置后 previousRaw == null 则早退返回 0.0
3. isPhysicalConstraintViolation  // 重置后 previousRaw == null 则早退返回 false
```

- `calculateAcceleration` 已有 `prev ?: return 0.0` 早退（无需改）
- `isPhysicalConstraintViolation` 已有 `prev ?: return false` 早退（无需改）

### 2. A13：异常帧不更新 previousRaw / previousPosition

```kotlin
BEFORE:
previousRaw = raw
previousPosition = raw.latitude to raw.longitude

AFTER:
if (!isAnomaly && !isPositionAnomaly) {
    previousRaw = raw
    previousPosition = raw.latitude to raw.longitude
}
```

**注意依赖**：A12 先修保证"连续异常 + 不更新 previousRaw = dt 越大越走 A12
重置" —— dt > 200ms 自动触发 previousRaw = null 退化到"首样本"状态，避免
previousRaw 永远停在旧值。

### 3. A14：简化分支 + 异常帧不入窗口（选方案 a：简化）

```kotlin
BEFORE:
val outputSpeed = when {
    isAnomaly && speedWindow.size >= 3 -> speedWindow.median()
    speedWindow.size >= 3 -> speedWindow.median()
    else -> raw.speed
}
speedWindow.add(raw.speed)  // 无条件

AFTER:
val outputSpeed = if (speedWindow.size >= 3) speedWindow.median() else raw.speed
if (!isAnomaly) {
    speedWindow.add(raw.speed)
}
```

同理 `latWindow / lonWindow / bearingWindow` 异常帧（含 `isPositionAnomaly`）不入窗口。

**选方案 a（简化）而非方案 b（差异化 `lastStableSpeed`）**：
- 简化不引入新状态（`lastStableSpeed` 需持久化）
- `isAnomaly` 字段通过 `calculateConfidence` 降低 confidence 仍反映
- 窗口纯度更重要（短期污染在 median 已缓解，但长期纯度只能靠不 add）

## Impact

### 协议与数据模型

- **不改** `GpsData` / `FilteredGpsData` / `GpsDataFilter` 公共 API
- 只改 `GpsDataFilter.process` 内部执行顺序 + 窗口 add 条件

### 受影响模块

| 模块 | 文件 | 动作 |
|---|---|---|
| filter | `core/domain/src/main/java/com/blazepush/core/domain/usecase/GpsDataFilter.kt` | `process` 内部重排 + 异常帧守卫 |
| 测试 | `core/domain/src/test/java/com/blazepush/core/domain/usecase/GpsDataFilterTest.kt` | 新增 4 条 A12/A13/A14 回归 |

### 行为变更

| 场景 | Before | After |
|---|---|---|
| 信号丢失 > 200ms 后首帧 | `isAnomaly` 可能判错（maxDelta 巨大） | `isAnomaly = false`（previousRaw 已重置，早退） |
| 信号丢失后首帧 `acceleration` | 可能把跨大 dt 的速度差计入 | `0.0`（previousRaw null 早退） |
| 一次 spike + 下一帧恢复 | 恢复帧被 spike 污染，误判异常 | 恢复帧不受 spike 影响（spike 未更新 previousRaw） |
| 异常帧的窗口中位数输出 | 异常帧值进窗口，拉偏 median | 异常帧不入窗口，median 纯度更好 |
| 非异常路径 | 不变 | 不变（正常路径等价） |

### 风险与缓解

| 风险 | 缓解 |
|---|---|
| A12 先行但 A13 延后：行为无回退，只是异常帧仍污染 previousRaw（现状） | 本 change 一次性同批修 A12 + A13，无中间态 |
| A13 修了但 A12 没修：前述 previousRaw 永远旧值问题 | Spec 明确声明 A13 依赖 A12；tasks 强制 A12 先改 |
| "连续 N 帧异常" 时 previousRaw 被"锁死"：由 A12 的 dt > 200ms 重置兜底（下一帧相对 previousRaw 的 ts 差只要 > 200ms，就走 A12 重置分支 → previousRaw = null → 本帧 dt 守卫自然早退） | 设计上闭环，无需引入新"连续异常计数器"。但加回归测试锁定该兜底行为 |

### 不在本 change 范围

- A43 `circularMedian` 命名与实现不符（改名为 `circularMean`）—— 独立小 change
- A44 `checkPositionVelocityConsistency` 跨经度不处理 —— 独立小 change
- 接入 `AnomalyDetector` 给更精细的异常原因分类（对抗 review 2.9）—— 需要 A30 决策

## Alternatives

### A：A14 选"差异化"方案（维护 `lastStableSpeed`）

**拒绝理由**：引入 `lastStableSpeed: Double?` 新状态字段 + `reset()` 必须清理 +
语义复杂（"上次非异常的 outputSpeed" 本身还要看 window 是否够）。方案 a 简化
路径已经通过 window 纯度 + `confidence × 0.5` 降权达到等效效果，无需新状态。

### B：一次 change 合并 A12/A13/A14 + A43/A44（filter 大清理）

**拒绝理由**：A43（circularMedian 改名）涉及多调用点，A44 引入 `wrappedDeltaLon`
是逻辑变更，都不应与 A12/A13 的顺序依赖强耦合。**单一职责**：本 change 只做
"信号丢失 + 异常帧隔离"。

## Non-goals

- 不改 filter 公共 API（构造参数、`process` / `reset` 签名）
- 不改 median 算法（`circularMedian` / `median` 内部实现）
- 不改 `checkPositionVelocityConsistency` 的容差 / 阈值
- 不接入 `AnomalyDetector` / `DataInterpolator`（孤立代码清理是 A30 独立战役）
- 不修 A12 / A13 依赖关系之外的 filter 问题
