# Records 历史按赛道过滤 — 延期立项设计 memo

> ✅ **已消化（2026-06-06 盘点确认，无需立项）**：本 memo 描述的问题在沉淀同日（2026-05-01）
> 即被两个并行 round 修复——C round `persist-session-summary-fields` 给
> `TelemetrySessionEntity` 加 `trackId`/`trackNameSnapshot` 字段（schema v3→v4 migration），
> F round `wire-real-data-to-records-and-laps-tabs` 在 DAO 加全套 `WHERE trackId = :trackId`
> 聚合查询（`getBestLapForTrack`/`getSessionCountForTrack`/`getTotalLapCountForTrack`/
> `getRecentSessionsForTrack`）并接线 `TestSessionViewModel.kt:226` `flatMapLatest`，
> SESSION HISTORY 已跟随当前选中赛道过滤。memo 当时未回标状态导致幽灵待办。以下原文仅作历史留档。

> 触发场景：OpenSpec change `add-debug-preset-track-boyu-loop` 真机验证（2026-05-01）暴露
> 出 RecordsHomeScreen 的"session list 不按当前赛道过滤"的预先存在问题。本 memo 用于
> 下次开 round 时直接对照、起草 proposal/design，**禁止仅靠对话沉淀**。

## 1. 现状

`feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/RecordsHomeScreen.kt`
中 `LapsView` Composable 的两块视图各自取数源：

- **顶部 `CurrentTrackRecordCard`**（line 428）：`record.trackName = currentTrack?.name?.zh`
  跟随 `TestSessionViewModel.currentSelectedTrack` 切换；`bestLapTime / bestLapDate /
  sessions / totalLaps` **全是写死 mock**（line 411-419，注释 "占位 mock，不在本
  change 范围"）。
- **下方 "SESSION HISTORY"**（line 475-499）：`sessionRows = telemetryRepository
  .getRecentLapSessions(limit = 10)`，**无 track 过滤**。

效果：
- 当只有 1 条预置赛道（TFIC）时：标题永远是 TFIC，session list 也都是 TFIC 跑出来
  的，**视觉一致**（错觉没暴露）。
- 引入第 2 条预置赛道后：用户切换 currentTrack 到天投泊寓 → 顶部 panel 标题切了 →
  下方 SESSION HISTORY 还是"全部 session"（含 TFIC 历史）→ 视觉上让人以为"早上跑
  的 TFIC 历史 session 都是天投泊寓的"。

## 2. 数据证据

### 2.1 Room schema 不存 trackId

`core/data/src/main/java/com/blazepush/core/data/local/entity/TelemetrySessionEntity.kt`：

```kotlin
@Entity(tableName = "telemetry_sessions")
data class TelemetrySessionEntity(
    @PrimaryKey val sessionId: String,
    val sessionType: String,
    val startTs: Long,
    val endTs: Long,
    val binaryFilePath: String,
    val lapCount: Int = 0,
    val bestLapMs: Long? = null,
)
```

**没有 `trackId` 字段** —— 一段 session 跑完落库后，根本不知道是哪条赛道。所以即使
`getRecentLapSessions` 加 trackId 参数也无意义，因为 entity 里没这个列。

### 2.2 session 写入侧已有 lapRunConfig.trackId 但未持久化

`feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt`
内 `LapRunConfig.trackId` 已存在（line 252-257、505、524、641-644）。session 启动时
ViewModel 持有正确 trackId，但 `LapSession` → `TelemetrySessionEntity` 的映射环节
没把它写进 Room。

## 3. 方案对比

| 方案 | 改动范围 | 兼容旧数据 | 适用场景 |
|---|---|---|---|
| **(A) Room migration 加列** | TelemetrySessionEntity + Migration N→N+1 + Repository 写读 + UI 过滤 | 旧 sessions trackId = NULL，可一刀切回填为 `preset-tfic-lpcc`（之前只有 TFIC） | 推荐，最干净 |
| (B) 两段 sessionId 编码 trackId | sessionId 改成 `<trackId>:<原 id>` | 兼容性差，原有 PK 结构改大 | 不推荐 |
| (C) 旁路表 `session_track_links` | 新增 entity；JOIN 查询 | 兼容 OK，但额外 JOIN 成本 | 不推荐（无明显收益） |
| (D) 不持久化、用内存 cache | 启动后 ViewModel 跟踪当前 session→trackId | 进程重启后丢失 | 不推荐（违反"持久化"语义） |

## 4. 推荐方案 + 性能分析（方案 A）

### 4.1 Schema migration

```kotlin
@Entity(tableName = "telemetry_sessions")
data class TelemetrySessionEntity(
    @PrimaryKey val sessionId: String,
    val sessionType: String,
    val trackId: String?,    // ← 新加，nullable 兼容旧数据
    val startTs: Long,
    val endTs: Long,
    val binaryFilePath: String,
    val lapCount: Int = 0,
    val bestLapMs: Long? = null,
)
```

Migration N→N+1：
```sql
ALTER TABLE telemetry_sessions ADD COLUMN trackId TEXT;
UPDATE telemetry_sessions SET trackId = 'preset-tfic-lpcc'
WHERE trackId IS NULL;  -- 历史回填：开发期只跑过 TFIC
```

**注意**：开发期回填策略必须只在 dev/internal builds 启用；如果项目已发布给外部
用户，回填规则要按"无法可靠判断的 session 一律 NULL"保守处理。

### 4.2 Repository 接口

```kotlin
suspend fun getRecentLapSessions(
    trackId: String?,            // null = 不过滤，向后兼容
    limit: Int,
): List<TelemetrySession>
```

DAO 层 `@Query` 加 `WHERE (:trackId IS NULL OR trackId = :trackId)`，零 JOIN。

### 4.3 UI 过滤

`RecordsHomeScreen.LapsView`：
```kotlin
LaunchedEffect(currentTrack?.id) {
    sessionRows = telemetryRepository.getRecentLapSessions(
        trackId = currentTrack?.id,
        limit = 10,
    )
}
```

`remember(currentTrack)` 已经只在切换时重新计算 mock record；`LaunchedEffect`
key 加 `currentTrack?.id` 后切换赛道立即重查 sessions。

### 4.4 性能

- DAO 查询：单列 INDEX 即可；当前 session 总量 ~百级，过滤代价 < 1ms。
- UI 重组：`currentTrack` 切换约 1 次/秒级别（用户操作），重查无可见延迟。

## 5. 实施约束（MUST）

1. Migration MUST 同时落 `Migration` 实例与 schema export（项目用了 schema/ 目录，需 dump）。
2. 写入侧 MUST 把 `LapRunConfig.trackId` 透传到 `TelemetrySessionEntity.trackId`，
   起源在 `TestSessionViewModel.createLapSession` / `createTelemetrySession` 路径。
3. 读取侧 MUST 在 Repository 层接口签名上加 `trackId: String?` 参数（不 overload，
   nullable 默认）。
4. UI 侧 MUST 在 `RecordsHomeScreen.LapsView` 顶部 panel 旁可视化"按赛道过滤"
   状态（即使是隐式的，至少 panel 标题与下方 list 应在同一时间窗内）。
5. 顶部 `CurrentTrackRecordCard` 的 `bestLapTime / sessions / totalLaps` mock 数字
   MUST 改为按当前赛道真实查询（否则即使 session list 切对了，顶部 best lap 还是
   "1:32.457"假数据，依然误导）。

## 6. 单元测试覆盖（路径级断言）

- `TelemetrySessionEntityTest`：trackId 字段存在 + 默认 nullable
- `TelemetryRepositoryTest`：
  - `getRecentLapSessions(trackId=null, ...)` 返回所有
  - `getRecentLapSessions(trackId="preset-tfic-lpcc", ...)` 只返回 TFIC
  - `getRecentLapSessions(trackId="preset-boyu-loop", ...)` 只返回 boyu loop
  - `getRecentLapSessions(trackId="missing-track", ...)` 返回 emptyList
- `MigrationN_to_N+1_AddsTrackIdColumn`：Room migration 测试
- UI 侧（如有 Robolectric 或 Compose UI test）：currentTrack 切换 →
  sessionRows 变化

## 7. 与当前 round 的协同关系

`add-debug-preset-track-boyu-loop` round（本 round）的范围是"接入第 2 条预置赛道"，
**已经完成**：
- 互斥变体源集机制 ✓
- referencePath / 5 gate 离线生成 ✓
- variant 拆分单测 ✓
- release 包零 debug 数据泄漏 ✓

本 memo 描述的"records 按赛道过滤"是 enhance-track-presentation round 留下的
mock 占位 + Room schema 缺 trackId 的 pre-existing 问题，引入第 2 条赛道后才
"显形"。逻辑独立、改动面比"接入新预置赛道"大一个数量级（涉及 Room migration），
属于 baseline 改造。

## 8. 不并入当前 round 的理由

1. **改动面跨模块**：core/data Room migration + feature/test ViewModel + UI 改造，
   而本 round 范围严格收敛在 feature/test repository 层 + 变体源集机制。
2. **实施风险**：Room migration 需要单独验证（含旧数据库回填路径），跟"接入预置
   赛道"语义正交，强行打包会让本 round commit message + review 焦点失焦。
3. **现有 enhance-track-presentation 的注释明确说**这是"占位 mock，不在本 change
   范围"——尊重前序 round 的边界声明，这是属于"records 数据化"baseline 工作的
   一部分。

## 9. 立项节奏估算

- proposal + design + specs + tasks 工件：~2-3 小时（含读现有 TelemetryRepository
  / DAO / TestSessionViewModel.createLapSession 路径）
- 实施：~4-6 小时（Migration + Entity + DAO + Repository + UI + 测试）
- Codex review：1 轮 + 1 轮消化
- 真机验证：~30 min
- **建议下次 round 名**：`wire-records-by-track`（kebab-case，与 enhance-track-
  presentation / add-debug-preset-track-boyu-loop 风格一致）

直接 `/opsx:ff wire-records-by-track` 起草工件，引用本 memo 即可起步。
