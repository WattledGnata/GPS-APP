# lap-telemetry-sector-derivation Specification

## Purpose
TBD - created by archiving change future-sector-derivation. Update Purpose after archive.
## Requirements
### Requirement: getLapTelemetry 从 lap 窗口内 accepted Sector 过线派生多元素 sectorBoundaries

`TelemetryRepository.getLapTelemetry(sessionId: String, lapIndex: Int): LapTelemetry?`（`core/data/.../repository/TelemetryRepository.kt`）SHALL 从该 session 已持久化的 crossing 中派生 `LapTelemetry.sectorBoundaries`，使其反映圈内真实的 Sector gate 过线分段，而非恒为单元素。

实现 MUST 满足：

1. **数据源**：复用 `getLapTelemetry` 内已调用的 `crossingDao.queryBySessionId(sessionId)` 返回的 crossing 列表（与 StartFinish 窗口派生同一份内存数据），**MUST NOT** 为 sector 派生新增 DAO 查询或新增 @Query。
2. **filter 条件**：sector 候选 = crossing 中满足全部三条的项：
   - `gateType.equals("Sector", ignoreCase = true)`
   - `accepted == true`
   - `crossingWallClockTimestampMs != null`
3. **时钟域**：sector boundary 的时间值 **MUST** 用 `crossingWallClockTimestampMs`（接收侧真壁钟，与 `lapStartWallClock` / `lapEndWallClock` / binary samples `absoluteTsMs` 同时钟域）。**MUST NOT** 用 `crossingTimestampMs`（GPS 协议时钟）作为 sector boundary 的时间值或窗口判定基——理由：lap 窗口 `[lapStartWallClock, lapEndWallClock)` 是 wallClock 域，混用 GPS 协议时钟（mod 3,600,000 回绕 + hourStart 切换）会致窗口判定错位（与 `unify-lap-count-pairing-semantics` 确立的"圈配对身份统一 wallClock"同源约束）。
4. **窗口约束**：sector 候选的 `crossingWallClockTimestampMs` **MUST** 落在半开区间 `[lapStartWallClock, lapEndWallClock)`（`>= lapStartWallClock && < lapEndWallClock`）。窗口外的 sector 过线（属相邻圈或越界）**MUST** 被排除。
5. **去重首项**：派生出的 sector wallClock 集合 **MUST NOT** 含 `== lapStartWallClock` 的项（圈起点已作为 sectorBoundaries 首元素，避免重复首段产生 size-0 矩形）。
6. **排序与组装**：sector wallClock 按升序排序得 `sectorWallClocks`；`sectorBoundaries = listOf(lapStartWallClock) + sectorWallClocks`。
7. **首元素不变式**：`sectorBoundaries.first()` **MUST** == `lapStartWallClock`（维持 `LapTelemetry` 头注释 `LapTelemetry.kt:26` "sectorBoundaries 首项 == lapStartWallClock" 契约 + SectorBar `SectorBar.kt:45` 警告不触发）。
8. **回退语义**：当 `sectorWallClocks` 为空（无 sector 门赛道 / 无 accepted sector / sector wallClock 全 null / 窗口内无 sector）时，`sectorBoundaries` **MUST** == `listOf(lapStartWallClock)`（单段回退）。该回退 **MUST NOT** 抛异常、**MUST NOT** 返回空列表（保持与 baseline 单段行为收敛，SectorBar 拿单元素画 1 段全圈条）。
9. **不影响其他字段**：本派生 **MUST NOT** 改动 `getLapTelemetry` 的其他返回字段（samples / lapStartWallClock / lapEndWallClock / lapDurationMs / trackId / trackNameSnapshot）、**MUST NOT** 改动 StartFinish 窗口派生逻辑、**MUST NOT** 改动 lapIndex 越界 / binary 缺失 / wallClock null 的既有 null 返回行为。

#### Scenario: 完整圈派生多段 sectorBoundaries

- **GIVEN** 一个 LAP_SESSION，accepted StartFinish crossing 的 wallClock 配对出 lapIndex=0 的窗口 `[1000, 5000)`（lapStartWallClock=1000, lapEndWallClock=5000）
- **AND** 该 session 含 2 个 accepted Sector crossing，wallClock 分别 = 2500（s1）/ 3800（s2），均落在 `[1000, 5000)` 窗口内
- **WHEN** 调 `getLapTelemetry(sessionId, 0)`
- **THEN** `sectorBoundaries == listOf(1000L, 2500L, 3800L)`（前置 lapStart=1000 + 升序 sector wallClock）
- **AND** `sectorBoundaries.first() == 1000L`（== lapStartWallClock）
- **AND** `sectorBoundaries.size == 3`（喂 SectorBar 后配合自动补 lapEnd=5000 画 3 段）

#### Scenario: 无 sector 过线回退单段（不回归 baseline）

- **GIVEN** 一个 LAP_SESSION，lapIndex=0 窗口 `[1000, 5000)`，但该 session **无任何** accepted Sector crossing（无 sector 门赛道 / debug 宽容闭合未过 sector 门）
- **WHEN** 调 `getLapTelemetry(sessionId, 0)`
- **THEN** `sectorBoundaries == listOf(1000L)`（仅 lapStartWallClock，单段回退）
- **AND** `getLapTelemetry` 不抛异常、返回非 null（其他字段正常）
- **AND** 该回退与 baseline 单段行为完全一致（SectorBar 画 1 段全圈条）

#### Scenario: 反例——窗口外 sector 过线 MUST NOT 混入本圈

- **GIVEN** 一个多圈 session，lapIndex=0 窗口 `[1000, 5000)`、lapIndex=1 窗口 `[5000, 9000)`
- **AND** 该 session 含 4 个 accepted Sector crossing：wallClock = 2500 / 3800（属圈 0 窗口内）+ 6200 / 7500（属圈 1 窗口内）
- **WHEN** 调 `getLapTelemetry(sessionId, 0)`
- **THEN** `sectorBoundaries == listOf(1000L, 2500L, 3800L)`（**仅圈 0 窗口内的 sector**）
- **AND** 6200 / 7500 **MUST NOT** 出现在圈 0 的 sectorBoundaries 中（窗口外排除）
- **AND** 若实现漏掉窗口过滤（取全部 sector），`sectorBoundaries` 会含 6200/7500 → 该断言 fail，锁死"MUST 按 wallClock 窗口过滤"
- **AND** 调 `getLapTelemetry(sessionId, 1)` 时 `sectorBoundaries == listOf(5000L, 6200L, 7500L)`（仅圈 1 窗口内）

#### Scenario: rejected / null-wallClock Sector crossing 被排除

- **GIVEN** 一个 session，lapIndex=0 窗口 `[1000, 5000)`
- **AND** 窗口内有 3 个 Sector crossing：(a) wallClock=2500, accepted=true（有效）；(b) wallClock=3000, accepted=false, reason=UnexpectedGateOrder（乱序，rejected）；(c) wallClock=null, accepted=true（历史 row 无 wallClock）
- **WHEN** 调 `getLapTelemetry(sessionId, 0)`
- **THEN** `sectorBoundaries == listOf(1000L, 2500L)`（仅 (a) 有效）
- **AND** rejected 的 (b) 与 null-wallClock 的 (c) 均被排除
- **AND** 若实现误纳入 rejected 或 null-wallClock 项，断言 fail

#### Scenario: 反例——sector boundary MUST NOT 用 crossingTimestampMs（GPS 协议时钟）

- **GIVEN** 一个 session，lap 窗口按 wallClock = `[1000, 5000)`；窗口内有 1 个 accepted Sector crossing，其 `crossingWallClockTimestampMs = 2500`（落窗口内）但 `crossingTimestampMs = 99999`（GPS 协议时钟，落窗口外）
- **WHEN** 调 `getLapTelemetry(sessionId, 0)`
- **THEN** `sectorBoundaries == listOf(1000L, 2500L)`（用 wallClock=2500 判定在窗口内并取 wallClock 值）
- **AND** 若实现误用 `crossingTimestampMs=99999` 做窗口判定，该 sector 会被错误排除（99999 不在 [1000,5000)）→ sectorBoundaries 退化成 `[1000]` → 断言 `sectorBoundaries == listOf(1000L, 2500L)` fail，锁死"MUST 用 crossingWallClockTimestampMs"

#### Scenario: sector wallClock 恰等于 lapStart 时去重不重复首项

- **GIVEN** 一个 session，lap 窗口 `[1000, 5000)`；窗口内有 2 个 accepted Sector crossing：wallClock = 1000（恰等于 lapStart，退化）/ 3800
- **WHEN** 调 `getLapTelemetry(sessionId, 0)`
- **THEN** `sectorBoundaries == listOf(1000L, 3800L)`（wallClock==lapStart 的项被去重，不产生 `[1000, 1000, 3800]`）
- **AND** `sectorBoundaries.first() == 1000L` 且列表无重复相邻 == 项（SectorBar 不产生 size-0 段）

#### Scenario: 部分 sector 过线（不全）按实际派生，不伪造缺失

- **GIVEN** TFIC 赛道定义 2 个 sector 门（s1/s2），但某圈（debug 宽容闭合）只过了 s1（1 个 accepted Sector crossing，wallClock=2500），s2 未过
- **WHEN** 调 `getLapTelemetry(sessionId, lapIndex)`
- **THEN** `sectorBoundaries == listOf(lapStartWallClock, 2500L)`（按实际过的 1 个 sector 派生 2 段，不补造 s2 的假 boundary）
- **AND** sectorBoundaries.size 反映真实过线数 + 1，**MUST NOT** 用 Track.sectorGates.size 补齐缺失 sector 的假数据

