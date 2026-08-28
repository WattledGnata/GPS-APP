## Why

`add-lap-session-phase1` round §8 真机验证发现：LapSessionDetailScreen 的 TOP SPEED 显示 `--`。溯源到 lap session binary 写入的 `tsDeltaMs` 字段同时存在两个独立 bug：

1. **跨时钟域**：`tsDeltaMs = gpsData.timestamp - lapAnchorTs` 把协议解码 epoch ms 与接收侧 `System.currentTimeMillis()` 真壁钟混合相减
2. **anchor 错位**（Codex review §1 揭示）：即便都改用真壁钟，`lapAnchorTs`（`activeLapStartSystemTs`，UI 进入 lap 模式时记录）跟 `header.startTs`（`TelemetryRepository.startSession()` 内部懒启动时记录）不在同一时刻取，差值是"等首帧 GPS 到段3"的等待时间，仍会让 `absoluteTs = header.startTs + tsDeltaMs` 整体向未来偏移

两 bug 叠加导致 `BinaryTelemetryReader` 按时间窗口过滤 lap 样本时 100% reject，`readLapSamples` 永远空。

当前主 round 已用 `readPerformanceSamples`（顺序读不过滤）作为 detail 屏 quick fix，但 baseline 的"按时间窗口截取样本"能力仍不可用。本 round 在数据写入路径根因消除该污染。**注**：本 round 仅解锁"用 session start/end 真壁钟做窗口"的 readLapSamples 调用；per-lap / sector segment 受 crossing event 时钟域问题阻塞（`crossingTimestampMs` 来自 GPS 协议时间），延期立项 `fix-lap-crossing-clock-hygiene`（设计 memo 见 `docs/design/lap-crossing-clock-hygiene-deferred.md`）。

## What Changes

- **修复 1（anchor 同源）**：`TelemetryRepository` 暴露只读 property `activeSessionStartTs: Long?`，在 `startSession()` 时与 `header.startTs / entity.startTs` 同时刻赋值（同一次 `currentTimeMillis()` 调用结果），在 `endSession()` 时清空
- **修复 2（bridge 切 anchor）**：`TestSessionViewModel.bridgeGpsToLapTiming` 改用 `tsDeltaMs = System.currentTimeMillis() - repository.activeSessionStartTs`，不再用 `lapAnchorTs`
- **覆盖**：grep 全工程定位所有写入 lap session binary 的入口（含 simulator replay 路径），同样原则修复
- **新增**：lap session binary writer-reader round trip 单元测试套件（5-6 case），覆盖时钟域一致性、anchor 同源、anchor 错位反例、与 `readPerformanceSamples` 兼容性
- **加固**：grep 自检——`bridgeGpsToLapTiming` 与等价入口不得再出现 `gpsData.timestamp - X` 跨时钟域减法，也不得出现 `currentTimeMillis - lapAnchorTs / activeLapStartSystemTs` 这种 anchor 错位减法
- **不改**：`BinaryTelemetryWriter` / `BinaryTelemetryReader` 的接口与字节布局；`TelemetrySessionEntity` / `CrossingEventEntity` 字段；`crossing.timestampMillis` 的语义；A56 主 capability 已定义的 requirements

## Capabilities

### New Capabilities

（无）

### Modified Capabilities

- `binary-telemetry-storage`：补充一条"采样时间字段时钟域 hygiene 与 anchor 同源"requirement，明确 `tsDeltaMs` 的 anchor 必须严格等于 `header.startTs`（同时刻、同时钟域），并补 round trip / anchor 同源 / 反例捕获等 6 个 Scenario。注：该 capability 由 A56 (`unify-gps-telemetry-persistence`) 引入，A56 归档前两 change 的 ADDED Requirements 在归档时按时序合并

## Impact

- **接收端代码**：
  - `core/data/src/main/java/com/blazepush/core/data/repository/TelemetryRepository.kt`：加只读 property `activeSessionStartTs: Long?` + 在 `startSession() / endSession()` 内 1-2 行赋值/清空（核心 ~3-5 行）
  - `feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt:562` 附近：bridge 层改用 `repository.activeSessionStartTs` 作为 anchor（核心 ~2 行）
  - `feature/test/src/main/java/com/blazepush/feature/test/viewmodel/SimulatorViewModel.kt`：按决策 3 检查 simulator replay 路径下 tsDeltaMs 计算是否同样需要修正
- **测试代码**：
  - 新增 `core/data/src/test/java/com/blazepush/core/data/telemetry/BinaryLapTelemetryRoundTripTest.kt`（实施时确认实际包路径）
- **下游 UI 行为**：
  - `LapSessionDetailScreen` quick fix 路径（`readPerformanceSamples`）行为不变；本 round 不回切到 `readLapSamples`（留给后续 cleanup round）
  - 解锁"用 session start/end 真壁钟做窗口"的 readLapSamples 调用，未来"全 session 全样本"派生（如 TOP SPEED 全段、最高 g、最快 sector 起点）可用
  - 不解锁 per-lap / sector segment（受 crossing 时钟域阻塞，见上方 Why 段说明）
- **协议兼容性**：无影响。本修复不触碰 RaceChrono BLE 公共协议字段编码
- **数据兼容性**：旧 binary 文件（修复前写入）的 tsDeltaMs 仍然污染，按窗口过滤仍返回空。修复仅对修复后新写入的 session 生效。旧文件仍可被 `readPerformanceSamples` 全量读取
- **并行 round 隔离**（Codex review §4 修订）：
  - **MUST NOT 改动** `core/data/src/main` 中的 entity、Room migration、DAO 接口（C 在改）
  - **允许的 core/data 改动**：在 `TelemetryRepository.kt` 新加只读 property + 在 startSession/endSession 内 1-2 行赋值/清空（与 C 的 DAO/entity 改动文件隔离，rebase-friendly）
  - **允许新增** `core/data/src/test` 下测试文件（不算改 main 代码）
  - **独占** `feature/test/src/main/.../TestSessionViewModel.kt` 与 simulator 端等价入口（如有）
  - 详见 `docs/implementation-design/parallel-change-collab.md` §5 登记
- **真机验证**（Codex review §3 修订）：
  - **强合流门槛**：commit 的单元测试套件全绿（不依赖真机）
  - **弱合流门槛**：真机 install apk → LapSession 跑完 → detail 屏正常打开 + 显示历史一致字段（仅做"不回归"健康检查；TOP SPEED 真机端到端验证留给 detail 回切 cleanup round）
  - 真机准备阶段必须先在对话窗口告知用户并等待授权，避免与并行 round 真机验证撞车
- **Follow-up**：crossing event 时钟域 hygiene 设计 memo 沉淀到 `docs/design/lap-crossing-clock-hygiene-deferred.md`，立项名 `fix-lap-crossing-clock-hygiene`（见 tasks §8）
