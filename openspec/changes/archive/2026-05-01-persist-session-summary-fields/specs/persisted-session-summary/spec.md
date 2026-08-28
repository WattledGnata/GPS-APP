## ADDED Requirements

### Requirement: TelemetrySessionEntity 加 topSpeedKmh / trackId / trackNameSnapshot 字段

`core/data/.../entity/TelemetrySessionEntity.kt` MUST 加 3 个字段（全部 nullable，向下兼容历史 row）：

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
    val topSpeedKmh: Double? = null,        // 新增：session 全程最高速度（km/h）
    val trackId: String? = null,             // 新增：session 启动时的 trackId
    val trackNameSnapshot: String? = null,  // 新增：session 启动时的 track display name 快照
)
```

`core/domain/.../model/TelemetryModels.kt` 的 `TelemetrySession` domain model MUST 同步新增同名字段。

#### Scenario: entity 字段全部声明

- **GIVEN** 实施后 `TelemetrySessionEntity.kt` 源码
- **WHEN** grep `topSpeedKmh: Double?` / `trackId: String?` / `trackNameSnapshot: String?`
- **THEN** 3 个新字段定义全部命中
- **AND** 都允许 nullable

#### Scenario: domain model 字段同步

- **GIVEN** 实施后 `core/domain/.../TelemetryModels.kt:TelemetrySession` 数据类
- **WHEN** 阅读字段列表
- **THEN** 含 `topSpeedKmh: Double?` + `trackId: String?` + `trackNameSnapshot: String?` 字段
- **AND** entity → domain 映射函数（`TelemetrySessionEntity.toDomain()` in `TelemetryRepository.kt`）含 3 个新字段映射

### Requirement: Room migration 3 → 4 在 AppModule 注册，移除 fallbackToDestructiveMigration

`AppDatabase` MUST：

- schema version 由 3 升到 4
- companion object 内定义 `MIGRATION_3_4`（用 `ALTER TABLE ADD COLUMN` 不重建表）：

```kotlin
companion object {
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE telemetry_sessions ADD COLUMN topSpeedKmh REAL")
            db.execSQL("ALTER TABLE telemetry_sessions ADD COLUMN trackId TEXT")
            db.execSQL("ALTER TABLE telemetry_sessions ADD COLUMN trackNameSnapshot TEXT")
        }
    }
}
```

**`feature/test/.../di/AppModule.kt:38-43`** 的 `Room.databaseBuilder` 链路 MUST：

- 调 `.addMigrations(AppDatabase.MIGRATION_3_4)` 注册 v3→v4 migration
- **移除** 全局 `.fallbackToDestructiveMigration()`（防 v3→v4 失败时静默清空数据）

**对 v1/v2 旧数据库的处理**（Codex P1.3 review 提出的风险）：

本 round 仅承诺 **v3 → v4** 的严格 migration 路径（`unify-gps-telemetry-persistence` round / A56 落地的 v3 是当前最低支持的生产 schema）。对 v1 / v2 旧数据库（pre-A56 版本，可能在早期开发期真机存在），由于本 round 不补 1→2 / 2→3 完整 migration 链，处理路径二选一：

- **路径 A（默认）**：保留窄范围 destructive fallback `fallbackToDestructiveMigrationFrom(1, 2)`：
  ```kotlin
  Room.databaseBuilder(...)
      .addMigrations(AppDatabase.MIGRATION_3_4)
      .fallbackToDestructiveMigrationFrom(1, 2)   // 保留：v1/v2 destructive；v3+ 严格 migration
      .build()
  ```
  v1/v2 真机升级时 destructive 清库（pre-A56 数据反正不是当前用户主流），但 v3→v4 仍走严格 migration 保护现役数据
- **路径 B（apply 阶段如能确认所有真机至少 v3）**：完全移除 fallback，不保留 `fallbackToDestructiveMigrationFrom`；v1/v2 真机升级会 crash，user 通过 manual gate 验证后清理

**apply 阶段决定**：跑 `git log --all --oneline -- feature/test/src/main/java/com/blazepush/feature/test/di/AppModule.kt` + `core/data/src/main/java/com/blazepush/core/data/local/AppDatabase.kt` 看 schema version 历史；如果 v1/v2 在 release 标签上存在过（非 pre-release dev 提交），选路径 A；否则选路径 B。**MUST** 在 commit message body / risks 节明确所选路径。

历史 session（v3 → v4 migration 前已存在的 row）字段为 NULL。app 升级后不丢失任何 v3 session 数据。

#### Scenario: schema version 升到 4

- **GIVEN** 实施后 `AppDatabase.kt` 源码
- **WHEN** 阅读 `@Database(...)` 注解
- **THEN** `version = 4`

#### Scenario: MIGRATION_3_4 在 AppModule 注册

- **GIVEN** 实施后 `feature/test/.../di/AppModule.kt`
- **WHEN** grep `addMigrations.*MIGRATION_3_4`
- **THEN** 至少一处命中（在 `Room.databaseBuilder` 调用链内）

#### Scenario: 移除全局 fallbackToDestructiveMigration（路径 A 允许窄范围保留）

- **GIVEN** 实施后 `feature/test/.../di/AppModule.kt`
- **WHEN** grep `\.fallbackToDestructiveMigration\(\)`（**带括号**的全局形式，正则严格匹配 `.fallbackToDestructiveMigration()` 字面）
- **THEN** **零命中**（全局 destructive fallback 已移除）
- **AND** 如选路径 A 兜底 v1/v2：grep `\.fallbackToDestructiveMigrationFrom\(1, 2\)` 命中一次（窄范围 destructive，仅对 pre-A56 版本）；如选路径 B：此 grep 也零命中
- **AND** 选定路径 MUST 写入 commit message body

#### Scenario: migration 用 ADD COLUMN 不重建表

- **GIVEN** 实施后 `MIGRATION_3_4.migrate(db)` 实现
- **WHEN** 阅读 `db.execSQL` 调用
- **THEN** 含 `ALTER TABLE telemetry_sessions ADD COLUMN topSpeedKmh REAL`
- **AND** 含 `ALTER TABLE telemetry_sessions ADD COLUMN trackId TEXT`
- **AND** 含 `ALTER TABLE telemetry_sessions ADD COLUMN trackNameSnapshot TEXT`
- **AND** **不**含 `DROP TABLE` / `CREATE TABLE` 语句

#### Scenario: 历史 session 兼容（真机升级验证）

- **GIVEN** 一个 schema version 3 的真机数据库（含 N 条 session row，无 topSpeedKmh / trackId / trackNameSnapshot 字段）
- **WHEN** app 升级到本 round build 触发 MIGRATION_3_4 自动迁移
- **THEN** 全部 N 条 row 保留
- **AND** 3 个新字段值为 NULL
- **AND** `getRecentLapSessions(...)` 仍返回这些 row（domain model 字段为 null）
- **AND** detail 屏对 trackNameSnapshot 与 trackId 都为 null 的 session 走 `currentSelectedTrack` fallback（不抛异常）

### Requirement: TelemetryRepository.startSession 签名加 trackId + trackNameSnapshot

`TelemetryRepository.startSession` MUST 扩展签名：

```kotlin
suspend fun startSession(
    type: TelemetrySessionType,
    trackId: String? = null,
    trackNameSnapshot: String? = null,
): String
```

实现 MUST 把两个 nullable 参数一同写入 entity；默认 null 兼容 `PERFORMANCE_TEST` callsite。

`LAP_SESSION` callsite（`TestSessionViewModel.bridgeGpsToLapTiming` 内 `activeLapSessionId == null` 分支）MUST 显式传 `_lapRunConfig.value?.trackId` + 解析后的 trackName（如 `trackCatalog.getTrack(trackId)?.name?.zh`）。

#### Scenario: startSession 签名扩展

- **GIVEN** 实施后 `TelemetryRepository.kt`
- **WHEN** grep `suspend fun startSession`
- **THEN** 命中函数签名含 `trackId: String? = null` + `trackNameSnapshot: String? = null` 两个默认参数

#### Scenario: PERFORMANCE_TEST callsite 不破坏

- **GIVEN** 实施后 `TestSessionViewModel.kt:480` 加减速测试 startSession 调用
- **WHEN** 阅读调用代码
- **THEN** 仍写 `telemetryRepository.startSession(TelemetrySessionType.PERFORMANCE_TEST)`（依赖默认值）

#### Scenario: LAP_SESSION callsite 传 trackId 与 trackNameSnapshot

- **GIVEN** 实施后 `TestSessionViewModel.bridgeGpsToLapTiming` 内 LAP_SESSION startSession 调用
- **WHEN** 阅读调用代码
- **THEN** 含 `telemetryRepository.startSession(TelemetrySessionType.LAP_SESSION, trackId = ..., trackNameSnapshot = ...)`
- **AND** trackId 来源是 `_lapRunConfig.value?.trackId` 或等价
- **AND** trackNameSnapshot 来源是 `trackCatalog.getTrack(trackId)?.name?.zh` 或等价（startSession 时刻的赛道名 snapshot）

### Requirement: TelemetryRepository.endSession 派生并持久化 summary（accepted SF crossing pairs 语义）

`TelemetryRepository.endSession(sessionId: String)` MUST：

1. 关闭 active writer（baseline 行为，不变）
2. **新增**：用 `withContext(Dispatchers.IO)` 切线程跑：
   - 调 `readPerformanceSamples(filePath)` 派生 `topSpeedKmh = samples.maxOfOrNull { it.speedKmh }?.takeIf { it > 0.0 }`
   - 调 `crossingDao.queryBySessionId(sessionId)` 派生 lapCount/bestLapMs（accepted SF pairs 语义）
3. 调 `sessionDao.updateSummary(sessionId, endTs, lapCount, bestLapMs, topSpeedKmh)` 一次写齐 4 字段

**lapCount 派生语义**（明确为 "accepted SF crossing pairs"，**不承诺** qualityFlags 过滤）：

```kotlin
val acceptedSF = crossings
    .filter { it.gateType.equals("StartFinish", ignoreCase = true) && it.accepted }
    .sortedBy { it.crossingTimestampMs }
val durations = acceptedSF.zipWithNext { a, b -> b.crossingTimestampMs - a.crossingTimestampMs }
val lapCount = durations.size       // accepted SF 配对数
val bestLapMs = durations.minOrNull()
```

**已知语义差异**（与 `add-lap-session-phase1` round 的 Snackbar `finishActiveLapSession` 用 `LapSession.completedLaps.filter { qualityFlags.isEmpty() }` 派生不一致）：

- Snackbar lapCount 基于 in-memory `LapSession.completedLaps` + qualityFlags 过滤
- entity lapCount（本 round）基于 crossings + 仅 accepted（不读 qualityFlags，crossings 表无该字段）
- 两端语义不同，UI 上 Snackbar `X laps` 与 detail 屏 / Records list `lapCount` 数字可能不同
- 统一两端语义作为 follow-up `unify-lap-count-semantics` 单独立项

`TelemetrySessionDao` MUST 加 `updateSummary` 方法：

```kotlin
@Query("""
    UPDATE telemetry_sessions
    SET endTs = :endTs, lapCount = :lapCount, bestLapMs = :bestLapMs, topSpeedKmh = :topSpeedKmh
    WHERE sessionId = :sessionId
""")
suspend fun updateSummary(
    sessionId: String,
    endTs: Long,
    lapCount: Int,
    bestLapMs: Long?,
    topSpeedKmh: Double?,
)
```

baseline `updateEndTs` 保留（不删；其他 callsite 仍可用）。

binary 文件不存在或为空时 `topSpeedKmh = null`；crossings 为空时 `lapCount = 0` / `bestLapMs = null`。endSession **不抛异常**。

#### Scenario: endSession 在 IO 调度跑扫描

- **GIVEN** 实施后 `TelemetryRepository.endSession` 实现
- **WHEN** 阅读函数 body
- **THEN** 含 `withContext(Dispatchers.IO)` 调用包裹 `readPerformanceSamples` + `crossingDao.queryBySessionId`

#### Scenario: endSession 写齐 4 字段

- **GIVEN** 实施后 `TelemetryRepository.endSession` 实现
- **WHEN** grep `sessionDao.updateSummary` 调用
- **THEN** 命中一次
- **AND** 参数包含 `endTs / lapCount / bestLapMs / topSpeedKmh`

#### Scenario: lapCount 派生语义为 accepted SF crossing pairs

- **GIVEN** 一个 session 内有 4 个 crossing：3 个 accepted StartFinish（t=1000 / 2200 / 3300）+ 1 个 rejected StartFinish（t=2700, reason=WrongDirection）
- **WHEN** endSession 派生 lapCount
- **THEN** `lapCount == 2`（acceptedSF.size = 3 → durations.size = 2 配对）
- **AND** `bestLapMs == 1100`（durations [1200, 1100] 的 minOrNull）
- **AND** rejected crossing 不计入 lapCount

#### Scenario: lapCount 不依赖 LapRecord.qualityFlags

- **GIVEN** 实施后 endSession 派生逻辑源码
- **WHEN** grep `qualityFlags`
- **THEN** 在 `TelemetryRepository.endSession` 实现内**零命中**（不读 LapRecord.qualityFlags；与 Snackbar 路径语义差异由 follow-up round 统一）

#### Scenario: binary 缺失时 topSpeed null

- **GIVEN** session.binaryFilePath 指向不存在的文件
- **WHEN** endSession 派生 topSpeedKmh
- **THEN** topSpeedKmh = null
- **AND** endSession 不抛异常
- **AND** sessionDao.updateSummary 仍被调用（其他 3 字段仍写入）

### Requirement: detail 屏直读 entity 字段，track name 用 when 分支严格分流

`LapSessionDetailScreen` MUST：

- 删除 `LaunchedEffect(sessionId)` 内的 `readPerformanceSamples(filePath)` 全扫调用
- 直接读 `session?.topSpeedKmh`
- track name **MUST 用 when 分支** 严格分流，**MUST NOT** 用 elvis 链 `?:` 串联（Codex P1.2 review 提出：elvis 链在 trackId 非空 + catalog 失败 + snapshot 为空时仍会落到 currentTrack，等价于"历史 session 显示当前赛道"bug）：

  ```kotlin
  val displayTrackName = remember(session, currentTrack) {
      val s = session
      when {
          // 优先级 1：snapshot 非空（本 round 后所有新 session 都会有）
          !s?.trackNameSnapshot.isNullOrBlank() -> s.trackNameSnapshot!!
          // 优先级 2：snapshot 空 + trackId 非空（防御性场景）
          s?.trackId != null -> {
              // catalog 解析成功 → catalog name
              // catalog 解析失败 → "—"（不 fallback currentTrack）
              trackCatalog.getTrack(s.trackId)?.name?.zh ?: "—"
          }
          // 优先级 3：snapshot 与 trackId 都为 null（历史 session）
          else -> currentTrack?.name?.zh ?: "—"
      }
  }
  ```

  **三个互斥分支**：

  | 输入条件 | 输出 |
  |---|---|
  | `trackNameSnapshot` 非空 | snapshot 字面 |
  | `trackNameSnapshot` 空 + `trackId` 非空 + catalog 解析成功 | catalog name |
  | `trackNameSnapshot` 空 + `trackId` 非空 + **catalog 解析失败** | **`"—"`**（**不** fallback currentTrack） |
  | `trackNameSnapshot` 空 + `trackId` 空（历史 session） | currentTrack name 或 "—" |

- distance **仅** catalog 解析成功时显示（不依赖 currentSelectedTrack 的 lengthKm 避免误读）：

  ```kotlin
  val distanceKm = remember(session, derived) {
      session?.trackId
          ?.let { trackCatalog.getTrack(it) }
          ?.lengthKm
          ?.let { it * derived.validLaps }
          ?.takeIf { it > 0.0 }
  }
  ```

  catalog 失败 → distance 显示 "—"

#### Scenario: detail 屏不再扫 binary

- **GIVEN** 实施后 `LapSessionDetailScreen.kt` 源码
- **WHEN** grep `readPerformanceSamples` / `readLapSamples`
- **THEN** 在 detail 屏内**零命中**

#### Scenario: detail 屏读 entity.topSpeedKmh

- **GIVEN** 实施后 `LapSessionDetailScreen.kt`
- **WHEN** grep `session.topSpeedKmh` 或 `session?.topSpeedKmh`
- **THEN** 至少一处命中

#### Scenario: track name 用 when 分支不用 elvis 链

- **GIVEN** 实施后 `LapSessionDetailScreen.kt` 内 displayTrackName 派生
- **WHEN** 阅读派生表达式
- **THEN** 含 `when { ... }` 表达式
- **AND** 含三个互斥分支：snapshot 非空 / snapshot 空+trackId 非空 / 都为 null
- **AND** **不**含 `trackNameSnapshot ?: ... ?: ... ?: currentTrack` 这种 elvis 链 fallback（grep `\?: currentTrack` 在 displayTrackName 派生内零命中）

#### Scenario: 历史 session（trackNameSnapshot 与 trackId 均为 null）回退到 currentSelectedTrack

- **GIVEN** session.trackNameSnapshot == null 且 session.trackId == null（migration 后的历史 session，仅"都为 null"分支命中）
- **WHEN** detail 屏渲染
- **THEN** track name 显示当前 currentSelectedTrack name（仅此分支允许 fallback）
- **AND** 不抛异常

#### Scenario: trackNameSnapshot 优先（防 catalog 删除误读）

- **GIVEN** session.trackNameSnapshot == "成都天府国际赛道" 但 session.trackId == "preset-deleted-track"，trackCatalog.getTrack(...) 返回 null
- **WHEN** detail 屏渲染
- **THEN** track name 显示 "成都天府国际赛道"（snapshot 优先分支命中）
- **AND** 不查 catalog（短路）

#### Scenario: trackId 非空 + catalog 失败 + snapshot 空 → 显示 "—"（不 fallback currentTrack）

- **GIVEN** session.trackNameSnapshot == null 且 session.trackId == "preset-deleted-track"，trackCatalog.getTrack(...) 返回 null
- **WHEN** detail 屏渲染
- **THEN** track name 显示 "—"（trackId 非空分支命中，catalog 解析失败 → "—"）
- **AND** **不** fallback currentSelectedTrack（这是 Codex P1.2 防误读的关键 scenario）

#### Scenario: distance 仅 catalog 解析成功时显示

- **GIVEN** session.trackId == "preset-deleted-track"，trackCatalog.getTrack(...) 返回 null
- **WHEN** detail 屏渲染 distance row
- **THEN** distance 显示 "—"（**不**用 currentSelectedTrack 的 lengthKm 计算）

### Requirement: Records LAPS SESSION HISTORY 直读 entity summary

`RecordsHomeScreen.LapsView.SESSION HISTORY` 列表行 MUST：

- 直接渲染 `session.lapCount` / `session.bestLapMs` / `session.topSpeedKmh`
- title 字符串可包含 lapCount + bestLapMs（如 `"yyyy-MM-dd HH:mm · {lapCount} laps · best {bestLap}"`），不再仅显示 duration
- 行点击 `navigate("lap_session_detail/{sessionId}")` 行为不变

#### Scenario: history 行用 entity summary

- **GIVEN** 实施后 `RecordsHomeScreen.kt` 内 `formatLapSessionRowTitle` 或等价 helper
- **WHEN** 阅读 title 拼接逻辑
- **THEN** 含 `session.lapCount` 或 `session.bestLapMs` 字段引用

### Requirement: 单元测试用 fake DAO + 真实 BinaryTelemetryWriter（不引入 room-testing / Robolectric）

本 round MUST **不**引入 `androidx.room:room-testing` 依赖，**也不**引入 Robolectric / AndroidX test core 等 Android Context 依赖（Codex P1.1 review 提出：`Room.inMemoryDatabaseBuilder` 仍需要 Android Context，core/data 现有 testDebugUnitTest 无 Robolectric / ApplicationProvider 配置；现有测试形态用 Fake DAO 避开 Room setup）。

测试形态 MUST 与 baseline core/data 测试一致：**Fake DAO 实现接口 + 真实 `BinaryTelemetryWriter` / `PerformanceTestTelemetryReader`** 覆盖 endSession 派生与持久化逻辑：

1. **`FakeTelemetrySessionDao`**：实现 `TelemetrySessionDao` 接口，`updateSummary` 调用记录到 `var lastUpdate: UpdateSummaryCall?` 供测试断言
2. **`FakeCrossingEventDao`**：实现 `CrossingEventDao` 接口，`queryBySessionId` 返回测试构造的 crossing list
3. **`BinaryTelemetryWriter` 真实跑** + **`PerformanceTestTelemetryReader` 真实读**（这两个类不依赖 Room / Context，可在 JVM unit test 跑）
4. 测试 endSession 流程：构造 fakeDao + 真实 writer → 喂 sample（speed 100/150/200/180）+ 喂 crossings → 调 `endSession(sessionId)` → 断言 fakeDao 收到的 `updateSummary` 参数 + entity startSession 写入参数

**MIGRATION_3_4 SQL 自检**：单独测试文件**不调** Room migration 真实路径（避免 Context 依赖），只断言 source code 内含 3 个 `ALTER TABLE ADD COLUMN` 字符串：

```kotlin
@Test fun migration_3_4_alter_table_statements() {
    // 通过 internal val 暴露 SQL_STATEMENTS = listOf("ALTER TABLE ... ADD COLUMN topSpeedKmh REAL", ...)
    // 测试断言 SQL_STATEMENTS.size == 3 + 各字符串内容
    assertEquals(3, AppDatabase.MIGRATION_3_4_SQL.size)
    assertTrue(AppDatabase.MIGRATION_3_4_SQL.any { it.contains("ADD COLUMN topSpeedKmh REAL") })
    // ...
}

@Test fun database_version_is_four() {
    val db = AppDatabase::class.java.getAnnotation(androidx.room.Database::class.java)
    assertEquals(4, db.version)
}
```

实现侧：MIGRATION_3_4 在 companion object 暴露 `internal val MIGRATION_3_4_SQL: List<String>` 包含 3 条 SQL 字符串，`migrate()` 内 `SQL_STATEMENTS.forEach { db.execSQL(it) }`。这样 JVM unit test 可直接读 list 断言而不需要 Android Context。

**真机升级验证**（manual gate / tasks §6.6）：用旧版本（commit `6070ef5`，schema v3）跑 ≥ 1 个 session → 升级到本 round build → 验证 app 启动 + Records LAPS history 仍可见 + 旧 session 字段为 null + 新跑 session 字段全部写入。

完整 MigrationTestHelper 自动化（schema v3 → v4 的 row 保留断言、跨 schema 升级路径）作为 follow-up `room-test-infrastructure` 单独立项（与 Robolectric / androidTest 配置一起讨论）。

#### Scenario: TelemetryRepositoryEndSessionPersistTest 用 Fake DAO

- **GIVEN** 实施后代码库
- **WHEN** find `core/data/src/test/.../TelemetryRepositoryEndSessionPersistTest.kt`
- **THEN** 命中
- **AND** 测试代码用 `class FakeTelemetrySessionDao : TelemetrySessionDao` 实现接口（**不**用 `Room.inMemoryDatabaseBuilder`）
- **AND** 测试代码用 `class FakeCrossingEventDao : CrossingEventDao` 实现接口
- **AND** ≥ 5 个测试场景：startSession 写入 trackId / startSession 写入 trackNameSnapshot / endSession topSpeedKmh 派生 / endSession lapCount 派生（accepted SF pair）/ endSession bestLapMs 派生 / binary 缺失 fallback null

#### Scenario: 测试 BinaryTelemetryWriter 真实跑

- **GIVEN** 实施后 `TelemetryRepositoryEndSessionPersistTest`
- **WHEN** 阅读测试代码
- **THEN** 含真实 `BinaryTelemetryWriter` 实例化（不 mock）
- **AND** 含真实 `PerformanceTestTelemetryReader.read(filePath)` 调用验证 sample 写读 round trip
- **AND** 不依赖 Android Context / Robolectric（`@RunWith` 不指定 Robolectric）

#### Scenario: MIGRATION_3_4 SQL 字符串测试不依赖 Context

- **GIVEN** 实施后代码库
- **WHEN** find `core/data/src/test/.../AppDatabaseMigrationSqlTest.kt`
- **THEN** 命中
- **AND** 测试 `@Test` 直接断言 `AppDatabase.MIGRATION_3_4_SQL` list 内含 3 条 ALTER TABLE 字符串
- **AND** 测试 `@Test` 用反射读 `AppDatabase` 的 `@Database` 注解断言 `version == 4`
- **AND** **不**调 `MIGRATION_3_4.migrate(...)` 真实路径（无 SupportSQLiteDatabase / Context 依赖）

#### Scenario: AppDatabase 暴露 MIGRATION_3_4_SQL 列表给测试

- **GIVEN** 实施后 `AppDatabase.kt`
- **WHEN** grep `MIGRATION_3_4_SQL` 或等价 internal list
- **THEN** 命中（companion object 内的 `internal val MIGRATION_3_4_SQL: List<String>`）
- **AND** `MIGRATION_3_4.migrate(db)` 实现 `MIGRATION_3_4_SQL.forEach { db.execSQL(it) }`

#### Scenario: 测试套件全绿

- **GIVEN** 实施后代码库
- **WHEN** 执行 `./gradlew :core:data:testDebugUnitTest`
- **THEN** BUILD SUCCESSFUL
- **AND** 含上述新测试文件 + baseline 套件零回归

#### Scenario: 不引入 room-testing / Robolectric / Context 测试依赖

- **GIVEN** 实施后 `core/data/build.gradle.kts`
- **WHEN** grep `androidx.room:room-testing` / `room.testing` / `robolectric` / `androidx.test.core`
- **THEN** **零命中**（本 round 不引入这些依赖；完整 Migration 自动化测试 / Robolectric 形态切换作为 follow-up `room-test-infrastructure`）
