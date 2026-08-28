## Context

`fix-lap-binary-ts-hygiene` round（A，已归档 `archive/2026-05-02-fix-lap-binary-ts-hygiene`）在 §1 grep 盘点（2026-05-01）时发现：除了 LAP_SESSION 路径的 `bridgeGpsToLapTiming` 之外，PERFORMANCE_TEST 路径在 `TestSessionViewModel.kt` 的两处 binary sample 写入入口存在完全相同的 anchor / 时钟域 bug 模式（详细 memo 见 `docs/design/perftest-binary-ts-hygiene-deferred.md`）：

```
TestSessionViewModel.startTest (当前主区 line 720-734)
  ↓
val anchorTs = lockedPreTriggerBuffer.firstOrNull()?.timestamp  // 协议时间 (filteredData.timestamp)
              ?: filteredData.timestamp
activeTestStartTs = anchorTs                                    // 协议时间
  ↓
for (frame in lockedPreTriggerBuffer) {
    telemetryRepository.writeSample(
        TelemetrySample(tsDeltaMs = frame.timestamp - anchorTs, ...)  // 协议 - 协议（同时钟域内）
    )
}
  ↓
TelemetryRepository.startSession() 内部                         // 真壁钟 (T1)
val startTs = System.currentTimeMillis()
writer.open(..., startTs = T1)                                  // header.startTs = 真壁钟
  ↓
TestSessionViewModel.processFilteredData (当前主区 line 638-651) // PERFORMANCE_TEST Running 分支持续写
  ↓
tsDeltaMs = filteredData.timestamp - anchorTs                   // 协议 - 协议
  ↓
BinaryTelemetryReader 重建 absoluteTs = header.startTs (真壁钟) + sample.tsDeltaMs (协议偏移)
                                       = 真壁钟 + (协议 - 协议)
                                       ≠ 真壁钟接收时刻
  ↓
.filter { absoluteTs in [windowStart, windowEnd] }              // 用真壁钟做窗口 → 100% reject
```

**bug 等价分类**（与 LAP_SESSION 路径对称）：

| 维度 | LAP_SESSION（A round 已修） | PERFORMANCE_TEST（本 round） |
|---|---|---|
| anchor 字段 | `lapAnchorTs = activeLapStartSystemTs` (UI 进入 ts，**真壁钟** but 错位) | `anchorTs = activeTestStartTs` (preTrigger first frame ts，**协议时间**) |
| sample.timestamp 字段 | `gpsData.timestamp` (协议时间) | `filteredData.timestamp` / `frame.timestamp` (协议时间) |
| 减法形态 | 协议 - 真壁钟（跨时钟域） | 协议 - 协议（同时钟域内） |
| 与 header.startTs 关系 | 跨时钟域 + anchor 错位 | 跨时钟域（anchor 也不与 header 同源） |
| 反例 | session 窗口 readLapSamples 100% reject | **任何真壁钟窗口的 readPerformanceSamples** 100% reject（当前实现是顺序读不过滤，bug 未暴露） |

**当前未暴露**：`LapSessionDetailScreen` 走 `readPerformanceSamples` 顺序读路径（A round quick fix），PERFORMANCE_TEST 详情屏未触发窗口过滤；`PerformanceResultScreen` 的 SpeedCurve / GForceChart 也是消费 dataPoints in-memory，不走 binary reader 窗口路径。但 baseline 数据完整性已被破坏——任何按时间窗口截取加速度 segment 的未来功能（Phase 1 单圈 chart cursor 拖动 / "框选 0-100 区间回放" / "加速度峰值时刻 ±2 秒回放"）都将 100% 失效。

**前置依赖**：A round 已合回 `feature/track-tech-v2` 主区，`TelemetryRepository.activeSessionStartTs: Long?` 只读 property 已暴露并由 round trip 单测覆盖。本 round 直接复用，是对称扩展。

## Goals / Non-Goals

**Goals**：

- 让 PERFORMANCE_TEST binary 写入的 `tsDeltaMs` anchor 严格等于 `header.startTs`（同时刻、同时钟域）
- 覆盖 PERFORMANCE_TEST 的两个写入入口（startTest 内 preTrigger buffer 回填 + processFilteredData 持续写），与 LAP_SESSION 路径达成对称的 anchor 同源 hygiene
- 加 round trip 单元测试：startTest + writeSample → endSession → readPerformanceSamples 全窗口验证 absoluteTs 落在 `[entity.startTs, entity.endTs]` 范围内
- grep 自检：`processFilteredData` 与 `startTest` 内部不再出现 `filteredData.timestamp - X` / `frame.timestamp - X` 跨时钟域减法
- 显式承认 preTrigger buffer 帧的 tsDeltaMs 时间集中限制（方案 A trade-off），写进 spec Scenario 与 design Risk 段，作为可接受的语义
- 将 commit 的单元测试作为合流强门槛，真机仅做不回归弱门槛

**Non-Goals**：

- 不引入 `PreTriggerFrame.receivedAtWallClockMs` 字段（方案 B），见决策 2 拒绝理由
- 不改 `BinaryTelemetryWriter / BinaryTelemetryReader` 接口、字节布局、header schema
- 不改 `TelemetrySessionEntity` 字段或 Room migration
- 不改 RaceChrono BLE 公共协议任何字段
- 不改 `TestSessionViewModel.activeTestStartTs` 字段在 0-100 计时显示等下游派生中的语义（仅停止用作 binary tsDeltaMs anchor）
- 不复改 LAP_SESSION 路径已由 A round 修复的部分（`bridgeGpsToLapTiming` 不再动）
- 不解锁"加速度时间序列高保真回放"（buffer 帧时间集中限制使其不可用，未来需求出现时升级到方案 B 单独立项）
- 不做旧 PERFORMANCE_TEST binary 文件的数据迁移（旧文件按 `readPerformanceSamples` 顺序读路径继续可用）

## Decisions

### 决策 1：复用 `repository.activeSessionStartTs` 作为 anchor（方案 A）

**选择**：PERFORMANCE_TEST 的两个写入入口（startTest preTrigger 回填 + processFilteredData 持续写）都从 `telemetryRepository.activeSessionStartTs` 拉取 anchor，每帧 `tsDeltaMs = System.currentTimeMillis() - sessionStartTs`。

```kotlin
// TestSessionViewModel.startTest (当前主区 line 720-734)
activeTestSessionId = telemetryRepository.startSession(TelemetrySessionType.PERFORMANCE_TEST)
val sessionStartTs = telemetryRepository.activeSessionStartTs
if (sessionStartTs != null) {
    for (frame in lockedPreTriggerBuffer) {
        telemetryRepository.writeSample(
            TelemetrySample(
                tsDeltaMs = System.currentTimeMillis() - sessionStartTs,  // 真壁钟差，与 header.startTs 同源
                lat = frame.latitude,
                lon = frame.longitude,
                speedKmh = frame.speed,
                bearingDeg = frame.bearing,
            )
        )
    }
} else {
    // 防御：startSession 刚返回 sessionId 但 activeSessionStartTs 仍为 null 是 invariant 破坏
    // FileLogger API 仅有 d/v/e（无 w），invariant 破坏走 e 级与 A round line 843 同 pattern
    FileLogger.e(TAG, "startTest preTrigger backfill: missing activeSessionStartTs after startSession, skip telemetry write")
}
// 注：preTrigger 回填段位于 startTest 末尾，循环跳过后只剩 FileLogger.d 调试日志，
// 不影响 _testState = Running 已设置（line 717）+ 状态机后续 GPS 帧驱动 processFilteredData 进入 Running 分支

// activeTestStartTs 保留：仍用作 0-100 计时显示等其他派生（不变）
activeTestStartTs = lockedPreTriggerBuffer.firstOrNull()?.timestamp ?: filteredData.timestamp

// TestSessionViewModel.processFilteredData (当前主区 line 638-651, TestState.Running 分支)
// 原 line 639-651 为：
//     val anchorTs = activeTestStartTs
//     if (anchorTs != null) { writeSample(tsDeltaMs = filteredData.timestamp - anchorTs, ...) }
//     if (state.session.template.shouldEnd(filteredData.raw)) { finishTest(state.session) }
// 修改为：
val sessionStartTs = telemetryRepository.activeSessionStartTs
if (sessionStartTs != null) {
    telemetryRepository.writeSample(
        TelemetrySample(
            tsDeltaMs = System.currentTimeMillis() - sessionStartTs,
            lat = filteredData.latitude,
            lon = filteredData.longitude,
            speedKmh = filteredData.speed,
            bearingDeg = filteredData.bearing,
        )
    )
} else {
    // FileLogger API 仅有 d/v/e（无 w），与 A round line 843 同 pattern
    FileLogger.e(TAG, "processFilteredData: missing activeSessionStartTs, skip telemetry write but test pipeline continues")
}

// 重要：上面 if 块只控制 telemetry 写入；不论 sessionStartTs 是否为 null，Running 分支末尾的
// shouldEnd 检查 + finishTest 触发必须照常执行（与 A round 决策 1 同 pattern——bridge null 分支不阻塞下游状态机）
if (state.session.template.shouldEnd(filteredData.raw)) {
    finishTest(state.session)
}

// 注：updatePreTriggerBuffer 与 processFilteredData 是 gpsData.collect 流水中的并列步骤（line 343-346），
// processFilteredData 内部不调用 updatePreTriggerBuffer——本 round 不动 collect 流水；
// 修改仅限于 processFilteredData 的 Running 分支内部 if 块的 anchor source。
```

**对比**：

| 方案 | 改动量 | 与 header.startTs 同源 | preTrigger 帧时间分布 | 选择理由 |
|---|---|---|---|---|
| **A：复用 `activeSessionStartTs`** | bridge ~3 行 + startTest ~5 行 + 错误分支日志 | ✅ | N 帧集中在 startTest 内部循环耗时（~毫秒级） | 最小改动 + 与 A round 对称 + 与并行 round 函数级 0 交叉 |
| B：PreTriggerFrame 加 `receivedAtWallClockMs` 字段 | PreTriggerFrame 类型改 + 所有 buffer 写入入口跟 + bridge ~3 行 + startTest ~5 行 | ✅ | N 帧时间分布真实（与 buffer 实际时长一致） | 改动面更大；当前 PERFORMANCE_TEST 下游消费方不依赖精确间隔，过早优化 |
| C：anchor 改用 `activeTestStartTs` 但其语义切到真壁钟 | activeTestStartTs 取值改 + 原本依赖协议时间的下游需要适配 | ❌（除非把 activeTestStartTs 切到 `repository.activeSessionStartTs`，则退化成方案 A） | 同方案 A | 方案 C 退化成 A 的子集，且影响 0-100 计时显示语义，不必要 |

**Rationale 拒绝 B**：

- 当前 PERFORMANCE_TEST 下游消费方（0-100 计时、加速度曲线、SpeedCurve / GForceChart）都基于 sample 的 speed/lat/lon 派生，**不依赖** sample 间精确 tsDeltaMs 间隔。任何"框选 0-100 区间回放"" 加速度峰值 ±2s 回放"等未来功能，时间精度需求是"sample 落在窗口内"而非"sample 间隔精确到 ms"——方案 A 的 ms 级集中度满足这类窗口过滤
- 方案 B 引入 PreTriggerFrame 类型字段属架构层改动，触发广泛 ripple（buffer 写入入口、序列化、可能 simulator 端跟）。当前没有"加速度时间序列高保真回放"明确需求触发，过早优化
- 方案 B 升级路径是 backwards-compatible 的：未来需要时新加 capability `dual-clock-telemetry-correlation`（或类似），PreTriggerFrame 加字段，本 round 的 spec requirement 不需要改写

**Rationale 拒绝 C**：

- 若 `activeTestStartTs` 改用 `repository.activeSessionStartTs` 同源真壁钟，则下游 0-100 计时显示、segment 派生等需要重新审视语义边界，触发非必要 ripple
- 方案 C 实质是把 anchor source 间接化，没解决问题，反而模糊语义

### 决策 2：显式承认 preTrigger buffer 帧时间集中限制（方案 A trade-off）

**选择**：spec Scenario 中显式承认"preTrigger buffer 回填的 N 帧 absoluteTs 集中在 startTest 内部循环耗时窗口（~毫秒级），不分布在 buffer 实际采集时长（典型 1-2 秒）"，作为方案 A 的可接受语义。design Risks 段同步记录该限制 + 升级路径。

**Rationale**：

- spec 必须 self-contained 描述新约束的边界，避免下次开 round 立项时误以为本修复 = "完整时序保真"
- 显式承认 trade-off 是诚实的工件设计，比"假装没问题"更利于未来 round 决策
- 升级路径备案（方案 B）写进 design Risks → Mitigation，给未来 round 留出明确改造点

**反例 Scenario 设计**：spec 中加一条 Scenario 锁死"preTrigger 回填帧 absoluteTs **不要求** 分布在 buffer 实际时长——只要求落在 startTest 调用窗口内"，明确该约束是"接受 not 违规"。

### 决策 3：spec delta 用 ADDED Requirements，与 A round 平行追加

**选择**：本 change 的 `specs/binary-telemetry-storage/spec.md` 用 `## ADDED Requirements`，新增"PERFORMANCE_TEST 采样时间字段时钟域 hygiene 与 anchor 同源"requirement。不动 A round 已加的 LAP_SESSION 那条。

**Rationale**：

- A round 已加的 requirement 主体是"LAP_SESSION 路径 anchor 同源"，本 round 加的是"PERFORMANCE_TEST 路径 anchor 同源"——两条 requirement 平行存在，不冲突可叠加
- 避免改写已合回的 A round spec（OpenSpec delta 工作流不允许修改其他 change 已加的 requirement，除非用 MODIFIED）
- 归档时 OpenSpec 工具会按时序合并 ADDED Requirements 到主 capability spec

**Caveat**：如果将来某个 round 想把"LAP_SESSION 与 PERFORMANCE_TEST 共用同一条 anchor 同源 requirement"重构合并，那时用 MODIFIED 改写两条为一条更通用的——本 round 不主动做这个合并，保持局部可读性。

### 决策 4：合流门槛改为单元测试，真机仅做不回归

**选择**：

1. **强门槛（必须通过才能合流）**：commit 的单元测试套件全绿（4 个 case，见 tasks §3 + spec §1 Scenario）
2. **弱门槛（不回归验证）**：装机跑一次 PERFORMANCE_TEST（0-100 加速测试场景）+ 验证测试结果页 SpeedCurve / 加速度曲线显示不回归 + Records → PERFORMANCE 子 tab 历史记录可读
3. **取消**：原本 deferred memo 设想的"按时间窗口截取 segment"端到端真机验证（窗口消费方还未引入，无法端到端验证）

**Rationale**：

- 本修复的功能正确性由 round trip 单测断言（commit 可审查），与 A round 决策 6 同 pattern
- 真机端到端验证窗口过滤需 Phase 1 单圈 chart 落地后才有触发场景，本 round 时间窗不强求
- 弱门槛真机验证仅排查"修复后是否破坏现有 PERFORMANCE_TEST 主流程"——速度曲线 / 加速度曲线 / 0-100 计时显示等已有功能不应回归

**Caveat（L1 review v1 揭示）**：真机不回归门槛**不能证明本 round 修复生效**——SpeedCurve / GForceChart / 0-100 计时显示等当前下游消费方都不走 binary 窗口过滤路径（PerformanceResultScreen 用 in-memory dataPoints，Records → PERFORMANCE 子 tab 的 SpeedCurve 用 BEST record dataFilePath 顺序读）。即使本 round 修复**没生效**（写入路径仍写错位的 tsDeltaMs），真机不回归断言仍会全过——这与 A round Codex review §3 揭示的"真机验证作为合流门槛但 quick fix 路径绕过修复点"是同模式。修复生效证据完全依赖 §3 单测 case A/D（round trip + 窗口过滤命中）+ §3 case E/F/G grep gate。

### 决策 5：测试归位 `core/data/src/test`

**选择**：新增测试文件 `core/data/src/test/java/com/blazepush/core/data/telemetry/BinaryPerftestTelemetryRoundTripTest.kt`（实施时 grep `BinaryLapTelemetryRoundTripTest.kt` 路径确认实际包路径，与 A round 同 pattern）。

**Rationale**：

- 测试目标是 PERFORMANCE_TEST binary writer-reader round trip + 时钟域 + anchor 一致性，与 binary 持久化代码同模块
- 与 A round 测试文件并列，便于未来 binary 路径相关测试集中维护
- 复用 A round 已建立的 test helper（如直接构造 `BinaryTelemetryWriter` / 读 header / 重建 absoluteTs）模式，减少重复代码

**Caveat**：测试文件 working directory = 模块根（`core/data/`），若需读资源文件 MUST 用 `projectRoot()` helper 或 `Paths.get("core/data/...")` 模块相对路径，避免 v3 高频盲点 #10（裸字面量相对路径在 Gradle test 下 fail）。本 round 4 个 case 都直接构造 in-memory writer/reader，不读资源文件，规避该坑。

### 决策 6：simulator 端不需要改

**选择**：simulator 端 `SimulatorViewModel.kt` 不写 PERFORMANCE_TEST binary（A round §1 已 grep 确认 simulator 端无 lap session binary 写入入口；PERFORMANCE_TEST 同样只在接收端 `TestSessionViewModel.kt` 写入），本 round 不改 simulator 任何代码。

**Rationale**：

- PERFORMANCE_TEST binary 写入入口仅存在于接收端 `TestSessionViewModel`（startTest 与 processFilteredData 两处）
- simulator 端职责是发射 RaceChrono BLE 包，不参与接收端 telemetry 持久化
- A round §1 grep 已建立 baseline："simulator 端无 binary 写入"——本 round tasks §1 二次 grep 确认即可

### 决策 7：保留 `activeTestStartTs` 字段，仅停止用作 binary anchor

**选择**：`TestSessionViewModel.activeTestStartTs` 字段保留原赋值方式（`lockedPreTriggerBuffer.firstOrNull()?.timestamp ?: filteredData.timestamp`，仍是协议时间），仅在 binary writeSample 入口停止读取它作为 anchor。

**Rationale**：

- `activeTestStartTs` 是 PERFORMANCE_TEST 的 0-100 计时显示等下游派生的真相源（如"测试已运行 X.Xs"基于 `currentFrame.timestamp - activeTestStartTs` 协议时间差派生）
- 切换 `activeTestStartTs` 语义会引发非必要 ripple
- 本 round 修复的是 binary anchor 一处，不动其他派生

**对比方案**（拒绝）：

| 替代方案 | 拒绝理由 |
|---|---|
| 删除 `activeTestStartTs` 字段 | 0-100 计时显示等下游派生失去 anchor，需重做 |
| 让 `activeTestStartTs = repository.activeSessionStartTs` | 计时显示语义从"协议时间差"切到"真壁钟差"，可能与现有 UI 显示假设冲突，需全量审视 |

## Risks / Trade-offs

- **Risk**：preTrigger buffer 回填的 N 帧 tsDeltaMs 集中在 startTest 内部循环耗时窗口（~ms 级），失去 buffer 实际采集时长（典型 1-2s）的真实时间分布 → **Mitigation**：决策 2 显式承认 + spec Scenario 锁死可接受语义；升级路径（方案 B：PreTriggerFrame 加 receivedAtWallClockMs 字段）已在 design 备案，时间窗够再立项
- **Risk（L1 review v1 揭示，更近的边界）**：当前 0-100 计时显示走 in-memory dataPoints 路径不影响；但若未来 0-100 计时切到 binary 派生（如"BEST 模式从历史 binary 读取"），preTrigger 段 25 帧 absoluteTs 集中在 ms 级会让派生的 reachTime 偏离真实加速时序 ~1s（buffer 实际采集时长） → **Mitigation**：切到 binary 派生时升级方案 B（PreTriggerFrame 加 receivedAtWallClockMs），或派生路径 skip preTrigger 段（只用 trigger 后的持续写段，与 trigger 状态机对齐）
- **Risk**：旧 PERFORMANCE_TEST binary 文件（修复前写入）的 tsDeltaMs 仍然污染，按真壁钟窗口过滤仍返回空 → **Mitigation**：旧文件按 `readPerformanceSamples` 顺序读路径继续可用（PerformanceResultScreen / SpeedCurve 都走顺序读不过滤）；本 round scope 不含旧文件迁移
- **Risk**：bridge 层改用真壁钟差后，binary 文件的 ts 字段从"GPS 协议时间偏移"变成"接收侧真壁钟偏移"，未来若引入"双端时钟同步分析"需在 binary 之外另存协议 ts → **Mitigation**：当前没有该需求；若未来需要，新加 capability `dual-clock-telemetry-correlation`，binary 字段不动
- **Risk**：本 round 修复后，`activeSessionStartTs == null` 防御分支可能意外触发（如 startSession 失败但仍走入 writeSample 分支） → **Mitigation**：决策 1 错误分支降级为 `FileLogger.e` + skip telemetry 写入但保留 PERFORMANCE_TEST 主流程（pre-trigger buffer 维护、加减速段判定、状态机更新照常），不阻塞用户当前测试；spec Scenario 加一条覆盖该分支的反例
- **Risk**：A round 与本 round 都改 `TestSessionViewModel.kt`，rebase 时可能字段 / import 区冲突 → **Mitigation**：A round 已合回主区，本 round 直接基于主区开 worktree 不需要 rebase A；本 round 修改在 startTest / processFilteredData 两个独立函数体内部，与 B/D round 函数级 0 交叉
- **Risk**：单元测试 `BinaryPerftestTelemetryRoundTripTest.kt` 的 timing 用 `System.currentTimeMillis()` 实测，不同设备运行可能有 ms 级飘动 → **Mitigation**：测试 assertion 用区间断言（"absoluteTs ∈ [startTs, startTs + tolerance]"）而非精确等于；tolerance 取保守值（如 100ms）覆盖 CI 环境抖动
- **Risk**：本 round 不在 simulator 端改任何代码，但 `SimulatorViewModel` 未来若引入 PERFORMANCE_TEST binary 写入入口（如 simulator 自测加速度测试 replay 路径），需补本 round 同样修法 → **Mitigation**：spec Scenario 加 grep gate 覆盖未来扩展（grep `feature/test/src/main/` 全模块的 `tsDeltaMs.*timestamp`，不只 TestSessionViewModel）

## Migration Plan

- **代码**：`TestSessionViewModel.kt` 两处 anchor source 切换 + `FileLogger.e` 错误分支日志（核心 ~8-10 行）+ 单元测试新增（4 case）。无 schema 迁移、无配置迁移、无 feature flag
- **回滚**：单 commit，revert 即恢复
- **数据**：旧 PERFORMANCE_TEST binary 文件不迁移；通过 `readPerformanceSamples` 顺序读路径继续可用
- **真机**：修复 → install apk → 跑一次 0-100 加速测试 → 测试结果页 SpeedCurve / 加速度曲线显示不回归 + Records → PERFORMANCE 子 tab 历史记录可读。串行规则：准备真机时先在对话窗口告知用户并等待授权
- **合流**：单元测试全绿是合流强门槛，真机不回归是弱门槛
- **Phase 0 exit**：本 round 闭环 + A round 已闭环 → Phase 0 唯一缺口补齐 → 触发 Phase Exit Review（`docs/implementation-design/parallel-change-collab.md` §7 流程），盘点 Phase 0 期间产生的所有 deferred memo 决议

## Open Questions

- 测试文件最终模块路径以 A round 实际 binary 持久化代码归位为准（`core/data` 已确认，本 round 直接复用），实施时第一步 grep `BinaryLapTelemetryRoundTripTest` 找路径
- `TelemetryRepository.activeSessionStartTs` 的并发模型：startTest preTrigger 回填 / writeSample / endSession 是否在同一 coroutine context 调用？需确认是否需 atomic 包装——A round 已确认按现有 activeWriter / activeSessionId 同 pattern 处理（复用即可），本 round 不需要额外改
- preTrigger buffer 帧时间集中限制是否需要写进 capability spec 长期约束（需 baseline）vs 仅写在本 round Scenario：**当前决定写 baseline**——一旦未来方案 B 升级，再 MODIFY 该 Scenario，记录"语义升级时间点"
- 是否将 `LapSessionDetailScreen` quick fix（走 `readPerformanceSamples`）回切到 `readLapSamples` / `readPerformanceSamples` 真壁钟窗口路径：**不在本 round 做**，留给 Phase 1 cleanup round；本 round 仅打通 PERFORMANCE_TEST binary 数据底座

### §1 grep 盘点结论（apply 阶段 2026-05-04 实测）

- **§1.1 PERFORMANCE_TEST 写入入口实际行号定位**：`tsDeltaMs\s*=` grep 命中 3 处：line 643（processFilteredData TestState.Running 分支待修）+ line 728（startTest preTrigger 回填段待修）+ line 833（A round 已修复 bridgeGpsToLapTiming）。与 v3 工件预期对齐，无行号漂移
- **§1.2 activeTestStartTs 引用 4 处**：field 声明 line 283 + processFilteredData 读取 line 639 + startTest 赋值 line 722 + finishTest 清空 line 983。**未发现** v3 工件预期的"可能的下游派生引用"——`activeTestStartTs` 当前只在 startTest/processFilteredData/finishTest 三个函数体内使用，本 round 修复后该字段仍保留语义（仅停止其作为 binary anchor 用途，但仍用作 0-100 计时显示等派生 — 实测 dataPoints 派生不依赖此字段，本字段修复后可观察是否仍有真实下游派生消费）
- **§1.3 baseline 跨文件 grep**：`tsDeltaMs\s*=\s*[a-zA-Z_]+\.timestamp\s*-` 在 `feature/test/src/main/java` 全子树命中 2 处，全部位于 `TestSessionViewModel.kt`（line 643 + 728），与本 round scope 覆盖完全一致；修复后应归零
- **§1.4 simulator 路径再确认**：`simulator/src/main` 下无任何 .kt 文件命中 `tsDeltaMs|writeSample`；feature/test 模块下不存在 `SimulatorViewModel.kt`。simulator 端 **无 PERFORMANCE_TEST binary 写入入口**，与 A round §1 LAP_SESSION 路径同样情况，本 round 决策 6 自检通过
- **§1.6 TelemetryRepository.startSession 内 currentTimeMillis 调用次数**：函数体（line 65-92）内 `val startTs = System.currentTimeMillis()` 仅 1 处（line 67），同一 startTs 同时赋给 `entity.startTs`（line 76）+ `entity.endTs`（line 77，临时初始化）+ `writer.open(..., startTs)`（line 84，writer 内部生成 header.startTs）+ `activeSessionStartTs`（line 88）→ **三字段同源 invariant 满足**（`entity.startTs == header.startTs == activeSessionStartTs`），本 round case A 容差断言依赖该 invariant 成立。注：`entity.endTs` 在 startSession 时为占位真壁钟，endSession 时（line 149）独立调用 currentTimeMillis 更新；这与本 round spec line 8 "entity.endTs 与 header.endTs 同源" 描述存在细微差别——但实测 endSession 流程中 `endTs` 同样赋给 entity + writer.close() 写 header.endTs，仍是同一调用结果同源，spec line 8 描述准确
- **§1.7 helper 路径解析**：apply §3 写测试文件之前再 verify（当前推迟到 §3.7 case F 实施前）
