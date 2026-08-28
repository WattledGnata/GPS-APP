## Context

`add-lap-session-phase1` round 跑通圈速完整闭环后，三个相关债收敛到同一个动作：endSession 时把派生 summary + startSession 时的 trackId 一并写入 `TelemetrySessionEntity`。

**当前 baseline（commit `6070ef5`）**：

- `core/data/.../local/AppDatabase.kt`：Room schema version = 3，`@Database(entities = [TelemetrySessionEntity::class, CrossingEventEntity::class], ...)`
- `TelemetrySessionEntity`：现有字段 `sessionId / sessionType / startTs / endTs / binaryFilePath / lapCount: Int = 0 / bestLapMs: Long? = null` —— 后两个字段从 A56 引入但**从未被回写**
- **`Room.databaseBuilder` 调用点**：在 `feature/test/src/main/java/com/blazepush/feature/test/di/AppModule.kt:38-43`（**不**在 `core/data/AppDatabase.kt` 内），且当前**挂着 `.fallbackToDestructiveMigration()`** —— 任何 schema 改动若 migration 不到位，会被静默清库
- `TelemetryRepository.startSession(type: TelemetrySessionType): String` —— 现签名不含 trackId
- `TelemetryRepository.endSession(sessionId)` —— 仅 close writer + 更新 endTs，不写 lapCount/bestLapMs/topSpeedKmh
- `LapSessionDetailScreen.LaunchedEffect`：每次进入用 `readPerformanceSamples(filePath)` 全扫 binary 派生 topSpeed（`add-lap-session-phase1` quick fix，等本 round 替换）
- `LapSessionDetailScreen` 的 Track / Distance：来自 `currentSelectedTrack`（Codex P2.4 review 提出的多赛道误读 bug）
- **`core/data/build.gradle.kts` 当前依赖**：`androidx.room.runtime` / `room.ktx` / `room.compiler` —— **不含 `androidx.room:room-testing`**；现有 core/data 测试用 fake DAO 避 Room setup（无 Robolectric / androidTest 环境）

**startSession 调用点 2 处**（grep 已确认）：

- `TestSessionViewModel:480` `PERFORMANCE_TEST`（加减速测试，无赛道概念）
- `TestSessionViewModel:569` `LAP_SESSION`（圈速，需传当前 trackId + trackNameSnapshot）

## Goals / Non-Goals

**Goals:**

- `TelemetrySessionEntity` 加 3 个字段：`topSpeedKmh: Double?` + `trackId: String?` + `trackNameSnapshot: String?`
- Room schema version 3 → 4，`MIGRATION_3_4` 用 `ALTER TABLE ADD COLUMN` 兼容历史数据库（旧 row 字段为 null）
- **`MIGRATION_3_4` 注册位置**：`feature/test/.../di/AppModule.kt:38` 的 `Room.databaseBuilder` 链路调 `.addMigrations(AppDatabase.MIGRATION_3_4)`，并**移除** `.fallbackToDestructiveMigration()`
- `startSession(type, trackId, trackNameSnapshot)` 签名加 2 个 nullable 参数（PERFORMANCE_TEST callsite 走默认 null 不用改）
- `endSession(sessionId)` 在 close writer 之后用 `Dispatchers.IO` 扫 binary 算 max speed + crossings 派生 lapCount/bestLapMs（**accepted SF crossing pairs 语义，不承诺 qualityFlags 过滤**）+ 写回 entity
- `LapSessionDetailScreen` 删 binary 全扫，直读 entity 字段；track name 显示三级 fallback（`trackNameSnapshot` → `trackId` 解析 → `currentSelectedTrack` 仅历史空 trackId session）；distance 仅 catalog 解析成功时显示
- 单元测试覆盖：endSession 写入字段验证 + Migration SQL 字符串语义 grep 自检（不引入 room-testing 依赖；MigrationTestHelper 验证作为 follow-up androidTest）

**Non-Goals:**

- 不重新设计 binary 文件格式（仍用 A56 落地的 17-byte/sample）
- 不改 `LapTimingEngine` 任何派生逻辑（lapCount 派生公式与 detail 屏现状一致）
- 不接 `GpsDataFilter`（与 round B `wire-laptime-to-gps-filter` 正交）
- 不修 `bridgeGpsToLapTiming` 的 tsDeltaMs 时间轴混合 bug（与 round A `fix-lap-binary-ts-hygiene` 正交）
- 不改 `RaceChrono BLE 协议`（公共协议不可变）
- 不引入 Foreground Service（一期接受 endSession 期间 app 在前台）

## Decisions

### D1：trackId + trackNameSnapshot 在 startSession 写入，不在 endSession 写

**决定**：`startSession(type: TelemetrySessionType, trackId: String? = null, trackNameSnapshot: String? = null)` 签名扩展，session 创建时把两个 nullable 参数一同写入 entity。

**理由**：
- session 启动时 trackId 与 trackName **同时**已知（圈速场景：trackId 由 `_lapRunConfig.value.trackId` 提供，trackName 由 `trackCatalog.getTrack(trackId)?.name?.zh` 解析得到）；延后到 endSession 才写需要 ViewModel 在 active 期间持有这两个状态，与 sessionId 各自一份增加同步成本
- session 中途异常结束（force kill / abnormal cleanup）时 entity row 已含两字段，detail 屏的 trackNameSnapshot 优先 fallback 仍能正确显示
- trackNameSnapshot **必须** startSession 时写入，**不能**留到 endSession：detail 屏 D5 用 when 分支严格分流时，"trackId 非空 + catalog 失败 + snapshot 空"分支会显示 `—`；如果 snapshot 漏写，多赛道扩展后赛道被删 → 用户看到的是 `—` 而不是 session 当时的赛道名（功能退化）
- 默认值 `null` 兼容 PERFORMANCE_TEST callsite（加减速测试无赛道概念），不改 callsite 调用签名

**替代方案考虑**：
- endSession 时一并写两字段（reject：active 期间 ViewModel 多持一份状态；trackNameSnapshot 漏写风险高）
- 仅写 trackId 不写 trackNameSnapshot（reject：catalog 删除赛道后 detail 屏 D5 fallback 会显示 `—`，违背 trackNameSnapshot 设计意图）

### D2：endSession 扫 binary 算 topSpeedKmh 走 Dispatchers.IO

**决定**：`endSession(sessionId)` 在 close writer 之后调 `readPerformanceSamples(filePath)` 派生 max speed，整个扫盘 + 派生用 `withContext(Dispatchers.IO)` 切线程。

**理由**：
- 一圈 binary ~42KB，多圈 session 120-300KB，顺序扫 + maxOf ~30-50ms（之前 `LapSessionDetailScreen` 实测）
- endSession 已是 suspend，但 callsite（`finishActiveLapSession`）走 viewModelScope，不切线程会占用 main 线程
- IO 调度让 endSession 总耗时仍 < 100ms（writer.close + 扫 binary + DAO 写），用户感知不到

**替代方案考虑**：
- 不持久化 topSpeedKmh，detail 屏继续每次扫（reject：本 round 主目标之一就是消除每次扫）
- 用 `BinaryTelemetryWriter` 在 write 阶段实时维护 max speed（reject：跨 close 边界要序列化到 header，改 binary 格式破坏 A56 兼容性）

### D3：lapCount / bestLapMs 派生基于 crossings，不基于 in-memory completedLaps

**决定**：`endSession` 调 `crossingDao.queryBySessionId(sessionId)` 取 crossings list，基于 `gateType == "StartFinish" && accepted == true` 配对相邻差派生 lapCount / bestLapMs，写入 entity。

**理由**：
- crossings 已持久化到 Room（A56 落地），endSession 时可靠可查；in-memory `LapSession.completedLaps` 在 `finishActiveLapSession` 内可能已被清状态后才调 endSession
- detail 屏的 `deriveDetailMetrics` 已用同样的 crossings 派生路径（`LapSessionDetailScreen.kt:464+`），保持派生公式与 detail 屏一致 → 持久化的 lapCount/bestLapMs 与 detail 屏显示数值一致
- crossings 派生不依赖 ViewModel 状态生命周期，跨进程重启后仍正确

**替代方案考虑**：在 `finishActiveLapSession` 内传 LapSessionSaveResult 给 endSession 直接写（reject：循环依赖；endSession 是 repository 职责，不应消费 ViewModel summary 类型）。

### D4：Migration 3 → 4 用 ALTER TABLE ADD COLUMN，**MIGRATION_3_4 注册位置在 AppModule**

**决定**：

`MIGRATION_3_4` 定义在 `core/data/.../local/AppDatabase.kt` 的 companion object（与 entity / database 同模块就近放置）：

```kotlin
@Database(
    entities = [TelemetrySessionEntity::class, CrossingEventEntity::class],
    version = 4,
    ...
)
abstract class AppDatabase : RoomDatabase() {
    ...
    companion object {
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE telemetry_sessions ADD COLUMN topSpeedKmh REAL")
                db.execSQL("ALTER TABLE telemetry_sessions ADD COLUMN trackId TEXT")
                db.execSQL("ALTER TABLE telemetry_sessions ADD COLUMN trackNameSnapshot TEXT")
            }
        }
    }
}
```

**MIGRATION_3_4 注册**：`feature/test/.../di/AppModule.kt:38-43` 的 `Room.databaseBuilder` 链路修改为：

```kotlin
single<AppDatabase> {
    Room.databaseBuilder(
        get<Context>().applicationContext,
        AppDatabase::class.java,
        "gps_app.db",
    )
        .addMigrations(AppDatabase.MIGRATION_3_4)   // 新增
        // .fallbackToDestructiveMigration()         // 移除
        .build()
}
```

**理由**：
- 三字段都允许 NULL，ADD COLUMN 默认 NULL 满足兼容性
- 不破坏历史数据（旧 row 字段为 null，detail 屏走 fallback 链）
- ALTER TABLE 原生操作 < 10ms
- **MIGRATION_3_4 注册位置必须在 AppModule 而不是 AppDatabase 自身**：Room 实际构造在 AppModule 的 `databaseBuilder()` 链，仅在 AppDatabase 定义 migration 不会让生产 DB 使用它（Codex review P1.1 提出）
- **移除 `fallbackToDestructiveMigration()`**：该方法会让 migration 失败时静默清空全部 session 数据（用户上一段录制的圈速 / 加减速测试全部丢失）。本 round 移除后 migration 失败 = app crash / 数据库无法打开，让 bug 显式暴露而非沉默吞掉。**单元测试 + 真机升级验证**双保险确保 MIGRATION_3_4 正确性

**替代方案考虑**：
- destructive migration 兜底（reject：清空历史用户数据不可接受）
- migration 注册放 AppDatabase 内部（reject：Room 构造在 AppModule，AppDatabase 内部注册无效）

### D5：detail 屏 track name 显示用 when 分支，不用 elvis 链

**决定**：用 when 分支严格分流，**禁止** trackId 非空场景 fallback `currentSelectedTrack`：

```kotlin
val displayTrackName = remember(session, currentTrack) {
    val s = session
    when {
        // 优先级 1：snapshot 非空（本 round 后所有新 session 都会有）
        !s?.trackNameSnapshot.isNullOrBlank() -> s.trackNameSnapshot
        // 优先级 2：snapshot 空 + trackId 非空（防御性场景；正常路径不会发生因为本 round 两字段同时写）
        s?.trackId != null -> {
            // catalog 解析成功 → 显示 catalog 当时的 name
            // catalog 解析失败（多赛道扩展后赛道被删 / rename）→ 显示 "—"
            // **不**fallback currentSelectedTrack（Codex P1.2 review 防误读）
            trackCatalog.getTrack(s.trackId)?.name?.zh ?: "—"
        }
        // 优先级 3：snapshot 与 trackId 都为 null（历史 session，本 round 落地前的）
        else -> currentTrack?.name?.zh ?: "—"
    }
}

// distance 单独处理：仅 catalog 解析成功时显示，不依赖 currentSelectedTrack 的 lengthKm
val distanceKm = remember(session, derived) {
    val resolvedTrack = session?.trackId?.let { trackCatalog.getTrack(it) }
    resolvedTrack?.lengthKm?.let { it * derived.validLaps }?.takeIf { it > 0.0 }
}
```

**fallback 三个分支（互斥，不再用 elvis 链）**：

| 输入条件 | 输出 |
|---|---|
| `trackNameSnapshot` 非空 | snapshot 字面（不查 catalog） |
| `trackNameSnapshot` 空 + `trackId` 非空 + catalog 解析成功 | catalog name |
| `trackNameSnapshot` 空 + `trackId` 非空 + **catalog 解析失败** | **`"—"`**（不 fallback currentTrack） |
| `trackNameSnapshot` 空 + `trackId` 空（历史 session） | currentTrack name 或 "—" |

**理由**：
- elvis 链 `snapshot ?: catalog ?: currentTrack` 在 trackId 非空但 catalog 失败时仍会落到 currentTrack，等价于"历史 session 显示当前赛道"bug（Codex P1.2 review 提出）
- when 分支强制把"trackId 非空"和"trackId 空"分开处理：trackId 非空 = session 当时确实选过赛道，catalog 解析失败时显示 "—"（用户能看出是赛道信息缺失，不会被误导成当前赛道）；trackId 空 = 历史 session，fallback currentTrack 是 best-effort
- distance 仍按 catalog 解析（lengthKm 不能 snapshot），catalog 失败时 distance 显示 "—"

**替代方案考虑**：
- 持久化 trackLengthKm 到 entity（reject：增加 schema 复杂度；distance 显示 "—" 已可接受）
- catalog 失败时显示 trackId 字面（reject：trackId 是 internal id 如 `preset-tfic-lpcc`，对用户无意义）
- catalog 失败时显示 "Unknown track" 字符串（accept 作为降级方案；本 round 用 "—" 跟 distance / topSpeed 缺失时显示一致）

### D7：单元测试不引入 room-testing 依赖，用 SQL 字符串 grep 自检 + 真机升级验证

**决定**：本 round **不**引入 `androidx.room:room-testing` / `Robolectric` / `androidx.test.core.ApplicationProvider` 等 Android Context 依赖（Codex 二轮 review P1.1 / P1.2 提出：`Room.inMemoryDatabaseBuilder` 仍需要 Android Context，不能在 core/data 现有 JVM testDebugUnitTest 环境直接跑）。

测试形态：

1. **endSession 写入测试用 Fake DAO + 真实 BinaryTelemetryWriter**：
   - `class FakeTelemetrySessionDao : TelemetrySessionDao` 实现接口，记录 `insert(entity)` / `updateSummary(...)` 调用供测试断言
   - `class FakeCrossingEventDao : CrossingEventDao` 同上
   - `BinaryTelemetryWriter` + `PerformanceTestTelemetryReader` **真实跑**（这两个类不依赖 Room / Context；用 `kotlin.io.path.createTempFile()` 创建 binary 文件路径）
   - 这种形态与 baseline core/data 测试一致（都用 fake DAO 避 Room setup）
2. **MIGRATION_3_4 SQL 字符串单元测试**：
   - `AppDatabase.companion object` 暴露 `internal val MIGRATION_3_4_SQL: List<String>` 含 3 条 SQL；`migrate()` 实现 `MIGRATION_3_4_SQL.forEach { db.execSQL(it) }`
   - 测试 `@Test` 直接读 `AppDatabase.MIGRATION_3_4_SQL` 断言含 3 条 ALTER TABLE 字符串 + 用反射读 `@Database` 注解断言 `version == 4`
   - **不**调真实 `MIGRATION_3_4.migrate(db)` 路径（避免 SupportSQLiteDatabase / Context 依赖）
3. **真机升级验证**：tasks §6.6 用旧版本（commit `6070ef5`）跑过 ≥ 1 个 session（schema v3）→ 升级到本 round build → 验证 app 启动 + 历史 session 仍可读

**理由**：
- `Room.inMemoryDatabaseBuilder` 是 Room runtime API 但**仍需 Android Context**（Codex 二轮 P1.1 提出）；core/data JVM unit test 无 Context，不能直接调
- Fake DAO 形态与 baseline core/data 测试（如 `TelemetryRepositoryTest`）一致，apply 阶段不需要切换 testing infrastructure
- MIGRATION_3_4 真实路径靠真机升级 manual gate 兜底；自动化 MigrationTestHelper 测试作为 follow-up `room-test-infrastructure` 与 Robolectric / androidTest 配置一起讨论
- SQL 字符串自检低成本（直接断言 list），可避免 typo（如把 `topSpeedKmh REAL` 写成 `topSpeed REAL`）

**替代方案考虑**：
- 强制本 round 引入 room-testing + Robolectric（reject：core/data 测试形态全面改造，超本 round scope；与现有 fake DAO 测试不一致）
- 用 `Room.inMemoryDatabaseBuilder` 在 JVM test 跑（reject：仍需 Context，会编译过但运行时挂 — Codex 二轮 P1.1 提出过）
- 把 migration test 改 androidTest（reject：跑慢、需要真机/模拟器；先用 SQL 字符串自检 + manual 真机升级验证够用）
- 完全跳过 migration 测试（reject：SQL 字符串自检是低成本兜底，避免 typo）

### D6：DAO 加单一 update 方法 vs 多个细粒度 update

**决定**：`TelemetrySessionDao` 加单一 `updateSummary(sessionId, endTs, lapCount, bestLapMs, topSpeedKmh)` 方法替代当前 `updateEndTs`（保留 `updateEndTs` 给历史 callsite，不动）。

新方法 endSession 调一次写齐 4 字段；trackId 在 startSession 时已写，update 不动 trackId。

**理由**：
- 一次 SQL update 比 4 次细粒度 update 性能更好（~5ms vs 20ms）
- 4 字段都在 endSession 同时知，没有部分更新场景
- 未来需要单独修 lapCount/bestLapMs（如 follow-up 修复历史 session 数据）可再加细粒度 update

**替代方案考虑**：4 个独立 update 方法（reject：性能差，无业务场景需要独立 update）。

## Risks / Trade-offs

[**MIGRATION_3_4 实现错误导致 v3→v4 真实失败**] → Mitigation：MIGRATION_3_4 用 ADD COLUMN nullable 是 SQLite 最稳定操作；移除 `fallbackToDestructiveMigration()` 后失败 = app crash 显式暴露而非静默清库；单元测试 SQL 字符串自检（断言 MIGRATION_3_4 source 含 3 个 `ALTER TABLE ADD COLUMN`）+ 真机升级验证（用旧版本跑过 session 后升级到本 round build 验证）双保险

[**移除 destructive fallback 破坏 v1/v2 旧数据库升级路径**] → 关键风险（Codex P1.3 review 提出）。**当前最低支持版本**：本 round 假设生产真机最低 schema version 是 **v3**（`unify-gps-telemetry-persistence` round / A56 落地的版本，引入 telemetry_sessions / crossing_events 表）。如果真机存在 v1 / v2 旧数据库且无 1→2、2→3 完整 migration 链，本 round 移除 fallback 后升级到 v4 会数据库无法打开。

**Mitigation**（按风险递减）：
1. **真机升级验证强 manual gate**（tasks §6.6）：在已知 v3 真机上验证 v3→v4；同时**主动检测**真机当前 schema version（用 `adb shell sqlite3` 查 PRAGMA user_version 或 app 启动 try-catch logcat），如发现 v1/v2 真机存在，立即扩展本 round scope 加 1→2、2→3 migration（暂停 push）
2. **保留 `fallbackToDestructiveMigrationFrom(1, 2)` 兜底 v1/v2**（不全局 fallback，仅对 v1/v2 destructive，保护 v3/v4 严格 migration）—— 本 round **MAY** 加这条窄 fallback，明确写在 AppModule 注释里"v1/v2 是 pre-A56 版本，已无生产用户"
3. **仅当 manual gate 验证生产真机全部 v3 时**才完全移除 fallback；否则保留 fallbackToDestructiveMigrationFrom(1, 2) 直到下一 round 加上完整 migration 链
4. **回滚策略**：本 round commit 落地后真机首次启动 crash → user 报告 → 立即 hotfix round 加回 destructive fallback 或补 1→2/2→3 migration

**当前决定**：本 round MUST 移除全局 `fallbackToDestructiveMigration()`；MAY 加 `fallbackToDestructiveMigrationFrom(1, 2)`（apply 阶段决定，看 grep AppModule 历史 commit 确认 v1/v2 是否实际存在过）。**MUST** 在 §6.6 真机验证项明确"用 v3 真机升级到 v4 + 用 v1/v2 真机升级（如能找到）"

[**endSession 扫 binary 阻塞 finishActiveLapSession**] → Mitigation：`withContext(Dispatchers.IO)` 切线程；total 时间 ~100ms 用户在 HOLD TO END 完成时无感；如 binary 损坏 / 文件不存在，`readPerformanceSamples` 返回 emptyList → topSpeedKmh = null（与历史 session 兼容）

[**lapCount entity 与 Snackbar 派生语义差异**] → Mitigation：spec 明确 entity lapCount = "accepted SF crossing pairs"，**不承诺** qualityFlags 过滤；Snackbar `finishActiveLapSession` 用的是 in-memory `LapSession.completedLaps + qualityFlags` 派生，两端语义不同。**已知 limitation**：UI 上 Snackbar `X laps` 与 detail 屏 / Records list `lapCount` 数字可能不同（Snackbar 排除 invalid quality 圈，entity 计入所有 accepted SF pair）。统一两端语义作为 follow-up `unify-lap-count-semantics` 单独立项

[**trackId 非空 + catalog 解析失败 + snapshot 为空**] → Mitigation：D5 when 分支严格分流，此场景显示 "—" 而**不** fallback currentSelectedTrack；理论上正常路径不会发生（本 round startSession 同时写 trackId + snapshot），仅作防御性处理

[**round 间合回顺序错乱**] → Mitigation：本 round 与 round A `fix-lap-binary-ts-hygiene` / B `wire-laptime-to-gps-filter` **同函数潜在冲突**（同 `bridgeGpsToLapTiming` 函数体不同行）；合回顺序按看板 §4.1 / §5 推进；后合回方 rebase 时 MUST 跑 `:feature:test:testDebugUnitTest` 复测；与 D `track-tech-v2-style-debt-cleanup` 文件交叉，D 已声明依赖本 round 合回

## Migration Plan

1. **代码层**：本 round 单独 worktree 推进，完成后 ff-only 合回 `feature/track-tech-v2`
2. **Room migration 注册**：`MIGRATION_3_4` 定义在 `AppDatabase.companion object`，注册位置在 `feature/test/.../di/AppModule.kt:38` 的 `Room.databaseBuilder` 链路 `.addMigrations(AppDatabase.MIGRATION_3_4)`；同时**移除** `.fallbackToDestructiveMigration()`（可选保留 `fallbackToDestructiveMigrationFrom(1, 2)` 兜底 pre-A56 版本，apply 阶段决定）
3. **真机首次启动**：自动触发 v3→v4 migration（ALTER TABLE ADD COLUMN nullable，<10ms）；旧 row 字段为 NULL，detail 屏 D5 fallback 链显示 currentSelectedTrack
4. **回滚策略**：如 v3→v4 migration 在生产真机失败 → app 启动 crash（移除 fallback 后失败显式暴露）→ user 报告 → 立即 hotfix（加回 destructive fallback 或补 migration 实现）；user 也可通过 app 数据清理重置 Room（极端兜底）
5. **降级到 pre-本 round 版本**：fallback 字段为 null，detail 屏走兼容 fallback 路径，无功能 break

## Open Questions

无（关键决策 D1-D7 已拍板；apply 阶段需根据 AppModule 历史 commit 确认是否加 `fallbackToDestructiveMigrationFrom(1, 2)`）。
