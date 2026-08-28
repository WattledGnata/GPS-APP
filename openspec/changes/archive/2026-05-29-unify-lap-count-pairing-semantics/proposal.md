# Proposal: unify-lap-count-pairing-semantics

## Why

### 问题溯源

圈速链路中"哪一圈是第 N 圈"（圈编号 / lap pairing identity）目前存在**三套独立派生实现**，分别由不同 round 在不同时期落地，彼此之间没有共享的"圈配对"权威定义。这意味着同一个 session 的同一批 crossing，在不同代码路径上可能被配对成**不同的圈**或**不同的圈编号**。

三个派生站点（全部已 grep 核实，行号锚点见下）：

| 站点 | 代码位置 | filter | 排序键 | 配对方式 | 驱动什么 |
|---|---|---|---|---|---|
| **A. endSession（持久化 lapCount）** | `core/data/.../repository/TelemetryRepository.kt:164-168` | `accepted && gateType=StartFinish` | `crossingTimestampMs`（GPS 协议时钟） | `zipWithNext` → `durations.size` | 持久化 `entity.lapCount`（Records 列表 / detail Overview） |
| **B. deriveDetailMetrics（detail 屏圈列表）** | `feature/test/.../ui/tracktech/LapSessionDetailScreen.kt:471-495` | `gateType=StartFinish`，再分 `accepted` | `crossingTimestampMs`（GPS 协议时钟） | `zipWithNext` → `lapNumber = idx + 1` | **用户看到并将来要点击的 "Lap N" 行** |
| **C. getLapTelemetry（单圈 telemetry 切片）** | `core/data/.../repository/TelemetryRepository.kt:275-280` | `accepted && gateType=StartFinish` | **`crossingWallClockTimestampMs`**（接收侧真壁钟） | `acceptedSF[lapIndex]..[lapIndex+1]` | **点击 "Lap N" 后打开的 telemetry 切片** |

另有第四个**计数（非配对）**站点：

| 站点 | 代码位置 | 派生方式 | 驱动什么 |
|---|---|---|---|
| **D. finishActiveLapSession（Snackbar 实时计数）** | `feature/test/.../viewmodel/TestSessionViewModel.kt:567-568` | `completedLaps.filter { qualityFlags.isEmpty() }.size`（in-memory + qualityFlags 过滤） | session 结束 Snackbar `"X laps"` 文案 |

### 当前 baseline 的两类分歧

**分歧一（关键 · production correctness · 圈配对身份不一致）：B 与 C 排序键不同。**
- B（用户点击的圈列表）按 `crossingTimestampMs`（GPS 协议时钟，`mod 3,600,000` 解码 + `hourStartMillis` 切换）排序配对。
- C（点击后打开的 telemetry）按 `crossingWallClockTimestampMs`（接收侧 `System.currentTimeMillis()` 真壁钟）排序配对。
- 两套时钟域**不是单调一致的**：GPS 协议时钟跨整点会回绕（mod 一小时），失锁/重同步会让 `hourStartMillis` 跳变；而 wallClock 是接收侧本地壁钟。当同一批 accepted SF crossing 在两套时钟下产生**不同的排序顺序**时，B 的第 N 行与 C 的第 N 圈指向**不同的物理圈** → 用户点 "Lap 3" 打开的是别的圈的曲线/轨迹。
- 这是已归档 round `lap-data-readers`（archive/2026-05-04）spec 中已明文记录但**故意推迟**的债：其 spec 第 26 行写明"baseline endSession 用 `crossingTimestampMs` 排序 zipWithNext 派生 lapCount，本 round（getLapTelemetry）用 `crossingWallClockTimestampMs` 排序 zipWithNext，两套语义在数据完整时收敛但混合 wallClock null 数据时可能不同"。
- 同时 `persisted-session-summary`（live spec）第 184 行也写明"统一两端语义作为 follow-up `unify-lap-count-semantics` 单独立项"——**本 round 即该 follow-up**（命名细化为 `unify-lap-count-pairing-semantics`，因核心是 pairing identity 而非单纯 count）。

**分歧二（次要 · 仅显示计数 · 非配对身份）：D（Snackbar）与 A/B（持久化/列表）计数口径不同。**
- D 用 qualityFlags 过滤（排除 IncompleteSectors / ProtocolDesyncGap / SuspectedJitter 作废圈），A/B 不读 qualityFlags（crossing 表无该字段，仅按 accepted）。
- 后果：session 结束时 Snackbar 显示 `"2 laps"`，detail 屏 Overview 与 Records 列表却显示 `lapCount = 3`。这是**计数数字不一致**，但**不会让点击打开错圈**（D 不参与"点哪一圈"的导航路径）。

### 用户场景

1. 用户横屏跑完一个 session（含 3 个有效圈），HOLD TO END 结束。Snackbar 弹 `"3 laps"`。
2. 进 session 详情屏，看到圈列表 `Lap 1 / Lap 2 / Lap 3`。
3. （Phase 1 后续 `lap-detail-screen-with-cursor` round 落地后）用户点击 `Lap 3` 行，期望打开第 3 圈的速度/加速度/轨迹曲线 + 游标回放。
4. **若 B/C 排序键分歧触发**：detail 屏调用 `getLapTelemetry(sessionId, 2)`（lapIndex = lapNumber - 1），但 C 内部按 wallClock 排序的第 2 对配对，与用户在列表里按 GPS 时钟排序看到的第 3 行**不是同一圈** → 打开错圈，用户看到的曲线对不上他点的圈。这是 production correctness bug，且只在跨时钟域排序分歧的真实数据上才暴露，单测/常规 path 可能掩盖。

本 round 的目标：**让 B（用户点击的圈列表圈编号）与 C（点击打开的 telemetry 切片）严格同源**——即两者基于**同一套圈配对 key 与排序**，使 `Lap N` 行对应的 `lapIndex = N - 1` 在 `getLapTelemetry` 中取到的恰好是同一物理圈。A（持久化 lapCount）的配对口径同步对齐到同一 key，保证 Records 列表 `lapCount` 数字与圈列表行数一致。D（Snackbar 计数）的语义差异显式收口为"仅显示计数、不参与圈导航身份"的 normative 约束，避免 half-closure。

## What Changes

1. **确立单一权威圈配对 key**：选 `crossingWallClockTimestampMs` 作为 A/B/C 三站点的**唯一圈配对排序键与窗口键**（rationale + 拒绝其他方案见 design.md Decision 1/2）。C 已用此 key（不变）；A（endSession）与 B（deriveDetailMetrics）从 `crossingTimestampMs` 改为按 `crossingWallClockTimestampMs` 排序配对，并复用 C 已确立的"null wallClock 排到末尾（`?: Long.MAX_VALUE`）+ 前缀连续 null 段 / 后缀连续非空段"假设。
2. **修改 `TelemetryRepository.endSession`**（站点 A）：`acceptedSF` 排序键 + duration 减法从 `crossingTimestampMs` 改为 `crossingWallClockTimestampMs`；当 crossing 的 wallClock 为 null 时按 C 同款规则处理（排末尾、不参与有效配对）。lapCount = wallClock 域下 zipWithNext 配对数。
3. **修改 `LapSessionDetailScreen.deriveDetailMetrics`**（站点 B）：accepted SF 排序键 + duration 减法从 `crossingTimestampMs` 改为 `crossingWallClockTimestampMs`，使圈列表 `lapNumber = idx + 1`（1-based）严格对应 `getLapTelemetry(sessionId, idx)`（0-based lapIndex）。
4. **新增显式 normative 不变式**：定义"圈配对身份"（lap pairing identity）= A/B/C 共用的同源契约，并把 D（Snackbar qualityFlags 计数）显式 normative 为"仅显示计数、不得作为圈导航 lapIndex 来源"，关闭 half-closure。
5. **测试**：在 `TelemetryRepositoryEndSessionPersistTest`（既有套件）补 case 锁死 endSession 用 wallClock 配对；新增/扩展 detail 屏派生 + getLapTelemetry 跨站点同源的单测（反例 scenario 锁死"若 B 用 GPS 时钟 / C 用 wallClock 在分歧数据上指向不同圈则 fail"）。

## Impact

- **Affected specs**: `persisted-session-summary`（MODIFIED：endSession lapCount 派生 key + 新增 lap pairing identity 跨站点同源不变式 + Snackbar 计数归口 normative）。
- **Affected code**:
  - `core/data/src/main/java/com/blazepush/core/data/repository/TelemetryRepository.kt`（`endSession` L154-169 排序/减法 key 改 wallClock；**不埋 FileLogger**——模块边界，见 design FileLogger 计划）
  - `feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/LapSessionDetailScreen.kt`（`deriveDetailMetrics` L471-495 排序/减法 key 改 wallClock + private→internal 可见性放宽 + LaunchedEffect 派生处埋 FileLogger）
  - `feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt`（`finishActiveLapSession` L561-586 仅**新增** FileLogger 埋点记录站点 A/D 计数；**不改** qualityFlags 计数计算逻辑）
- **Affected tests**:
  - `core/data/src/test/java/com/blazepush/core/data/repository/TelemetryRepositoryEndSessionPersistTest.kt`（补 wallClock 配对 case + 反例）
  - detail 屏派生函数测试（若 `deriveDetailMetrics` 为 private 则在屏所在 module 加可见性允许的测试或抽出纯函数测试，见 tasks）
- **不改动**：
  - `getLapTelemetry`（站点 C）——它已是权威 key，本 round 不动其实现（只补跨站点同源测试）。
  - RaceChrono BLE 公共协议 / binary writer / `bridgeGpsToLapTiming` 的 `gpsData.timestamp` 处理（A56 + 公共协议边界，本 round 零触碰）。
  - Room schema（无字段加减、无 @Database version bump）。
  - `finishActiveLapSession` 的 qualityFlags 计数逻辑（仅加 normative 约束声明其用途边界，不改其计算）。
- **复杂度**: small（< 200 行 + 2 module + 无 schema 改），走加速通道（road-test-first 模式下：CC 单遍自审 + FileLogger 埋点 + 真机攒批；不触发 5 个强制升级 medium 例外场景——非公共协议改 / 非跨 capability ripple / 非 schema migration / 非新 module / 非派生 follow-up）。
