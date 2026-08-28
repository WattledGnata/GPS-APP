# Proposal: future-sector-derivation

## Why

### 问题溯源

`TelemetryRepository.getLapTelemetry(sessionId, lapIndex)`（`core/data/.../repository/TelemetryRepository.kt:284-321`）是单圈详情屏（Phase 1 `lap-detail-screen-with-cursor` round，M2 里程碑）消费的圈速 telemetry 切片 reader。它返回的 `LapTelemetry`（`core/domain/.../model/LapTelemetry.kt:32-42`）含 `sectorBoundaries: List<Long>` 字段，喂给 `SectorBar` 组件（`feature/test/.../ui/components/SectorBar.kt`）画"圈内分段进度条"——SectorBar 把相邻 boundary wallClock 对画成一个 sector 矩形，游标落在哪段就高亮该段。

**当前 baseline（已 grep 核实，行号锚点见 What Changes）**：`getLapTelemetry` 第 317 行写死

```kotlin
sectorBoundaries = listOf(lapStartWallClock),
```

即永远只放圈起点单元素。SectorBar 拿到单元素列表后，`computeSectorBounds`（`SectorBar.kt:18-35`）会自动补 `lapEndWallClock` 凑成 `[lapStart, lapEnd]` 两端 → 永远只画 **1 段全圈条**，sector 分段功能 0% 可用。路线图 §1.2 已把这列为"三个组屏前必拍硬数据"之一，§3 Phase 1 收尾表把本 round（`future-sector-derivation`）**排序前置到或并入 detail 屏之前**，否则 detail 屏先做 1 段废 SectorBar，sector 派生回头返工 SectorBar 接线。

### 数据源调查结论：sector 过线确有记录（YES，已逐链路 grep + read 坐实）

本 round 立项第一步是诚实调查"sector 过线到底有没有被检测并持久化"。结论是 **YES，全链路已就绪**，无需任何上游补数据：

| 链路环节 | 证据（文件:行） | 结论 |
|---|---|---|
| **赛道定义 sector 门** | `feature/test/.../repository/PresetTracks.kt:58-83` | TFIC 预置赛道定义 2 个 `TimingGateType.Sector` 门（s1 seq=1 / s2 seq=2），`sectorGates: List<TimingGate>` 非空 |
| **gateType 取值域** | `feature/test/.../model/track/TimingGateType.kt:3-6` | enum `{ StartFinish, Sector }`，Sector 是合法值 |
| **engine 检测 sector 过线** | `feature/test/.../usecase/LapTimingEngine.kt:251-339`（`handleSectorCrossing`） | 遍历 `track.orderedSectorGates` detect，期待门 accepted → 记 `CrossingEvent(gateType=Sector, accepted=true)`；非期待门 accepted → 记 `CrossingEvent(gateType=Sector, accepted=false, reason=UnexpectedGateOrder)` |
| **crossing 持久化（含 sector）** | `feature/test/.../viewmodel/TestSessionViewModel.kt:912-943` | **无差别持久化所有 newCrossings**（不 filter gateType），每条带 `crossingWallClockTimestampMs = System.currentTimeMillis()`（与 binary samples `absoluteTsMs` 同时钟域）写 Room `crossing_events` |
| **Room 字段** | `core/data/.../local/entity/CrossingEventEntity.kt:33,41` | `gateType: String` + `crossingWallClockTimestampMs: Long?` 字段齐备，schema 已落地（v4→v5 已有 wallClock 列） |
| **DAO 已能查全部 crossing** | `core/data/.../local/dao/CrossingEventDao.kt:31-32`（`queryBySessionId`） | 返回 session 全部 crossing（含 sector），**无需新增 @Query** |
| **reader 已查全部 crossing** | `core/data/.../repository/TelemetryRepository.kt:286`（`getLapTelemetry` 内 `crossingDao.queryBySessionId`） | reader 已把全部 crossing 读进内存，只是第 288 行只 filter StartFinish 派生窗口，第 317 行没用 sector crossing 派生 sectorBoundaries |

**因此本 round 是纯 reader 派生改造**：sector 数据已经躺在 Room 里，只是 `getLapTelemetry` 没把它们捞出来填进 `sectorBoundaries`。这区别于"数据源缺失需上游立项"的悲观假设——经核实假设不成立，sector 过线真实可用。

### 数据源边界（诚实声明缺口，非阻塞本 round）

1. **flags 字段无关**：本 round 不触碰 `LapTelemetrySample.flags`（W1 binary writer 端永久默认 0 的 deferred 缺口与 sector 派生正交）。
2. **历史 session（§8.3 migration 之前）的 sector crossing `crossingWallClockTimestampMs = null`**：与 StartFinish 同款 null 处理——null wallClock 的 sector crossing 无法定位到圈窗口内的时间位置，MUST 排除，回退到只含 lapStart 的单段（不回归现有单段行为，旧数据本就只能看单段）。
3. **debug/宽容闭合 session 可能 sector 不全**：`fix-lap-debug-mode-sector-chain-test-after-min-count-1` round（user 2026-05-29 拍板"起终点过线两次即一圈、sector 不完整仍闭圈"）下，一圈可能只过了部分 sector 门甚至 0 个 → 该圈 sector crossing 少于 `track.sectorGates.size`。这不是 bug：少几个 sector 就少画几段，0 个就回退单段，SectorBar 仍可正常显示，不伪造缺失的 sector。

### 用户场景

1. 用户在 TFIC 赛道横屏跑完一个 session（含若干完整圈，每圈正常过 start-finish + s1 + s2）。
2. 进 session 详情屏，点击 `Lap 2` 行（Phase 1 `lap-detail-screen-with-cursor` round 落地后）。
3. detail 屏调 `getLapTelemetry(sessionId, 1)`，SectorBar 拿到 `sectorBoundaries = [lapStart, s1过线wallClock, s2过线wallClock]` → 画 **3 段**（Sector 1 / Sector 2 / Sector 3）。
4. 用户拖动游标，落在哪段 SectorBar 高亮哪段，配合速度/加速度曲线看"这一段我开得怎么样"。
5. **对比当前 baseline**：第 3 步只画 1 段全圈条，用户看不到分段，sector 分析能力缺失。

### 本 round 目标

让 `getLapTelemetry` 从 lap 窗口 `[lapStartWallClock, lapEndWallClock]` 内的 **accepted Sector gate 过线**（来自 session 已持久化的 crossing）派生多元素 `sectorBoundaries`，喂 SectorBar 画多段；无 sector 门的赛道 / 无 sector crossing 的 session / sector wallClock 为 null 时 robust 回退到 `listOf(lapStartWallClock)`（与现有单段行为收敛，不回归）。

## What Changes

1. **`getLapTelemetry` 派生 sectorBoundaries**（`core/data/.../repository/TelemetryRepository.kt:317`，当前 `sectorBoundaries = listOf(lapStartWallClock)`）：从已读进内存的 `crossings` 列表中 filter `gateType="Sector"`（ignoreCase）`&& accepted == true && crossingWallClockTimestampMs != null`，再 filter wallClock 落在 `[lapStartWallClock, lapEndWallClock)` 半开窗口内（窗口外的 sector 过线属其他圈，MUST 排除），按 `crossingWallClockTimestampMs` 升序，得 `sectorWallClocks`。`sectorBoundaries = listOf(lapStartWallClock) + sectorWallClocks`。当 `sectorWallClocks` 为空 → `sectorBoundaries = listOf(lapStartWallClock)`（回退单段，不回归）。
2. **不引入新 DAO 方法**：`crossingDao.queryBySessionId(sessionId)` 已在 `getLapTelemetry` 第 286 行调用并把全部 crossing 读进 `crossings` 变量；sector 派生复用同一份内存数据，**MUST NOT** 新增 @Query（避免多查一次 DB + schema 边界）。
3. **窗口契约与现有 reader 收敛**：sector wallClock 用 **半开区间** `>= lapStartWallClock && < lapEndWallClock`——`>= lapStart` 排除恰在圈起点的退化项（圈起点已是 sectorBoundaries 首元素），`< lapEnd` 排除圈终点及下一圈的 sector（圈终点由 SectorBar `computeSectorBounds` 自动补 lapEnd，不重复）。
4. **MANDATORY FileLogger 埋点**（road-test-first 模式安全网）：纯数据派生 reader 路径用 `FileLogger.v()`（25Hz 路径外、每次 getLapTelemetry 调一次，量级低但用 v 级避免污染）记录"派生了几个 sector boundary / 是否回退单段 / 窗口外排除了几条 sector crossing"。模块边界 caveat：`core/data` 模块**无 FileLogger 依赖**（FileLogger 在 `feature/test`，见 design FileLogger 计划），因此 reader 内**不直接埋 FileLogger**，改在 design FileLogger 计划段说明诊断策略（reader 返回值 self-describing + detail 屏消费侧埋点 deferred 到 detail 屏 round）。
5. **测试**：在既有 `core/data/.../repository/LapTelemetryReadersTest.kt` 套件补 case（多段派生 / 无 sector 回退单段 / 窗口外 sector 排除 / sector wallClock null 排除 / 不全 sector 部分派生 + 反例锁死"窗口外 sector MUST NOT 混入"）。

## Impact

- **Affected specs**: 新增 capability `lap-telemetry-sector-derivation`（ADDED：`getLapTelemetry` 的 `sectorBoundaries` 派生契约 + 回退语义 + 窗口排除不变式）。
- **Affected code**:
  - `core/data/src/main/java/com/blazepush/core/data/repository/TelemetryRepository.kt`（`getLapTelemetry` 第 317 行 `sectorBoundaries = listOf(lapStartWallClock)` 改为从 sector crossing 派生；复用第 286 行已读的 `crossings`）
- **Affected tests**:
  - `core/data/src/test/java/com/blazepush/core/data/repository/LapTelemetryReadersTest.kt`（补 sector 派生 / 回退 / 窗口排除 / null 排除 / 反例 case；既有 case A 第 84 行 `assertEquals(r.lapStartWallClock, r.sectorBoundaries.first())` 契约保持——派生后首元素仍 == lapStart）
- **#16 跨 round 共享字段 drift（本 round 核心，user 要求 review 的原因）**：
  - `LapTelemetry.sectorBoundaries` 是公共 domain 数据契约字段（`core/domain`），W2（chart components / SectorBar）+ W3（LapAlignment）已合回。本 round 把它从"恒单段"变"可多段"。消费方逐个分析见 design Decision 4「#16 drift 段」——**结论：W2 SectorBar 本就是为多段设计（单段是 baseline 退化），W3 LapAlignment 根本不读 sectorBoundaries（grep 0 命中），无破坏**。
- **不改动**：
  - RaceChrono BLE 公共协议 / binary writer / `bridgeGpsToLapTiming` / engine（A56 + 公共协议边界，本 round 零触碰；sector crossing 早已由 engine 写好）。
  - Room schema（无字段加减、无 @Database version bump；`gateType` + `crossingWallClockTimestampMs` 列已存在）。
  - CrossingEventDao（不新增 @Query）。
  - `getLapTelemetry` 的 StartFinish 窗口派生逻辑（第 287-292 行，不动）。
  - StartFinish crossing 的 lapCount 配对语义（`unify-lap-count-pairing-semantics` round 的成果，本 round 零触碰）。
- **复杂度**: small（< 200 行 + 1 module + 无 schema 改）。但因 **#16 共享契约填充语义扩展**（sectorBoundaries 从恒单段→可多段，命中"跨 round 共享字段 drift"），按 CLAUDE.md `Round 复杂度分级` 的"强制升级 medium 流程例外场景 (5) 派生 follow-up / 共享契约填充语义扩展"——**本 round 经 user 显式要求走一轮对抗 review**（road-test-first 模式默认去 Codex/Opus，但本 round 因契约改例外保留一轮 L1 adversarial review），其余按 road-test-first（FileLogger 兜底 + 真机攒批）。
