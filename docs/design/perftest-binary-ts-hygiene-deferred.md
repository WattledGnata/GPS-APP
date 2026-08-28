# Performance Test binary 时间轴 hygiene — 延期立项设计 memo

**状态**：已立项 round `fix-perftest-binary-ts-hygiene`（2026-05-03 工件就位 + L1 review v1 修订到位）

**起源**：`fix-lap-binary-ts-hygiene` round §1 grep 盘点（2026-05-01）发现——除了 LAP_SESSION 路径的 bug，PERFORMANCE_TEST 路径也有完全相同的 anchor/时钟域 bug 模式

**关联**：本文件由 `fix-lap-binary-ts-hygiene` round 的 design.md Open Questions + tasks.md §8.4 backlog 引用

**2026-05-04 校对（L1 review v1 同步）**：memo 原文用旧函数名 `bridgeGpsToTelemetry`，与当前主区代码不符——实际函数是 `processFilteredData` 的 `TestState.Running` 分支（line 638-651）。本 memo 已全文替换。startTest preTrigger 回填段当前主区位于 line 720-734。FileLogger API 仅有 `d / v / e`（无 `w`），原 memo §3.1 代码示例的 `FileLogger.e` 应理解为 `FileLogger.e` 与 A round line 843 同 pattern。详见 `openspec/changes/fix-perftest-binary-ts-hygiene/{proposal,design,specs/binary-telemetry-storage/spec,tasks}.md`。

---

## 1. 现象

`fix-lap-binary-ts-hygiene` round 在 §1 盘点 grep `tsDeltaMs\s*=` 时发现：除了 line 598 的 `bridgeGpsToLapTiming`（LAP_SESSION 路径，本 round 主修），还有两处同样模式的写入：

- `feature/test/.../TestSessionViewModel.kt:415` `processFilteredData`（PERFORMANCE_TEST Running 期间持续写）：`tsDeltaMs = filteredData.timestamp - anchorTs`
- `feature/test/.../TestSessionViewModel.kt:500` `startTest`（PERFORMANCE_TEST pre-trigger buffer 回填）：`tsDeltaMs = frame.timestamp - anchorTs`

当前 `LapSessionDetailScreen` 走 `readPerformanceSamples` 顺序读不过滤路径，PERFORMANCE_TEST 路径的 bug **还没暴露但存在**——任何按时间窗口截取加速度 segment 的未来功能（如"框选 0-100 区间回放"、"加速度峰值时刻 ±2 秒回放"）都将失效。

## 2. 根因（与 lap session bug 同模式）

### 2.1 anchor 是 GPS 协议时间

```kotlin
// TestSessionViewModel.startTest line 493-494
val anchorTs = lockedPreTriggerBuffer.firstOrNull()?.timestamp ?: filteredData.timestamp
activeTestStartTs = anchorTs
```

`filteredData.timestamp` 与 `frame.timestamp` 都来自 `RaceChronoParser` 解码的 GPS 协议 epoch ms。`anchorTs` = 协议时间。

### 2.2 与 header.startTs 跨时钟域

```kotlin
// TelemetryRepository.startSession line 38-58
val startTs = System.currentTimeMillis()              // 真壁钟
sessionDao.insert(TelemetrySessionEntity(startTs = startTs, ...))
writer.open(file.absolutePath, type, startTs)         // header.startTs = 真壁钟
```

PERFORMANCE_TEST 同样在 startSession 时让 `header.startTs / entity.startTs` 用真壁钟，但 sample 的 `tsDeltaMs` 用协议时间差。reader 重建 `absoluteTs = header.startTs (真壁钟) + tsDeltaMs (协议时间差)` → 与任何"真壁钟窗口"过滤都 100% reject。

### 2.3 与 lap session bug 的对称性

| 维度 | LAP_SESSION（fix-lap-binary-ts-hygiene 修） | PERFORMANCE_TEST（本 memo） |
|---|---|---|
| anchor 字段 | `lapAnchorTs = activeLapStartSystemTs` (UI 进入 ts，真壁钟) | `anchorTs = activeTestStartTs` (preTrigger buffer first frame ts，协议时间) |
| sample.timestamp 字段 | `gpsData.timestamp` (协议时间) | `filteredData.timestamp` / `frame.timestamp` (协议时间) |
| 减法 | 协议 - 真壁钟 = 跨时钟域 | 协议 - 协议 = 同时钟域内 |
| 与 header.startTs 关系 | 跨 + anchor 错位 | 跨（anchor 错位是次要问题，因 anchor 也不与 header 同源） |
| 反例 | session 窗口 readLapSamples 100% reject | 任何真壁钟窗口的 readPerformanceSamples（**当前实现是顺序读不过滤**，bug 未暴露） |

→ PERFORMANCE_TEST bug 的暴露条件是"未来引入按时间窗口截取加速度 segment 功能"，时间窗够立项。

### 2.4 影响面

| 功能 | 现状 | 修复后 |
|---|---|---|
| `LapSessionDetailScreen` 走 `readPerformanceSamples` 顺序读 | 可用（绕开窗口） | 不变 |
| 未来"按时间窗口截取加速度 segment"（框选回放、峰值时刻 ±2s） | 不可用 | 解锁 |
| 0-100 / 100-0 加速度数据完整性（点对点能读全） | 可用 | 不变 |

## 3. 修复方案（与 lap session 路径对称）

### 3.1 方案 A（推荐）：复用 fix-lap-binary-ts-hygiene 的 `repository.activeSessionStartTs` property

PERFORMANCE_TEST 的 startTest 与 processFilteredData 同样从 `repository.activeSessionStartTs` 拉取 anchor：

```kotlin
// TestSessionViewModel.startTest line 493 附近
activeTestSessionId = telemetryRepository.startSession(TelemetrySessionType.PERFORMANCE_TEST)
val sessionStartTs = telemetryRepository.activeSessionStartTs
if (sessionStartTs != null) {
    for (frame in lockedPreTriggerBuffer) {
        telemetryRepository.writeSample(
            TelemetrySample(
                tsDeltaMs = System.currentTimeMillis() - sessionStartTs,  // 真壁钟差
                ...
            )
        )
    }
}

// TestSessionViewModel.processFilteredData line 411 附近
val sessionStartTs = telemetryRepository.activeSessionStartTs
if (sessionStartTs != null) {
    telemetryRepository.writeSample(
        TelemetrySample(
            tsDeltaMs = System.currentTimeMillis() - sessionStartTs,
            ...
        )
    )
}
```

- **依赖**：`fix-lap-binary-ts-hygiene` 已合回（`repository.activeSessionStartTs` property 已暴露）
- **改动**：~6-8 行（两处入口 + 错误分支日志）

**关键 caveat**：preTrigger buffer 回填的 N 帧（写入 startTest 内部循环）会用**写入瞬间**的 `currentTimeMillis()` 作为 anchor 差，而不是 frame 的实际接收时刻。这会让 N 帧的 tsDeltaMs 集中在某个小窗口（写入循环的耗时 ~毫秒级）而不是分布在 preTrigger buffer 的实际时长（典型几秒）。**这是新的语义**——必须在 design 显式承认 + 决定是否需要 buffer frame 自己记录"接收瞬间真壁钟"。

### 3.2 方案 B：preTrigger buffer 帧级真壁钟

让 `lockedPreTriggerBuffer` 的 frame 类型加一个 `receivedAtWallClockMs` 字段，processFilteredData 的入口在第一时间记录真壁钟存进去：

```kotlin
data class PreTriggerFrame(
    val gpsData: FilteredGpsData,
    val receivedAtWallClockMs: Long,  // 新增：接收瞬间真壁钟
)
```

startTest 回填时：`tsDeltaMs = frame.receivedAtWallClockMs - sessionStartTs`

- 优点：N 帧 tsDeltaMs 时间分布真实
- 缺点：需要改 PreTriggerFrame 类型 + 所有 buffer 写入入口（preTriggerBuffer.add 所有调用点）
- 改动面更大

### 3.3 方案 C：anchor 改用 `activeTestStartTs` 但 `activeTestStartTs` 改用真壁钟

让 `activeTestStartTs = preTriggerBuffer.firstOrNull()?.timestamp ?: filteredData.timestamp` 改用 `System.currentTimeMillis()`，但要保证它跟 `repository.activeSessionStartTs` 同源——这就退化成方案 A 的子集（不如直接用 repository property）。

不推荐。

## 4. 推荐方案 + 数学/性能分析

**推荐方案 A**，并显式承认 preTrigger buffer 回填的 N 帧 tsDeltaMs 集中在写入循环耗时窗口的限制。

理由：
- 方案 A 改动量小（~6-8 行），与 lap session round 的 anchor source 复用
- 方案 B 在加 `receivedAtWallClockMs` 字段才能真正解决 buffer 时间分布问题，但当前 PERFORMANCE_TEST 的下游消费方（0-100 计时、加速度曲线）都是基于 sample 的 speed/lat/lon 派生，不依赖 sample 间的精确 tsDeltaMs 间隔——可接受方案 A 的 buffer 帧时间集中
- 若未来有"加速度时间序列回放"需求，再升级到方案 B

### 4.1 数学

PreTrigger buffer 容量典型 25-50 帧（25Hz × 1-2 秒），方案 A 下这 N 帧的 tsDeltaMs 散布在 startTest 内部循环耗时（~几毫秒）。reader 重建 absoluteTs 也是这个集中时段，落在 entity.startTs ± 几毫秒内——session 窗口过滤仍命中。

接收期间持续写的样本（line 411-422）每帧间隔正常（~40ms），不受 buffer 回填影响。

## 5. 实施约束

1. **MUST 在 fix-lap-binary-ts-hygiene 合回后立项**（依赖 `repository.activeSessionStartTs` property 已暴露）
2. **MUST 用方案 A**（不引入 PreTriggerFrame 字段，保持 buffer 类型不变）
3. **MUST** 在 design 显式承认 preTrigger buffer 帧的 tsDeltaMs 时间集中限制
4. **MUST** 加单元测试：startTest + writeSample 后查询 binary，所有 sample 的 absoluteTs 落在 `[entity.startTs, entity.startTs + window]` 范围内
5. **MUST** grep 自检：`processFilteredData` / `startTest` 不再出现 `filteredData.timestamp - anchorTs` 或 `frame.timestamp - anchorTs` 这种跨时钟域减法
6. **MUST NOT** 改 `TestSessionViewModel.activeTestStartTs` 的语义（保留用作 0-100 计时显示等其他派生）

## 6. 单元测试覆盖建议

新 round `fix-perftest-binary-ts-hygiene` 的测试套件 MUST 含以下 scenario：

- **`PERFORMANCE_TEST round trip 在 anchor 同源下窗口过滤命中`**：startTest + 写入 N 帧 + endSession + readPerformanceSamples 全窗口验证 absoluteTs 范围
- **`PERFORMANCE_TEST 时间窗口过滤剔除窗外`**（与 lap session case C 对称）
- **`preTrigger buffer 回填帧 absoluteTs 集中在 startTest 内部窗口`**：显式 assert N 帧 absoluteTs 散布在 ms 级别，不要求散布在 buffer 实际时长——这是 spec 接受的语义
- **`grep 自检`**：`filteredData.timestamp - anchorTs` / `frame.timestamp - anchorTs` 在 `feature/test/src/main/` 内为空

## 7. 与 fix-lap-binary-ts-hygiene 的协同

| Round | 解决问题 | 解锁 |
|---|---|---|
| `fix-lap-binary-ts-hygiene` | LAP_SESSION binary samples absoluteTs 对齐真壁钟 + anchor 同源 | LAP_SESSION 任何窗口的 readLapSamples |
| `fix-perftest-binary-ts-hygiene`（本 memo） | PERFORMANCE_TEST binary 同样修法 | PERFORMANCE_TEST 任何窗口的 readPerformanceSamples / readLapSamples |

**串行关系**：本 round 必须在 `fix-lap-binary-ts-hygiene` 合回后立项实施（复用 property）。

**文件冲突**：本 round 改 `TestSessionViewModel.kt:411-422 + 493-507`，fix-lap-binary-ts-hygiene 改 line 598 附近。两 round 同文件不同函数，rebase 友好。

## 8. 不立刻并入 fix-lap-binary-ts-hygiene 的理由

- 本 round 主题是 LAP_SESSION 路径，proposal Why 明确说源自 `LapSessionDetailScreen TOP SPEED` 真机问题。PERFORMANCE_TEST 是另一个 session type，scope 不同
- PERFORMANCE_TEST 的 bug **当前未暴露**（detail 屏走 readPerformanceSamples 顺序读不过滤），不是紧急问题
- 并入会破坏本 round 的 "session windowed lap binary 解锁" 单一焦点 + 增加测试矩阵
- 时间窗够立项

## 9. 立项节奏

预计独立 round 工件量：
- proposal / design / specs / tasks
- 代码改动：`TestSessionViewModel.kt` ~6-8 行（两处入口 + 错误分支）
- 单元测试 ~4 case
- 估时：半天工件 + 半天实施 + 半天 Codex review + 真机端到端验证（暂无明确触发场景，可只做不回归 + 单测）

---

**索引位置**：`openspec/changes/fix-lap-binary-ts-hygiene/tasks.md` §8.4 follow-up backlog 引用本文件
