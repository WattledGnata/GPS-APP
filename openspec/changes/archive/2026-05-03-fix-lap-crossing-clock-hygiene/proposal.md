## Why

`fix-lap-binary-ts-hygiene` round（已合回 daca418 + 归档 599562e）把 lap session binary samples 的 `absoluteTs` 修复为接收侧真壁钟（与 `entity.startTs / endTs` 同时钟域），但 `TelemetryCrossingEvent.crossingTimestampMs` 仍来自 GPS 协议时间（pass-through 自 `LapTimingEngine.processSample` 输入的 `sample.timestampMillis = GpsData.timestamp`）。结果：

- 全 session 真壁钟窗口的 `readLapSamples(file, entity.startTs, entity.endTs)` ✅ 已可用
- per-lap 窗口（用两 crossing 截取一圈）+ sector 窗口的 `readLapSamples(file, crossings[i].timestampMs, crossings[i+1].timestampMs)` ❌ 仍 0 命中（协议时间 vs 真壁钟）

本 round 是 A 的对偶——给 crossing event 加真壁钟字段，让 per-lap / sector segment 窗口可用，**完整闭合 lap binary 时钟域能力**。详细设计见 `docs/design/lap-crossing-clock-hygiene-deferred.md`（9 章）。

## What Changes

- **新增字段**：仅 Room 持久化层加 `crossingWallClockTimestampMs: Long?`（**nullable**）—— `core/domain/.../TelemetryCrossingEvent`（domain DTO）+ `core/data/.../CrossingEventEntity`（Room schema）；**不动** `feature/test/.../laptiming/CrossingEvent.kt`（in-memory pure domain object，由 `LapTimingEngine.processSample` 纯函数产出，无 wallClock 注入路径）
- **写入路径**：`TestSessionViewModel.kt:891` 附近（构造 `TelemetryCrossingEvent` 调用 `repository.writeCrossing` 的位置；rebase 到主区 daca418 + F/G/I/J/K 合回后实际行号；apply 时按 `grep -n "TelemetryCrossingEvent(" TestSessionViewModel.kt` 实际命中位置为准）—— 过线事件触发的同一 ViewModel 协程上下文内取 `System.currentTimeMillis()`，与 `crossing.timestampMillis` 一并传给 `repository.writeCrossing`
- **Room migration**：schema v4 → v5，给 `crossing_events` 表 `ADD COLUMN crossingWallClockTimestampMs INTEGER`（**nullable，无 NOT NULL 约束**，旧 row 自动 NULL）。改用 nullable 而非 NOT NULL DEFAULT 0：v3 review 揭示 `0` 哨兵会让未来 UI 用旧数据时误命中全 session 帧，nullable 能让调用方显式判 null 走 fallback 路径
- **保留语义**：`crossingTimestampMs` 现有字段语义**不动**（UI 显示 HH:mm:ss + lap 时长减法仍用它），避免破坏 `LapDebugExecutionScreen.kt:222-243` 等历史调用方
- **测试**：覆盖双时钟域字段独立可读 / per-lap 窗口 readLapSamples 命中 / 反例锁死（极端偏差 0 命中 + 小偏差错位命中两子 case）/ Room migration v4→v5 SQL 自检 / 写入路径 grep gate（恰好命中 1 次 + 位置接近 writeCrossing）/ 跨文件逃逸 grep gate（仅 TestSessionViewModel.kt 出现）
- **不解锁的下游 UI**：本 round 仅完成数据层，per-lap / sector 的 UI 消费方（Analysis Mode 单圈轨迹、Records LAPS 圈分段）由后续独立 UI round 立项

## 出本 round scope · 但已发现的同主题 follow-up

- **`migrate-lap-duration-derivation-to-wallclock`**（v3 review §P1#4 揭示）：`TelemetryRepository.endSession` line 161-164 + `LapSessionDetailScreen.deriveDetailMetrics` line 471-515 派生 `bestLapMs / lap durations` 仍用 `crossingTimestampMs`（GPS 协议时间）减法。GPS 跨小时切换时（mod 3,600,000 解码 + hourStartMillis 切换）会产生**负数 / 1+ 小时错乱值**，写入 `entity.bestLapMs` 影响 Records 列表展示。本 round 加了 wallClock 字段但**不顺手切派生层**（保持 round scope 紧），等本 round 合回后立项 follow-up round 切派生层到 wallClock（fallback：wallClock=null 时退回 protocolTs）。详见 tasks §11.5 backlog

## Capabilities

### New Capabilities

（无）

### Modified Capabilities

- `binary-telemetry-storage`：补充一条"过线事件采样时间字段双时钟域 hygiene"requirement，明确 crossing event 必须同时持有 GPS 协议时间（UI 显示用）+ 接收侧真壁钟（per-lap 窗口截取 binary samples 用），并补两条 Scenario（写入双时钟域字段 + per-lap segment readLapSamples 用 wallClock 窗口命中）。注：该 capability 由 A56 (`unify-gps-telemetry-persistence`) 引入，A56 仍未归档（其 spec delta 在 changes/unify-gps-telemetry-persistence/specs/binary-telemetry-storage/spec.md），本 round 与 A56 + 已归档的 fix-lap-binary-ts-hygiene 都用 ADDED Requirements，归档时按时序合并到主 spec

## Impact

- **接收端代码（核心约 ~20 行）**：
  - `core/domain/src/main/.../model/TelemetryModels.kt`：`TelemetryCrossingEvent` data class 加 `crossingWallClockTimestampMs: Long?` 字段（~2 行，nullable）
  - `core/data/src/main/.../local/entity/CrossingEventEntity.kt`：加 `crossingWallClockTimestampMs: Long?` 列（~1 行，nullable）
  - `core/data/src/main/.../local/AppDatabase.kt`：`@Database(version = 5)` + 新增 `migration4To5Sql`（仅 1 条 `ALTER TABLE crossing_events ADD COLUMN crossingWallClockTimestampMs INTEGER`）+ `migration4To5` Migration object（~12 行）
  - `core/data/src/main/.../local/dao/CrossingEventDao.kt`：原 DAO 接口不变（@Insert/insertInTransaction 走 entity，自动包含新列）
  - `core/data/src/main/.../repository/TelemetryRepository.kt`：`writeCrossing` 方法的 entity 字段映射加 wallClock；`getCrossings` 的 `toDomain()` 映射加 wallClock（~3 行）
  - **`feature/test/src/main/java/com/blazepush/feature/test/di/AppModule.kt`**（v3 review 第 3 轮 §C#1 修订：实际位置在 feature/test 模块而非 core/data，因 core/data 模块无 DI 入口；Koin DSL `databaseModule` 内 `single { Room.databaeBuilder(...).addMigrations(...).build() }` lambda 块）：line 51 `.addMigrations(AppDatabase.migration3To4)` 改为 `.addMigrations(AppDatabase.migration3To4, AppDatabase.migration4To5)`（同一行 + 同一参数列表 + 同一 single block）
  - `feature/test/src/main/.../viewmodel/TestSessionViewModel.kt:891` 附近：构造 `TelemetryCrossingEvent` 时计算 `crossingWallClockTimestampMs = System.currentTimeMillis()`（~1 行；apply 时按实际 grep 命中位置为准，行号会随后续 round 漂移）
  - **不改** `feature/test/.../laptiming/CrossingEvent.kt`（in-memory pure domain object，由 LapTimingEngine 纯函数产出）
- **测试代码（约 ~120 行新增）**：
  - `core/data/src/test/.../local/AppDatabaseMigrationSqlTest.kt`：扩展加 `migration4To5Sql` 字符串自检 + Migration startVersion/endVersion 自检（~10 行新增）
  - `core/data/src/test/.../repository/CrossingClockRoundTripTest.kt` 新文件：~5 case
    - case A：`writeCrossing` 后 `getCrossings` 双字段都符合预期（误差 < 100ms）
    - case B：写 N 帧 binary samples + 2 个 crossing（wallClock 在中间时刻），readLapSamples 用两 crossing.wallClockTimestampMs 截取，命中正确帧数
    - case C：crossing 协议时间 `crossingTimestampMs` 跟 binary samples 不同时钟域时，readLapSamples 用它截取仍 0 命中（反例锁死语义）
    - case D：UI 显示语义不回归 grep gate—— `LapDebugExecutionScreen.kt` 等 UI 文件仍用 `crossingTimestampMs`（不误用 wallClock）
    - case E：Migration v4→v5 SQL 字符串恰好包含 `ALTER TABLE crossing_events ADD COLUMN crossingWallClockTimestampMs INTEGER`（无 NOT NULL 无 DEFAULT），旧 row 的 wallClock 列值默认 NULL
- **协议兼容性**：无影响。本 round 不触碰 RaceChrono BLE 公共协议字段编码
- **数据兼容性**：
  - 旧 binary 文件 + 旧 crossing 数据（修复前写入）：crossing.wallClockTimestampMs = NULL → 调用方 MUST 显式判 null fallback 到全 session 路径（per-lap 不可用，与现状一致，不崩溃）
  - 修复后新 session：crossing.wallClockTimestampMs = 真壁钟 → per-lap 窗口 readLapSamples 正确命中
  - 不引入数据迁移路径（本 round scope 是数据层，UI 消费在后续 round）
- **下游 UI 行为**：
  - `LapDebugExecutionScreen.kt:222-243`（HH:mm:ss + lap 时长减法）继续用 `crossingTimestampMs` → 不变、不回归
  - `LapSessionDetailScreen.kt`：当前 quick fix 路径（走 entity 字段 + 顺序读）不依赖 crossing 任何字段 → 不变
  - 未来 UI 消费方（Analysis Mode 单圈轨迹 / Records LAPS sub-tab 圈分段）：独立 UI round 实施时使用 `crossingWallClockTimestampMs` 作为窗口 → 解锁
- **真机验证**：本 round 是数据层，下游 UI 暂无消费，**无端到端真机可见证据**（同 A round）。功能正确性证据由单测覆盖（含 case B 端到端 round trip：crossing wallClock 写入 → readLapSamples 命中）。如未来 per-lap UI 落地时再做端到端真机验证
- **依赖与时序**：
  - **必须在 `fix-lap-binary-ts-hygiene` 合回归档后立项**（已满足，daca418 / 599562e）
  - **不依赖 A56 (`unify-gps-telemetry-persistence`) 归档**：spec delta 用 ADDED 与 A56 + A round 平行存在
- **并行 round 隔离**：当前 active round 为 B (`wire-laptime-to-gps-filter`，待启动) + D (`track-tech-v2-style-debt-cleanup`，待启动)，与本 round 文件独占清单见 `docs/implementation-design/parallel-change-collab.md` §5 登记表
