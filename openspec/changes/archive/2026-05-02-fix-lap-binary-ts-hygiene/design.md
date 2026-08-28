## Context

`add-lap-session-phase1` round（已合回 `feature/track-tech-v2`）落地了 LapSessionDetailScreen，真机走完一段 replay → HOLD TO END → View Record → 进入 detail 屏后发现 **TOP SPEED 显示 `--`**。

溯源链路（见 `docs/design/laptime-ts-hygiene-deferred.md` §2 + 本 round Codex review §1）：

```
TestSessionViewModel.selectLapDebugMode (line 267)
  ↓
activeLapStartSystemTs = System.currentTimeMillis()   // T0：UI 进入 lap 模式
  ↓ 等待首帧 GPS 到段3 (可能 0-30 秒)
TelemetryRepository.startSession() (line 40)
  ↓
val startTs = System.currentTimeMillis()              // T1：repository 懒启动
writer.open(..., startTs = T1)                        // header.startTs = T1
  ↓
TestSessionViewModel.bridgeGpsToLapTiming (line 562)  // T2..Tn：每帧
  ↓
tsDeltaMs = gpsData.timestamp - lapAnchorTs           // 协议 ts - T0
            ↑协议 epoch ms       ↑T0 (≠ T1)
  ↓
LapTelemetryReader 重建 absoluteTs = header.startTs (T1) + sample.tsDeltaMs (协议ts - T0)
                                   = T1 + 协议ts - T0
                                   ≠ 真壁钟接收时刻
  ↓
.filter { absoluteTs in lapStartTs..lapEndTs }   // 100% reject
  ↓
readLapSamples 永远空 → maxOf{speedKmh} → null → UI "--"
```

**两个独立 bug**：

1. **跨时钟域**（协议 ts vs 真壁钟）—— deferred memo §2 已诊断
2. **anchor 错位**（lapAnchorTs = T0 ≠ header.startTs = T1）—— Codex review §1 新揭示

只修 (1) 不修 (2)：absoluteTs 整体向"未来"偏移 `T1 - T0`，等待越久偏移越大，尾部样本仍可能被 session 窗口过滤掉。两者必须一起修。

A56 (`unify-gps-telemetry-persistence`) 引入的 capability `binary-telemetry-storage` 已规定"圈速点阵时间窗口查询"requirement（"返回的样本集合中所有 absoluteTs（sessionStartTs + ts_delta_ms）均在 [T_start, T_end] 范围内"），但代码实现没有满足该 requirement——这是一个 capability 已声明但实现违约的 baseline bug。

当前主 round 已用 quick fix 让 detail 屏的 TOP SPEED 走 `readPerformanceSamples`（顺序读不过滤），代价是失去窗口过滤能力。本 round 在数据写入路径根因消除该污染，**目标是让 session start/end 真壁钟窗口的 readLapSamples 正常工作**。per-lap / sector segment 受 crossing event 时钟域问题阻塞（见决策 5），延期独立立项。

## Goals / Non-Goals

**Goals**：

- 让 lap session binary 写入的 `tsDeltaMs` anchor 严格等于 `header.startTs`（同时刻、同时钟域）
- 让 `header.startTs / entity.startTs / entity.endTs` 维持接收侧真壁钟单一时钟域
- 加 round trip 单元测试：写入 N 帧 → readLapSamples 按 session 窗口过滤 → 验证 absoluteTs 命中
- grep 自检：`bridgeGpsToLapTiming` 不再出现 `gpsData.timestamp - lapAnchorTs` 这种跨时钟域减法
- 检查 simulator replay 路径下 tsDeltaMs 计算是否同样需要修正
- **将 commit 的单元测试作为合流门槛**（不依赖真机 detail 屏 TOP SPEED 验证，因 quick fix 路径绕开了本修复）

**Non-Goals**：

- 不解锁 per-lap / sector segment 的 readLapSamples 窗口（受 crossing event 时钟域阻塞，延期立项 `fix-lap-crossing-clock-hygiene`，见决策 5）
- 不改 `BinaryTelemetryWriter` / `BinaryTelemetryReader` 的接口、字节布局、header schema
- 不改 `TelemetrySessionEntity` / `CrossingEventEntity` 的字段或 Room migration
- 不改 RaceChrono BLE 公共协议任何字段
- 不改 `crossing.timestampMillis` 的语义或时钟域（属 crossing 时钟域 follow-up scope）
- 不做旧 binary 文件的数据迁移（旧文件按 `readPerformanceSamples` 全量读路径继续可用）
- 不做 `LapSessionDetailScreen` 的 quick fix 回切（`readPerformanceSamples` → `readLapSamples` 留给后续 cleanup round）
- 不接管 `wire-laptime-to-gps-filter` 的 jitter 消除工作（不重叠，见 deferred memo §9）

## Decisions

### 决策 1：anchor 必须等于 `header.startTs`（不是 `lapAnchorTs`）

**Codex review §1 修订**：原方案 A 把 `tsDeltaMs = gpsData.timestamp - lapAnchorTs` 改为 `tsDeltaMs = System.currentTimeMillis() - lapAnchorTs` 是错的——`lapAnchorTs`（`activeLapStartSystemTs`）是 UI 进入 lap 模式时 (T0) 取的真壁钟，跟 `header.startTs` (T1，repository 懒启动时取) 不在同一时刻，差值是"等首帧 GPS 到段3"的等待时间。

**新方案 A***：让 sample 的 anchor 严格等于 `header.startTs`：

```kotlin
// TestSessionViewModel.bridgeGpsToLapTiming (line 562 附近，原 if (lapAnchorTs != null) 块内部改写)
val lapAnchorTs = activeLapStartSystemTs
if (lapAnchorTs != null) {
    if (activeLapSessionId == null) {
        activeLapSessionId = telemetryRepository.startSession(TelemetrySessionType.LAP_SESSION)
    }
    // 关键：anchor 必须等于 header.startTs，从 repository 拉取（与 startSession 内部生成的 startTs 同源）
    val sessionStartTs = telemetryRepository.activeSessionStartTs
    if (sessionStartTs != null) {
        telemetryRepository.writeSample(
            TelemetrySample(
                tsDeltaMs = System.currentTimeMillis() - sessionStartTs,  // 真壁钟差，与 header.startTs 同源
                lat = gpsData.latitude,
                lon = gpsData.longitude,
                speedKmh = gpsData.speed,
                bearingDeg = gpsData.bearing,
            )
        )
    } else {
        // 防御：startSession 刚返回 sessionId 但 activeSessionStartTs 仍为 null 是 invariant 破坏，
        // 但本块不允许 throw / bare return，否则下面 lapTimingEngine.processSample 不会执行 → 圈速状态机停摆
        FileLogger.w(TAG, "bridge: missing activeSessionStartTs after startSession, skip telemetry write but engine continues")
    }
}

// 重要：上面 if 块只控制 telemetry 写入；不论 sessionStartTs 是否为 null，下面 lapTimingEngine.processSample 必须照常执行
val updatedSession = lapTimingEngine.processSample(...)
```

**实现路径**：`TelemetryRepository` 暴露只读 property `activeSessionStartTs: Long?`（在 `startSession` 时赋值，在 `endSession` 时清空），bridge 层从 repository 拉取该值作为 anchor。

**critical 约束**（Codex review §2 修订）：bridge 层不得用 bare `?: return` 处理 `activeSessionStartTs == null` 的 invariant 破坏分支——`bridgeGpsToLapTiming` 后半段还有 `lapTimingEngine.processSample` + `_lapSession.value` 更新 + 过线事件写 Room 等关键状态机操作，提前 return 会让圈速状态机停摆一帧（甚至连续掉帧）。修法是把"缺 anchor"降级到"跳过 telemetry 写入但引擎继续"+ 加 warning 日志，而不是阻塞整个 bridge。

**对比**（升级版）：

| 方案 | 改动量 | 与 header.startTs 同源 | 选择理由 |
|---|---|---|---|
| ~~A：用 lapAnchorTs 做 anchor~~ | 1 行 | ❌（差等首帧时间） | 错误，原 deferred memo 漏诊 |
| **A\*：repository 暴露 activeSessionStartTs** | repository 加 1 property + bridge 用 1 行 | ✅ | 最小改动 + anchor 同源 |
| B：startSession 返回 SessionMeta(id, startTs) | repository 改签名 + bridge 改 1 行 + 所有 caller 跟 | ✅ | 改动面更大，与并行 round C 冲突 |
| C：bridge 把"墙钟时刻+lat+lon+..." 传给 repository，repo 内部算 tsDeltaMs | API 改 + 所有 caller 跟 | ✅ | API 改动面最大 |
| D：reader 不重建 absoluteTs，用相对窗口 | 3 行 + 所有 caller 跟 | N/A | 改 reader 语义，繁琐 |
| E：entity / header 改用协议时间 | 3 处 | 用协议时间 | 偏离架构常规，未同步状态需 sentinel 兼容 |

**Rationale**：A* 是 review 后唯一同时满足"修对 anchor + 不动 reader/writer 接口 + 与并行 round C 冲突最小"的方案。

**与 C 的冲突评估**：C 在改 `core/data` 的 entity / migration / DAO，不动 `TelemetryRepository.kt` 的 method 签名。本 round 在 `TelemetryRepository.kt` 加只读 property + 在 startSession/endSession 内赋值/清空（3-4 行），rebase 应可合。如真冲突，本 round 让位（文件分隔已见 `parallel-change-collab.md` §6）。

### 决策 2：测试套件归位 `core/data`

**选择**：新增测试文件路径 `core/data/src/test/java/com/blazepush/core/data/telemetry/BinaryLapTelemetryRoundTripTest.kt`（实施时确认实际包路径）。

**Rationale**：

- 测试目标是 binary writer-reader round trip + 时钟域 + anchor 一致性，与 binary 持久化代码同模块
- 新增 test 不算改动 main 代码，与 C 的 entity / migration / DAO 改动隔离
- proposal Impact 边界澄清：MUST NOT 改 `core/data/src/main`，**允许新增** `core/data/src/test`

### 决策 3：覆盖 simulator replay 路径自检

**选择**：实施期 grep `SimulatorViewModel.startReplayDataUpdate` 与 simulator 端任何写入 lap session binary 的路径，确认是否复用 `bridgeGpsToLapTiming`。

- 若复用 → 修复一并生效
- 若另写 → 同样原则修复（用 repository 暴露的 sessionStartTs 做 anchor）

### 决策 4：spec delta 用 ADDED Requirements

**选择**：本 change spec delta 用 `## ADDED Requirements`，新增"采样时间字段时钟域 hygiene + anchor 同源"requirement，不动 A56 已有 requirement。

**Rationale**：A56 已有 requirement 是结果断言（"absoluteTs 必须在 [T_start, T_end] 范围内"），本 round 加的是过程约束（"tsDeltaMs anchor 必须等于 header.startTs，且属真壁钟时钟域"），两条不冲突可平行存在。

### 决策 5：scope 收紧——不解锁 per-lap / sector segment（Codex review §2 修订）

**Codex review §2 修订**：原 proposal 声称本 round 解锁"Analysis Mode 单圈轨迹、sector 分段、Records LAPS sub-tab 圈分段读取"，是空头支票——`TelemetryCrossingEvent.crossingTimestampMs` 来自 `LapTimingEngine.processSample` 输出的 `crossing.timestampMillis`，仍是 GPS 协议时间（见 `TestSessionViewModel.kt:627`）。即便本 round 修好 binary samples 的 absoluteTs 为真壁钟，未来用 `readLapSamples(file, crossings[i].timestampMs, crossings[i+1].timestampMs)` 仍是协议时间窗口过滤真壁钟样本，结果仍空。

**收紧后的本 round 解锁范围**：仅"用 session start/end 真壁钟（即 `entity.startTs / entity.endTs`）作为窗口的 `readLapSamples` 调用"。这能解锁的 UI：

- `LapSessionDetailScreen` 在未来若回切到 `readLapSamples(filePath, session.startTs, session.endTs)`（全 session 窗口），可以正确返回所有样本
- 任何"全 session 全样本"派生（TOP SPEED 全段、最高 g、最快 sector 起点等）

**不能解锁的（由 follow-up round `fix-lap-crossing-clock-hygiene` 处理）**：

- per-lap segment：用两个 crossing.timestampMillis 截取单圈轨迹
- sector segment：用 sector gate crossing 截取 sector
- Records LAPS sub-tab 的"按圈过滤的样本流"

**follow-up 立项**：见 `docs/design/lap-crossing-clock-hygiene-deferred.md`（与本 round 同步沉淀），9 章完整设计 memo，下次直接 `/opsx:ff fix-lap-crossing-clock-hygiene`。

## Risks / Trade-offs

- **Risk**：A56 (`unify-gps-telemetry-persistence`) 还在等真机 manual gates 没归档，本 round spec delta 引用 specs/ 下还不存在的 capability 名 → openspec validate 可能告警 → **Mitigation**：保持 capability 名 `binary-telemetry-storage` 与 A56 完全一致；归档顺序 user 拍板，A56 先归档则本 round delta 自动找到 base spec
- **Risk**：本 round 修复后，旧 binary 文件（修复前写入）的 lap session 仍然按窗口过滤返回空 → **Mitigation**：detail 屏当前走 `readPerformanceSamples` 顺序读路径，旧文件仍可用；本 round scope 不含旧文件迁移
- **Risk**：bridge 层改用真壁钟差后，binary 文件的 ts 字段从"GPS 协议时间偏移"变成"接收侧真壁钟偏移"，未来若引入"双端时钟同步分析"需在 binary 之外另存协议 ts → **Mitigation**：当前没有该需求；若未来需要，新加 capability `dual-clock-telemetry-correlation`，binary 字段不动
- **Risk**：simulator 自测路径若另有写入 lap session binary 的入口未被本 round 覆盖 → **Mitigation**：决策 3 + tasks §1 grep 全覆盖
- **Risk**：本 round 仅解锁 session 窗口，per-lap segment 仍不可用，未来 UI（Analysis Mode 单圈轨迹）需等 crossing 时钟域 follow-up → **Mitigation**：决策 5 明确收紧 + 沉淀 deferred memo + tasks §8 backlog 引用
- **Risk**：与并行 round C 在 `TelemetryRepository.kt` 文件冲突（C 改 DAO 注入 / repository method 实现，本 round 加 property） → **Mitigation**：本 round 改动定位在文件顶部 field 区 + startSession/endSession 内 1-2 行，与 C 的方法体改动通常 rebase-friendly；冲突时本 round 让位
- **Risk**：真机验证不能证伪本 change 生效（detail 屏 quick fix 路径已绕开本修复） → **Mitigation**：决策 6（合流门槛改单元测试），见 tasks §6 调整

### 决策 6：合流门槛改为 commit 的单元测试（Codex review §3 修订）

**Codex review §3 修订**：原 tasks §6.5/§6.6 用真机 detail 屏 TOP SPEED 验证本修复——但 detail 屏当前走 `readPerformanceSamples`（顺序读不过滤），TOP SPEED 即使不修 tsDeltaMs 也通过，是假绿。§6.6 临时本地切回 `readLapSamples` 也不留提交证据。

**修订后的合流门槛**：

1. **强门槛（必须通过才能合流）**：commit 的单元测试套件全绿（§3 中 4 个 case + 决策 1 新增 anchor 同源 case）
2. **弱门槛（不回归验证）**：真机 install apk → LapSession 跑完 → detail 屏能正常打开 + 显示历史一致字段（不强求 TOP SPEED 真机验证，因走 quick fix 路径）
3. **取消**：原 §6.6 临时切回 `readLapSamples` 的本地试验（不留可审查证据，不算门槛）

**Rationale**：本修复的功能正确性由 round trip 单测断言（commit 可审查），真机仅做"不回归"健康检查；detail 屏 TOP SPEED 真机验证留给后续 cleanup round（回切 `readLapSamples` 时一并做）。

## Migration Plan

- **代码**：repository 加 1 property + bridge 用 sessionStartTs 替代 lapAnchorTs（核心 ~5 行）+ 单元测试。无 schema 迁移、无配置迁移、无 feature flag
- **回滚**：单 commit，revert 即恢复
- **数据**：旧 binary 文件不迁移；通过 `readPerformanceSamples` 路径继续可用
- **真机**：修复 → install apk → LapSession 跑完 → detail 屏正常打开 + 显示历史一致字段（不回归验证）。串行规则：准备真机时先在对话窗口告知用户并等待授权（与并行 round C 真机验证错峰）
- **合流**：单元测试全绿是合流强门槛，真机不回归是弱门槛

## Open Questions

- 测试文件最终模块路径以 A56 实际 binary 持久化代码归位为准（`core/data` vs 其他），实施时第一步即确认
- simulator replay 路径下是否复用 `bridgeGpsToLapTiming`，实施时 grep 确认（若另写则按相同原则修复，使用 repository 暴露的 sessionStartTs）
- `TelemetryRepository.activeSessionStartTs` 的并发模型：startSession / writeSample / endSession 是否在同一 coroutine context 调用？需确认是否需 `@Volatile` 或 atomic 包装（实施时按现有 activeWriter / activeSessionId 同样模式处理，复用即可）
- 是否将 `LapSessionDetailScreen` quick fix 回切到 `readLapSamples`：**当前决定不在本 round 做**，原因是保持 round scope 紧；下次 cleanup round 回切并配合真机验证 TOP SPEED 端到端

### §1 grep 盘点结论（2026-05-01 apply 阶段实测）

- **§1.2 simulator 路径**：`SimulatorViewModel.kt` 内无 `bridgeGpsToLapTiming` / `tsDeltaMs` / `writeSample` / `startReplayDataUpdate` 任何命中。simulator 端**无另写 lap session binary 的入口**。本 round 决策 3 自检通过，无需修 simulator 端
- **§1.1 PERFORMANCE_TEST 路径同 bug 模式**：grep 还发现 `TestSessionViewModel.kt:415`（`bridgeGpsToTelemetry` Running 期间持续写）+ `TestSessionViewModel.kt:500`（`startTest` preTrigger buffer 回填）两处同样的"协议时间 - anchor"减法（anchor 也是协议时间），与 header.startTs 跨时钟域。**出本 round scope**（PERFORMANCE_TEST 是另一个 session type），已沉淀 deferred memo `docs/design/perftest-binary-ts-hygiene-deferred.md`，立项名 `fix-perftest-binary-ts-hygiene`，依赖本 round 合回（复用 `repository.activeSessionStartTs` property）。详见 tasks.md §8.4
- **行号漂移**：v3 工件预期 `bridgeGpsToLapTiming` 在 line 562，rebase 后实际在 line 598（C 加 trackId/trackNameSnapshot 后偏移 36 行）。决策 1 伪代码与决策路径不变，仅行号引用以实际为准
