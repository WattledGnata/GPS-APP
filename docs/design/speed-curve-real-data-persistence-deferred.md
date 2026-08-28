# SpeedCurve 真实数据持久化（延期立项 memo）

> ⚠️ **2026-05-04 状态更新（合并到 lap-data-readers round）**：本 memo 的核心数据契约（`getDataPointsForResult(testId)` reader API）已合并到 W1 round `lap-data-readers`，最新接口形态见 `openspec/changes/lap-data-readers/specs/lap-telemetry-readers/spec.md` Requirement 2。
>
> **接口形态变化（vs 本 memo 原稿）**：
> - 旧稿（§5.5 / §3 / §6 / §7）：`Repository.getDataPointsForResult(id): Flow<List<GpsDataPoint>>`
> - 新决策（lap-data-readers W1 round）：`suspend fun TestResultRepository.getDataPointsForResult(testId: String): PerformanceTelemetry?`（single-shot suspend + nullable + PerformanceTelemetry 类型，复用 `LapTelemetrySample` 而非 `GpsDataPoint`）
> - **位置变更**：方法位置从 "TelemetryRepository" 改为 "TestResultRepository"（按真相源分流，参 lap-data-readers/design.md D1 alternatives A8）
> - **合并理由**：`getDataPointsForResult(testId)` 与 `getLapTelemetry(sessionId, lapIndex)` 都是从 binary samples + entity 元数据派生 domain telemetry 切片，统一 repository 数据契约比拆 2 round 更经济
>
> 本 memo 保留作为 P2 历史背景资料（第 §1-§4 现状 + 数据证据 + 方案对比仍有参考价值），**§5/§6/§7/§9 涉及接口形态的内容已被 lap-data-readers round 工件 override**。
>
> **后续 follow-up round**：Phase 2 `wire-records-performance-real-curve`（消费 lap-data-readers 已落地的 `getDataPointsForResult` reader，把 RecordsHomeScreen.SpeedCurveStub → SpeedCurveReal）—— 见 lap-data-readers/tasks.md §10.4。

---

> **延期来源**：round `wire-real-data-to-records-and-laps-tabs`（2026-05-01 真机验证）
> **触发点**：用户在华为 8KE0219522008434 装机验证 §7.4 PERFORMANCE 端到端时，发现 Records → PERFORMANCE 子 tab 的 SpeedCurve 折线图与真实 BEST 0-100 数据不对应，仍是 mock。
> **平行会话**：`redesign-performance-result-screen` 正在重做 0-100 测试结果页（post-test 详情屏），会同样需要 dataPoints —— 两边消费同一份持久化数据。

---

## 1. 现状

`feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/RecordsHomeScreen.kt:264-415`：

- `SpeedCurveStub` Composable 渲染 0-100 加速曲线 + bubble。
- 曲线是 **解析函数硬编码**：`speed = 150f * (1f - exp(-1.4 * t * 5))`（plotW 内 60 步采样）。
- 100 km/h 交点 X = `4.21f / 5f` —— **常量 4.21s 写死**。
- bubble 文字 `"100 km/h"` + `"4.21 s"` 是字符串字面量。
- 函数命名带 "Stub" 暗示 placeholder，但视觉上看起来像真实图表，会误导用户以为它代表当前 BEST 0-100 结果。

视觉位置：Records tab → PERFORMANCE 子 tab → 第二个 panel（紧邻 BEST 0-100 / BEST BRAKE / TOTAL RUNS metric tile 行下方）。

---

## 2. 数据证据

**持久化路径调研**：

| 数据 | 存储位置 | 是否含 dataPoints |
|---|---|---|
| `TestRecordEntity` | Room `test_records` 表 | ❌ 仅 metadata（id / testTemplateId / carModel / timestamp / totalTime / totalDistance） |
| `SpeedSegmentEntity` | Room `speed_segments` 表（join testRecordId） | ❌ 仅档位区段（如 0-25 / 25-50 / 50-75 / 75-100 km/h 各档时间），4-5 个粒度点不够画曲线 |
| `TestSession.dataPoints` | **运行时内存**（domain model 字段） | ✅ 完整 (elapsedTime, speed, lat, lng, hdop, satellites, frequency, timestamp) 序列；测试结束保存到 Room 时**被丢弃** |

代码证据：
- `core/domain/src/main/java/com/blazepush/core/domain/model/TestModels.kt:104` `TestSession data class` 含 `val dataPoints: MutableList<GpsDataPoint>`
- `TestSession → TestResult` 转换路径（`CalculateResultUseCase`）只取 metadata + speed segments，不持久化 dataPoints
- 与 `LapSession` 持久化路径不同 —— LapSession 有 `BinaryTelemetryWriter` + `LapTelemetryReader` 把 GPS 样本流写到 binary 文件（`A56` round 落地），由 `TelemetrySessionEntity.binaryFilePath` 引用

**采样率**：性能测试通常 25 Hz（GPS 接收链路原生）。一次 0-100 测试约 4-6 秒，dataPoints 数 ≈ 100-150 个 GpsDataPoint。

---

## 3. 方案对比

### 方案 A：放任不管（当前状态）

- 保留 `SpeedCurveStub` 假曲线
- ❌ 误导用户、累积技术债

### 方案 B：本 round F 做诚实降级（已采纳的临时方案 —— 见 §7 协同关系）

- 把 `SpeedCurveStub` 替换成 BEST 0-100 数字大字 panel + "Detailed curve coming soon" 副标
- 不再画曲线、不再放假数字
- ✅ 不误导、不阻塞 round F 闭环
- ❌ 用户失去曲线视觉、PERFORMANCE 子 tab 信息密度下降

### 方案 C：本 memo 推荐 —— 跟 `redesign-performance-result-screen` 协同持久化

- **Phase 1（在 perf-result session 内做）**：扩展 `TestRecordEntity` schema 持久化 dataPoints
  - 选项 C1：表新增 `dataPointsBlobPath: String?` 字段，binary 文件存 GpsDataPoint 序列（仿 LapSession 路径，复用 `BinaryTelemetryWriter` 类似机制）
  - 选项 C2：新建 `test_data_points` 表（join testRecordId），逐行存 GpsDataPoint（每条 ~50 bytes，单次 100-150 行 ≈ 7KB，Room 直接吃可接受）
  - **推荐 C1**，因 binary 路径已有 `A56` round 验证、跨设备兼容、查询性能更好
- **Phase 2（Records PERFORMANCE SpeedCurve 消费）**：
  - `TestResultRepository` 暴露 `fun getDataPointsForResult(id: String): Flow<List<GpsDataPoint>>`
  - `TestSessionViewModel` 暴露 `bestAcceleration0To100DataPoints: StateFlow<List<GpsDataPoint>>`（基于 `bestAcceleration0To100` flatMapLatest 取 dataPoints）
  - `SpeedCurveStub` 改 `SpeedCurveReal`：消费 `(elapsedTime, speed)` 序列，渲染 polyline + 找 `speed >= 100` 第一个点作 100 km/h 标注

### 方案 D：自立 change 完整重做

- 不依赖 perf-result session
- ❌ 跟 perf-result 工作高度重叠（同样需要 dataPoints），重复劳动

---

## 4. 推荐方案 + 数学/性能分析

**推荐方案 C**：跟 `redesign-performance-result-screen` 协同持久化，本 round F 走方案 B 临时降级。

**性能分析**（C1 binary 路径）：

- 单次 0-100 测试 dataPoints ≈ 150 个 × 32 bytes/点 = ~5KB binary
- 100 个测试历史累积 = 500 KB —— 完全在内置存储舒适区
- Binary 读取 fail-fast 可接受（无 GpsDataPoint → SpeedCurve 显示空态）
- Compose Canvas 渲染 150 个点 polyline = 单帧 < 1ms（已经过 lap session 验证）

**数学覆盖**（100 km/h 交点）：

```kotlin
val crossing = dataPoints.firstOrNull { it.speed >= 100.0 }
val crossingTime = crossing?.elapsedTime  // 秒
```

不需要插值 —— 25 Hz 采样下精度 ±20ms，对 4-6s 的总时间足够（图表标注层级不需要更精细）。

---

## 5. 实施约束（MUST 条款）

1. **C1 binary 文件路径生成纪律**：必须复用 `A56` 已建立的 binary 路径生成约定（按 sessionId / testRecordId 散列子目录）；**禁止** ad-hoc 路径。
2. **dataPoints 序列化字段顺序固化**：byte layout 必须有 schema doc（仿 LapTelemetryReader 文档），写入侧 / 读取侧版本一致。
3. **写失败不能阻塞测试结束**：测试结果保存 transaction 完成后再异步写 dataPoints binary；binary 写失败 `dataPointsBlobPath = null`，UI fallback 空态。
4. **Room 迁移 MUST 加 migration 1→2**（目前 schema 假设是 1，需查 `AppDatabase` confirm）：新增 `dataPointsBlobPath` 字段时旧数据 `null`，UI 表现为"老结果无曲线"。
5. **跨 session 数据契约**：perf-result session 与 Records PERFORMANCE 都消费同一份 `getDataPointsForResult(id)` Flow —— 如果两边引入不同 Repository 方法签名，归并时一定冲突，必须先达成接口共识。

---

## 6. 单元测试覆盖

新 round 需要的测试集：

1. **`BinaryDataPointsWriterTest`**：写入 100 个 GpsDataPoint → 读回比对 byte-for-byte 一致（核心持久化路径）
2. **`TestResultRepository.getDataPointsForResultTest`**：
   - 测试 1：`dataPointsBlobPath = null` → emit emptyList
   - 测试 2：blob 存在 → emit 完整 List<GpsDataPoint>
   - 测试 3：blob 文件被外部删除 → emit emptyList + log warning（不抛异常）
3. **`SpeedCurveReal` Compose preview**：mock dataPoints 数据 → snapshot 比对（如果工程跑 paparazzi）
4. **手测覆盖**（不入单测）：跑真实 0-100 测试，回 PERFORMANCE 看曲线 + 100km/h 标注与 BEST 0-100 数字对得上

---

## 7. 与当前 round 的协同关系

**当前 round F (`wire-real-data-to-records-and-laps-tabs`) 的处理**：

- 走 **方案 B 诚实降级**：把 `SpeedCurveStub` 替换为简化 panel（BEST 0-100 大字 + "Detailed curve coming soon" 副标 + 无数据时 "暂无加速数据"）
- 删除 `SpeedCurveCanvas` / `SpeedCurveBubble` / 4.21 / 100 hardcoded 字面量
- 同步本 memo 文件 + tasks.md §10 backlog link

**parallel session `redesign-performance-result-screen` 协同建议**：

- 该 session 重做 0-100 post-test 结果详情页时，自然需要消费完整 dataPoints（至少要画"测试当时 GPS 速度曲线"）
- **推荐该 session 顺手把方案 C1 的 Phase 1 做了**：扩 `TestRecordEntity.dataPointsBlobPath` + binary writer + `TestResultRepository.getDataPointsForResult(id)` 接口
- Phase 2（Records PERFORMANCE SpeedCurve 接入）由 round F 之后另起一个轻 round 完成，依赖 perf-result session 已落地的接口
- **数据契约 must 共识**：perf-result session **MUST** 暴露 `Repository.getDataPointsForResult(id): Flow<List<GpsDataPoint>>` 方法签名，Records 这边不另立别名

---

## 8. 不并入当前 round 的理由

- round F 已经 5 轮 Codex review、3 个 commit、跨 data / viewmodel / UI 三层；scope 已饱和
- SpeedCurve 真实化需要 schema migration + binary writer + Room migration —— 触及 data baseline，该走独立 OpenSpec change（参考 `A56` round 的工程量）
- perf-result session 正在做的工作天然是 dataPoints 消费方，由它定接口 + Records 这边复用，避免重复设计
- 本 round F 的 spec 边界明确："接已存在数据"，没声称要扩 schema

---

## 9. 立项节奏估算

**推荐拆 2 个 round 推进**：

| Round | 工作量估计 | 依赖 | 触发节奏 |
|---|---|---|---|
| `persist-test-result-data-points`（Phase 1，由 perf-result session 拉起） | 中（schema migration + binary writer + 1 Repository 方法 + 1 Room migration test） | 无 | perf-result session 当前周期内 |
| `wire-records-performance-real-curve`（Phase 2，Records SpeedCurve 接真实数据） | 小（删 SpeedCurveStub + 新 SpeedCurveReal Composable + ViewModel 暴露 dataPoints StateFlow + UI 接线） | Phase 1 落地 + merge 主干 | Phase 1 闭环后 1 周内 |

**对应建议命名**（下次 `/opsx:ff <name>` 直接对得上）：
- Phase 1: `persist-test-result-data-points`
- Phase 2: `wire-records-performance-real-curve`
