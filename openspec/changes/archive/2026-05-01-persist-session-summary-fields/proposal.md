## Why

`add-lap-session-phase1` round 把圈速 session 完整闭环跑通后暴露三个相关的数据持久化债：

1. **detail 屏 top speed 每次进入扫整 binary** —— `LapSessionDetailScreen` 的 LaunchedEffect 调 `readPerformanceSamples(filePath)` 顺序读全文件，跨 session 切换 ~50ms / 进入；session 数变多后浪费明显
2. **`TelemetrySessionEntity.lapCount` / `bestLapMs` Room 字段未回写** —— A56 `unify-gps-telemetry-persistence` round 加了字段但 `endSession()` 没写入，长期是默认值 `0` / `null`
3. **detail 屏读 `currentSelectedTrack` 误报历史 session 赛道** —— Codex `add-lap-session-phase1` review P2.4 提出：用户切换赛道后旧 session detail 显示当前选中赛道（错），distance 也按当前赛道 `lengthKm * validLaps` 算（错）。本 V2 round 受 entity schema 不可改约束接受了 fallback；现在补上 trackId 持久化才能根因消除

三个问题都收敛到同一个动作：**`endSession()` 时把派生 summary + 当时的 trackId 一并写入 entity**。一并做避免后续每个 follow-up 单独碰 entity 与 migration。

## What Changes

- **新增** `TelemetrySessionEntity.topSpeedKmh: Double?`：session 全程最高速度（km/h），`endSession` 扫 binary 算出后写入
- **新增** `TelemetrySessionEntity.trackId: String?`：session 启动时记录的 trackId，detail 屏据此解析回 Track（多赛道删除场景下 catalog 找不到时配 trackNameSnapshot fallback）
- **新增** `TelemetrySessionEntity.trackNameSnapshot: String?`：session 启动时的 track display name 快照（如 "成都天府国际赛道"），不随 catalog rename / 删除变化。detail 屏 track name 显示优先用 snapshot，避免 catalog 失效后 fallback `currentSelectedTrack` 重引入"历史 session 显示当前赛道"的 bug（Codex add-lap-session-phase1 P2.4 review 衍生防御）
- **修改** `TelemetrySessionEntity.lapCount: Int`：`endSession` 时基于 `crossings` 派生写入。**语义明确**：`accepted=true && gateType=StartFinish` 的 crossing 相邻配对数量（=`durations.size`）；**不承诺** `LapRecord.qualityFlags` 过滤（crossing 表无 qualityFlags 字段，本 round 不引入新数据来源）。与 `LapSessionDetailScreen.deriveDetailMetrics` / `add-lap-session-phase1` round 的 detail 屏派生公式一致；与 Snackbar `finishActiveLapSession` 用的 `LapSession.completedLaps.filter { qualityFlags.isEmpty() }` 语义有差异（in-memory vs persistent），两端语义统一作为 follow-up `unify-lap-count-semantics` 单独立项
- **修改** `TelemetrySessionEntity.bestLapMs: Long?`：同 lapCount 派生（`durations.minOrNull()`）
- **Room migration**：schema version 3 → 4，3 个字段都用 `ALTER TABLE ADD COLUMN` nullable（不重建表，历史 session 不丢失）
- **AppModule**：`Room.databaseBuilder` 链路加 `.addMigrations(AppDatabase.MIGRATION_3_4)`；**移除** `.fallbackToDestructiveMigration()` 防 migration 失败时静默清库（让 migration bug 暴露而非吞掉）
- **修改** `TelemetryRepository.startSession(type)` → `startSession(type, trackId, trackNameSnapshot)`（后两个可空）：在创建 entity 时一同写入；调用方（`TestSessionViewModel.bridgeGpsToLapTiming` 内的 `activeLapSessionId == null` 分支）传 `_lapRunConfig.value?.trackId` + 解析后的 trackName（`trackCatalog.getTrack(trackId)?.name?.zh`）
- **修改** `TelemetryRepository.endSession(sessionId)`：在 close writer 之后用 `Dispatchers.IO` 切线程跑：扫 binary 算 max speed + 调 `crossingDao.queryBySessionId` 派生 lapCount/bestLapMs（基于 accepted SF crossing pairs 语义）+ 调 dao `updateSummary` 写 4 字段
- **修改** `LapSessionDetailScreen`：删 `LaunchedEffect` 内 `readPerformanceSamples` 全扫；改读 `session.topSpeedKmh`；**track name 显示优先级**：`session.trackNameSnapshot` → `session.trackId?.let { trackCatalog.getTrack(it)?.name?.zh }` → `currentSelectedTrack?.name?.zh`（仅 snapshot 与 trackId 都不可用时才 fallback；trackId 非空但 catalog 解析失败 + snapshot 也为空的场景显示 trackId 字面 + "track removed" 标签，**不**fallback `currentSelectedTrack`）；**distance** 仅在 catalog 可解析 trackId 时才显示（lengthKm 不能 snapshot），catalog 失效时 distance 显示 "—"
- **修改** `RecordsHomeScreen.LapsView` 的 SESSION HISTORY 行：渲染时用 `session.topSpeedKmh` / `session.lapCount` / `session.bestLapMs` 直读

## Capabilities

### New Capabilities

- `persisted-session-summary`: endSession 时把派生 summary（topSpeedKmh / lapCount / bestLapMs，**lapCount 语义 = accepted SF crossing pairs，不承诺 qualityFlags 过滤**）与 startSession 时的 trackId + trackNameSnapshot 持久化到 `TelemetrySessionEntity`；Room schema version 3 → 4（ADD COLUMN nullable，向下兼容历史 session；移除 fallbackToDestructiveMigration 防 migration 失败时静默清库）；UI 层（detail 屏 / Records list）直读 entity 字段，detail 屏 track name 显示按 trackNameSnapshot → trackId 解析 → currentSelectedTrack（仅 trackId/snapshot 都为 null 的历史 session）三级 fallback；topSpeed 由直接读 entity 替代每次扫 binary 派生

### Modified Capabilities

无（本 round 的契约用单一 `persisted-session-summary` New Capability 描述：entity schema + repository API + UI 数据源 + migration 兼容性都收敛到该 capability 的 Requirement 集合内；与 `unify-gps-telemetry-persistence` / `lap-session-detail-screen` 等 active rounds 的 spec 在 archive 后再做 spec 维度的整理，避免在两个未 archive 的 spec 上跨写 delta）

## Impact

**修改的代码路径**：

- `core/data/src/main/java/com/blazepush/core/data/local/entity/TelemetrySessionEntity.kt`：加 3 字段（`topSpeedKmh: Double?` / `trackId: String?` / `trackNameSnapshot: String?`）
- `core/data/src/main/java/com/blazepush/core/data/local/AppDatabase.kt`：Room schema version 3→4；companion object 加 `MIGRATION_3_4` 实现 + `internal val MIGRATION_3_4_SQL: List<String>`（暴露 SQL 字符串给 JVM unit test 断言）
- `core/data/src/main/java/com/blazepush/core/data/local/dao/TelemetrySessionDao.kt`：加 `updateSummary(...)` 方法（一次写齐 endTs/lapCount/bestLapMs/topSpeedKmh），保留 `updateEndTs`
- `core/data/src/main/java/com/blazepush/core/data/repository/TelemetryRepository.kt`：`startSession` 签名加 trackId + trackNameSnapshot；`endSession` 加 IO 调度扫 binary + 派生 lapCount/bestLapMs（accepted SF crossing pairs 语义）；entity → domain 映射加 3 字段
- `core/domain/src/main/java/com/blazepush/core/domain/model/TelemetryModels.kt`：`TelemetrySession` domain model 加 `topSpeedKmh: Double?` + `trackId: String?` + `trackNameSnapshot: String?` 字段
- **`feature/test/src/main/java/com/blazepush/feature/test/di/AppModule.kt`** (line 38-43)：`Room.databaseBuilder` 链路加 `.addMigrations(AppDatabase.MIGRATION_3_4)` + 移除全局 `.fallbackToDestructiveMigration()`（apply 阶段决定是否保留 `fallbackToDestructiveMigrationFrom(1, 2)` 兜底 pre-A56 版本）
- `feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt:bridgeGpsToLapTiming`（line ~569）：`startSession` 调用点加 trackId + trackNameSnapshot 参数（**与 round A 同函数潜在冲突**，看上面"与 follow-up backlog 其他 round 的关系"节）
- `feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/LapSessionDetailScreen.kt`：删 binary 全扫；改读 entity 字段；track name 用 when 分支三级分流（snapshot / trackId 解析 / 历史 session fallback）；distance 仅 catalog 解析成功时显示
- `feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/RecordsHomeScreen.kt`：LapsView 行内直读 entity summary 字段（lapCount / bestLapMs / topSpeedKmh）

**新增的代码路径**：

- `core/data/src/test/java/com/blazepush/core/data/repository/TelemetryRepositoryEndSessionPersistTest.kt`：用 **Fake DAO**（实现 TelemetrySessionDao / CrossingEventDao 接口）+ 真实 BinaryTelemetryWriter / PerformanceTestTelemetryReader（不依赖 Room / Context / Robolectric）覆盖 endSession 派生与持久化；≥ 5 个测试场景
- `core/data/src/test/java/com/blazepush/core/data/local/AppDatabaseMigrationSqlTest.kt`：JVM unit test 直接断言 `AppDatabase.MIGRATION_3_4_SQL` list 内含 3 条 ALTER TABLE 字符串 + 反射读 `@Database` 注解断言 version = 4（**不**调真实 migration 路径，无需 Context）

**测试形态约束**：

本 round MUST **不**引入 `androidx.room:room-testing` / `Robolectric` / `androidx.test.core` 等 Android Context 测试依赖。完整 MigrationTestHelper 自动化跑 v3→v4 schema 验证作为 follow-up `room-test-infrastructure` 单独立项（与 Robolectric / androidTest 配置一起讨论）。

**协议兼容性**：

本 round **不**触及 RaceChrono BLE 协议（公共协议字段不动）。`TelemetrySessionEntity` 是 app 内部 Room schema，Room migration 兼容历史数据库即可。

**双端改动**：

接收端 `gps-app` 改动；发射端 `simulator` 不改动。

**与 follow-up backlog 其他 round 的关系**：

- **与 `fix-lap-binary-ts-hygiene` (round A) 同函数潜在冲突**（**不是不同文件**）：A 改 `TestSessionViewModel.bridgeGpsToLapTiming` line 562 `tsDeltaMs` 公式（1 行）；本 round 改同函数内 `activeLapSessionId == null` 分支的 startSession callsite（line ~569）传 `trackId` 参数。两处 hunk 在同函数体内不同行，git rebase 可能产生 hunk 冲突需手工确认。**合回顺序**：先合回方走顺利路径；后合回方在另一方主干基础上 rebase 时 MUST 跑 `:feature:test:testDebugUnitTest` 复测确保对方 hunk 不影响本 round 语义；冲突解决后 push 顺序由 user 决定（看板 §4.1）
- **与 `wire-laptime-to-gps-filter` (round B) 同函数潜在冲突**：B 改 `bridgeGpsToLapTiming` 函数体接 GpsDataFilter；与本 round 的 startSession callsite 同函数。合回顺序：B 等 A 合回再启动（看板 §5 声明），本 round 与 B 之间合回时同样按上述 rebase 复测规则
- **与 `track-tech-v2-style-debt-cleanup` (round D) 文件交叉**：D 整体清理 `// @IgnoreFormatCheck` 文件级豁免，本 round 修改的 `LapSessionDetailScreen.kt` / `RecordsHomeScreen.kt` / `TelemetryRepository.kt` / `TelemetrySessionEntity.kt` 在 D scope 内。D 必须等本 round 合回再做（看板 §5 已声明依赖）

**Codex review 衔接**：

`add-lap-session-phase1` round Codex review P2.4 历史 detail 赛道归属问题在本 round 根因消除（trackId 持久化）。
