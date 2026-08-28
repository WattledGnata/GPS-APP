## Why

`fix-lap-binary-ts-hygiene` round（A，已归档 `archive/2026-05-02-fix-lap-binary-ts-hygiene`）§1 grep 盘点发现：除了 LAP_SESSION 路径的 `bridgeGpsToLapTiming` 之外，PERFORMANCE_TEST 路径的两处 binary sample 写入入口存在**完全相同的 anchor / 时钟域 bug 模式**：

1. `feature/test/.../viewmodel/TestSessionViewModel.kt` 的 `processFilteredData` 内 `TestState.Running` 分支（PERFORMANCE_TEST Running 期间持续写，当前主区 line 643）：`tsDeltaMs = filteredData.timestamp - anchorTs`
2. 同文件 `startTest` 内 pre-trigger buffer 回填循环（当前主区 line 728）：`tsDeltaMs = frame.timestamp - anchorTs`

**注**：deferred memo `docs/design/perftest-binary-ts-hygiene-deferred.md` 把第 1 处的函数名记作 `bridgeGpsToTelemetry`，与当前主区代码不符——实际函数是 `processFilteredData`（A round 期间或之前的某 round 已经重命名 / 重构）。本工件以当前主区实际代码为准。

两处的 `anchorTs = lockedPreTriggerBuffer.firstOrNull()?.timestamp ?: filteredData.timestamp` 拉自 `RaceChronoParser` 解码后的 GPS 协议 epoch ms；与之相对，`TelemetryRepository.startSession()` 内部用 `System.currentTimeMillis()` 真壁钟生成 `header.startTs / entity.startTs`。reader 重建 `absoluteTs = header.startTs (真壁钟) + tsDeltaMs (协议时间差)` 跨时钟域，与任何"用真壁钟做窗口"的过滤 100% reject。

**当前未暴露**：`LapSessionDetailScreen` 走 `readPerformanceSamples` 顺序读不过滤路径，PERFORMANCE_TEST detail 屏未触发窗口过滤。但 baseline 数据完整性已被破坏——任何按时间窗口截取加速度 segment 的未来功能（Phase 1 单圈 chart cursor 拖动 / "框选 0-100 区间回放" / "加速度峰值时刻 ±2 秒回放"）都将 100% 失效。

本 round 在 PERFORMANCE_TEST 数据写入路径根因消除该污染，让两条 binary 路径（LAP_SESSION + PERFORMANCE_TEST）在 anchor 与时钟域 hygiene 上达成对称。Phase 0 数据层闭环最后一个 round。

## What Changes

- **修复 1（startTest 回填段）**：`TestSessionViewModel.startTest` 在 `telemetryRepository.startSession(PERFORMANCE_TEST)` 后，从 `repository.activeSessionStartTs`（A round 已暴露的只读 property，与 `header.startTs / entity.startTs` 同源）拉 anchor，每帧 `tsDeltaMs = System.currentTimeMillis() - sessionStartTs` —— 不再用 `frame.timestamp - anchorTs`
- **修复 2（processFilteredData TestState.Running 分支）**：PERFORMANCE_TEST Running 期间持续写的入口同样切到 `repository.activeSessionStartTs`，每帧 `tsDeltaMs = System.currentTimeMillis() - sessionStartTs` —— 不再用 `filteredData.timestamp - anchorTs`
- **保留**：`activeTestStartTs` 字段语义不变（仍用作 0-100 计时显示等其他派生），仅停止用作 binary tsDeltaMs anchor
- **新增**：PERFORMANCE_TEST 路径 binary writer-reader round trip 单元测试套件（**8 case**，L1 review 期扩充：A round trip + B anchor 错位反例 + C preTrigger 帧时间集中 + D 持续写 deterministic + E 时钟域 grep 自检 + F 跨文件 grep gate + G fallback 形态对齐含 P4 防 bare return regression + H activeTestStartTs 两步赋值语义）
- **加固**：grep 自检——`processFilteredData` 与 `startTest` 内部不得再出现 `filteredData.timestamp - X` / `frame.timestamp - X` 跨时钟域减法
- **不改**：
  - `BinaryTelemetryWriter` / `BinaryTelemetryReader` 接口与字节布局
  - `TelemetrySessionEntity` / `CrossingEventEntity` 字段
  - `lockedPreTriggerBuffer` 类型与字段（不引入 `receivedAtWallClockMs` 字段）
  - `activeTestStartTs` 字段在 0-100 计时显示等下游派生中的语义
  - LAP_SESSION 路径已由 A round 修复的部分（不复改 `bridgeGpsToLapTiming`）

## Capabilities

### New Capabilities

（无）

### Modified Capabilities

- `binary-telemetry-storage`：补充 PERFORMANCE_TEST 路径的 anchor 同源与时钟域 hygiene requirement，明确 PERFORMANCE_TEST 写入入口（startTest 回填 / bridgeGpsToTelemetry 持续写）`tsDeltaMs` 的 anchor 必须严格等于 `header.startTs`（同时刻、同时钟域），并补 PERFORMANCE_TEST round trip / preTrigger buffer 帧时间集中限制 / 反例捕获 / grep 自检共 4 个 Scenario。注：A round 已为 LAP_SESSION 路径建立 ADDED Requirement，本 round 增量针对 PERFORMANCE_TEST 路径

## Impact

- **接收端代码**：
  - `feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt`：
    - `startTest` 内 pre-trigger buffer 回填循环（当前主区 line 720-734）：anchor source 切到 `repository.activeSessionStartTs`，`tsDeltaMs = System.currentTimeMillis() - sessionStartTs`（核心 ~3-4 行）
    - `processFilteredData` 的 `TestState.Running` 分支内 writeSample 入口（当前主区 line 638-651）：同样切换（核心 ~2 行）
  - 不再读取 `frame.timestamp` / `filteredData.timestamp` 作为 binary anchor 差减数
- **测试代码**：
  - 新增 `core/data/src/test/java/com/blazepush/core/data/telemetry/BinaryPerftestTelemetryRoundTripTest.kt`（实施时确认实际包路径，与 A round `BinaryLapTelemetryRoundTripTest` 同 directory pattern）
- **下游 UI 行为**：
  - `LapSessionDetailScreen` 走 `readPerformanceSamples` 顺序读路径行为不变
  - 解锁"PERFORMANCE_TEST 任意时间窗口的 readPerformanceSamples / readLapSamples"——为 Phase 1 单圈 chart cursor 拖动 / 加速度 segment 框选铺好 reader 侧契约
- **协议兼容性**：无影响。本修复不触碰 RaceChrono BLE 公共协议字段编码
- **数据兼容性**：旧 PERFORMANCE_TEST binary 文件（修复前写入）的 tsDeltaMs 仍然污染，按窗口过滤仍返回空。修复仅对修复后新写入的 session 生效；旧文件仍可被 `readPerformanceSamples` 全量顺序读取
- **preTrigger buffer 帧时间集中限制**（spec 接受语义）：方案 A 下 N 帧 preTrigger 回填的 tsDeltaMs 集中在 startTest 内部循环耗时（~毫秒级），而不是分布在 buffer 实际时长（典型几秒）。该限制在 design.md §3 与 specs Scenario 中显式承认。当前 PERFORMANCE_TEST 下游消费方（0-100 计时、加速度曲线）都基于 sample 的 speed/lat/lon 派生，不依赖 sample 间精确 tsDeltaMs 间隔——可接受。若未来出现"加速度时间序列高保真回放"需求，再升级到方案 B（`PreTriggerFrame` 加 `receivedAtWallClockMs` 字段）
- **并行 round 隔离**：
  - 当前主区无并行 round 改 `TestSessionViewModel.kt` 的 `startTest / bridgeGpsToTelemetry` 函数体（D round style debt cleanup 待启动，依赖 A/B/C/E 全部合回；B round wire-laptime-to-gps-filter 改 `bridgeGpsToLapTiming` 内部，与本 round 函数级不重叠）
  - 允许并行：B round（不同函数体，rebase 友好）
- **真机验证**：
  - **强合流门槛**：commit 的单元测试套件全绿（不依赖真机）
  - **弱合流门槛**：装机跑一次 PERFORMANCE_TEST（0-100 加速测试场景）+ 验证测试结果页 SpeedCurve / 加速度曲线显示不回归 —— 仅做"不回归"健康检查，不做"窗口过滤端到端"验证（窗口消费方还未引入）
  - 真机准备阶段必须先在对话窗口告知用户并等待授权
- **依赖**：A round (`fix-lap-binary-ts-hygiene`) 已合回 `feature/track-tech-v2` 主区（`repository.activeSessionStartTs` property 已暴露并有单元测试覆盖），本 round 直接复用
- **Follow-up**：无新 follow-up 沉淀（本 round scope 自闭环；preTrigger buffer 高保真回放升级路径已在 design 备案，时间窗够再立项）

## 协议兼容性

无影响。本修复仅修改接收端 `TestSessionViewModel` 内部对 binary tsDeltaMs anchor 的取值方式，不触碰：

- RaceChrono BLE 主包 / 时间包字段与编码
- `RaceChronoParser` 解码逻辑
- `BinaryTelemetryWriter` 写入字节布局（header 32 bytes + sample 28 bytes 等公共契约）
- `TelemetrySessionType` 枚举值

发射端 simulator 无任何改动需求（simulator 不写 PERFORMANCE_TEST binary）。
