## Context

Records tab + Laps tab 已经有完整 V2 视觉重构（`enhance-track-presentation` 落地真实赛道呈现，`replace-nearby-tracks-with-recent-strip` 落地 RECENT 横滑），但**所有 metric / 列表数据都是 hardcode mock**。当前数据基础已就绪：

- `TestResultRepository.saveResult` 在每次加减速测试 `Completed` 后被调用（`CalculateResultUseCase` 计算结果 + 持久化），Room 表 `test_records` 累积全部 result 历史。已有 `getRecentResults(limit)` / `getSegments(testId)` / `deleteResult(...)` 方法
- `TelemetryRepository.endSession` 在每次圈速 session HOLD TO END 后被调用，Room 表 `telemetry_sessions` v4 schema 已含 `topSpeedKmh` / `lapCount` / `bestLapMs` / `trackId` / `trackNameSnapshot` 字段（C round 持久化），已有 `getRecentLapSessions(limit)` 等查询

缺的只是"把 6 处 mock UI 接到 Repository 的 Flow"。本 round 是纯**数据接线 + Repository 扩展**改动，不动 schema、不动 UI 视觉、不动 Track 数据契约。

## Goals / Non-Goals

**Goals**：

- G1：Repository 层加聚合查询方法（best by template / total count / by trackId 过滤），返回 `Flow` 自动响应数据写入
- G2：ViewModel 暴露 8 个 StateFlow，跟随 `currentSelectedTrack` 切换自动 flatMapLatest 到新 trackId 的 query
- G3：UI 层 6 处 mock 全部消费 ViewModel StateFlow，hardcode 字符串 / placeholder val 全部清掉
- G4：新建 `MetricFormatter` 工具函数集中 lap ms / date / run timestamp 的格式化逻辑，避免散落
- G5：Records tab PERFORMANCE / LAPS 两段统计跨 session 自动累加更新；Laps tab RECENT BEST 跟随 currentSelectedTrack 自动切

**Non-Goals**：

- NG1：不接 SpeedCurveStub 真实速度曲线（产品决策待定，开新 round）
- NG2：不接赛道方向 direction（Track 数据契约扩展，未来 round）
- NG3：不做 Records tab 维度筛选（按车型 / 日期 / 赛道筛选属于 Records filter round）
- NG4：不动 Room schema（消费 C round 已加 v4 字段）
- NG5：不改 Repository 已有方法签名，仅追加新方法
- NG6：不接性能测试结果详情页（用户另一 session 的 redesign-performance-result-screen 范围）

## Decisions

### D1：Repository 查询返回 `Flow<T>` 而非 `suspend fun T`

**选择**：所有新增聚合查询返回 `Flow`。

**备选**：`suspend fun getBestResult(): TestResult?` —— 拉取一次值。

**理由**：

- UI 上 best lap / total count 等数据在新 result/session 写入后**自动刷新**（不用手动 invalidate）—— Compose `collectAsState` 直接消费 StateFlow，写入侧 `@Insert` 触发 Room Flow 推送，无需手动协调
- Room 已支持 `@Query ... fun ...: Flow<...>`，不需要额外 wrapping
- `suspend fun` 一次性查询适合命令式（如保存按钮按下后查），不适合"持续显示统计"

### D2：聚合查询位置：Room DAO `@Query` vs Repository 内 Flow.map

**选择**：能在 SQL 层算的（COUNT、MIN by testTemplateId）走 DAO `@Query`；需要跨实体组合的（`min bestLapMs where sessionType='LAP_SESSION' + endTs > startTs + trackId = :trackId`）也走 DAO `@Query`；Flow.map 只用于域模型转换（Entity → Domain / Summary）。

**备选**：全部用 `getAllResults().map { it.minByOrNull { ... } }` —— 拉全表内存计算。

**理由**：

- DAO `@Query MIN/COUNT` 走 SQL 索引（trackId / templateId），数据量大时 O(log n)；Flow.map 全表 O(n)
- 当前数据量小（test_records < 100 条 / telemetry_sessions < 50 条），SQL 优势不显著，但 Repository 是长期基础设施，按 SQL 优先 future-proof
- DAO 加 `@Query` 1-2 行成本低

### D3：ViewModel 的"按 currentTrack 切换"用 `flatMapLatest`

**选择**：

```kotlin
val bestLapForCurrentTrack: StateFlow<TelemetrySession?> =
    _currentSelectedTrack
        .filterNotNull()
        .flatMapLatest { track -> telemetryRepository.getBestLapForTrack(track.id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
```

**备选**：

- (a) `combine(_currentSelectedTrack, _allBestLaps)` —— 拉全部赛道 best lap 然后 filter，浪费查询
- (b) UI 端做 collect + 切换 —— 状态管理散落到 UI

**理由**：`flatMapLatest` 自动 cancel 旧 trackId 的 Flow + 订阅新 trackId 的 Flow，是 currentSelectedTrack 切换的标准模式。`SharingStarted.WhileSubscribed(5000)` 让 UI 离开屏幕 5s 内 Flow 仍订阅、避免快速重订阅震荡。

### D4：MetricFormatter 单点真理

**选择**：新建 `feature/test/.../ui/tracktech/format/MetricFormatter.kt`，提供 3 个纯函数：

```kotlin
fun formatLapMs(ms: Long): String                  // "1:32.457"
fun formatDate(epochMs: Long): String              // "May 18, 2024"
fun formatRunTimestamp(epochMs: Long, now: Long = System.currentTimeMillis()): String
                                                   // "Today, 10:35" 或 "May 18, 2024"（按距今时长）
```

**备选**：散落到各 Composable 内部就地实现。

**理由**：

- 6 处 UI 消费会重复用这些格式（lap ms 在 Laps tab RECENT BEST + Records LAPS BEST LAP + SESSION HISTORY 都用；date 在 best lap status + session row 都用）
- 集中后单元测试一次（`MetricFormatterTest`），避免格式漂移（如某处 "1:32.457" 另一处 "1:32:457"）

### D5：空数据 fallback 显示 "--"

**选择**：所有 metric value 在 null（首次启动 / 该赛道无 session 历史 / 该 template 无 result）时显示 `"--"`，与 `enhance-track-presentation` 中"NO TRACK SELECTED" / "NO PREVIEW" 等 fallback 风格一致。

**备选**：

- (a) 隐藏整个 metric tile / 区块 —— 视觉空旷
- (b) 显示 "暂无成绩" / "首次跑前为空" 等长文本 —— 占位文案过长，挤压数字位置

**理由**："--" 是赛车仪表 / 圈速仪标准的 "无数据" 视觉，简洁且不抢焦点。

### D6：与 round A 同文件协同

**情况**：本 round F 与 A 共改 `TelemetryRepository.kt` + `TestSessionViewModel.kt`：

- A：改 `TelemetryRepository.startSession/endSession` 加 `activeSessionStartTs` property + `TestSessionViewModel.bridgeGpsToLapTiming` 改 1 行 anchor 公式
- F：加 `TelemetryRepository.getBestLapForTrack/getSessionCountForTrack/...` 新查询方法 + `TestSessionViewModel` 加顶层 8 个 StateFlow + `init` block 加订阅

**Mitigation**：

- 函数级不重叠（A 在既有 lifecycle 函数内、F 加新函数）
- import 区可能微冲突（A 大概率不加新 import；F 加 Flow / flatMapLatest 等 import）
- Rebase 顺序：谁先合回另一个 rebase；按看板 §3 自动 merge 处理 import 区
- 看板 §6 共享文件登记 ongoing，闭环时改 done

## Risks / Trade-offs

- **R1：DAO @Query 写错 SQL 不被编译器抓业务逻辑层**：Room 的 `@Query` 编译期 verify SQL 语法 + 列名（catch 列名拼写错），但**不验证**业务过滤条件正确性（如 `MIN(bestLapMs) WHERE sessionType = 'LAP_SESSION' AND endTs > startTs AND trackId = :trackId` 写错条件、漏 in-progress 排除等） → Mitigation：单元测试 `TelemetryRepositoryTrackQueryTest` 覆盖 trackId 过滤 + 排除其他 trackId + 排除 in-progress (`endTs == startTs`) + 排除 `bestLapMs = null`
- **R2：Flow `WhileSubscribed(5000)` 可能在 tab 快速切换时震荡**：5s 缓冲对常规使用够；快速切的边界场景重新触发 query → Mitigation：5s 是经验值，未来发现震荡再调
- **R3：与 round A 同文件 rebase 冲突**：函数级不重叠风险低，但 import 区可能撞 → Mitigation：合回顺序看 progress（A 范围小、估计先合，F rebase 跟上自动 merge）
- **R4：bestLap 查询包含异常 session（lapCount = 0 / bestLapMs = null）**：C round 写入侧 endSession 在 lapCount > 0 时才填 bestLapMs，但 schema 允许 null → Mitigation：DAO `@Query` 加 `WHERE bestLapMs IS NOT NULL`
- **R5：date / time 格式与本地化**：`MetricFormatter.formatDate` 用 `SimpleDateFormat("MMM d, yyyy", Locale.ENGLISH)` 输出 "May 18, 2024"；如果将来要本地化（中文显示"5月18日"）改 Locale 即可，本 round 不做
- **R6：RECENT RUNS 列表的 isPB 判定**：当前 mock 用 `RecentRun.isPB` 字段；接真实数据时需要在 ViewModel 层 derive `recentRuns.map { it.copy(isPB = it.totalTime == bestAcceleration0To100.value?.totalTime) }` 或类似 → Mitigation：design 暂记，实施时按需要调

## Migration Plan

无运行时 migration：

- Repository 仅追加新方法，旧 API 不动
- ViewModel 仅追加新 StateFlow，旧 API 不动
- UI 删除 placeholder mock val 是 source 改动，无运行时影响

部署即生效：用户首次启动新 APK 看到 "--"（无历史数据）；跑测试 / lap session 后自动累加 metric 到真实值。

回滚：直接回滚 commit；UI 显示回到 mock 字符串（向后兼容）。

## Open Questions

- Q1：RECENT RUNS isPB 标志的判定逻辑要不要持久化（`TestRecord.isPB: Boolean`）还是查询时 derive？倾向**查询时 derive**（mock 现在也是 derive），避免 schema 改动
- Q2：bestLap 查询是否需要排除 invalid lap（C round 加 `LapQualityFlag` 字段判断 lap 完整性）？倾向 **WHERE bestLapMs IS NOT NULL** 兜底（C round 写入侧已 filter invalid），不再加 quality flag 过滤；如果 invalid lap 漏写 bestLapMs=null 就自动排除
- Q3：sessionCount 是否包含未完成的 session？已确定 **MUST 排除 in-progress**（startSession 写入 `endTs = startTs` 占位，endSession 才写真实 endTs）。所有查询 MUST 用 `WHERE endTs > startTs` 排除 in-progress；**不能**用 `WHERE endTs IS NOT NULL`（endTs 字段非空、无法用 null 判定）
