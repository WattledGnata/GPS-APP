## 实施任务（依赖顺序）

本 change 落地 `persisted-session-summary` capability：endSession 时持久化 topSpeedKmh / lapCount / bestLapMs / trackId 到 `TelemetrySessionEntity`，detail 屏 / Records list 直读 entity 字段，detail 屏 trackId 解析回 Track（多赛道场景的历史归属修复）。

参考 `proposal.md` / `design.md` D1-D6 / `specs/persisted-session-summary/spec.md`。

依赖顺序：data 层（entity → migration → dao → repository → domain model）→ ViewModel callsite → UI 层 → 测试 → commit。

---

## 0. grep 预检

- [x] 0.1 **当前 schema version 核实**：

  ```bash
  grep -n "version =" /Users/wattledgnata/traeProjects/gps-app/.worktrees/persist-session-summary-fields/core/data/src/main/java/com/blazepush/core/data/local/AppDatabase.kt
  ```

  预期：`version = 3`。本 round migration 升到 4。如非 3，说明并行 round 同时改 schema，必须协商。

- [x] 0.2 **startSession 调用点核实**：

  ```bash
  grep -rn "telemetryRepository.startSession\|repository.startSession" /Users/wattledgnata/traeProjects/gps-app/.worktrees/persist-session-summary-fields/feature /Users/wattledgnata/traeProjects/gps-app/.worktrees/persist-session-summary-fields/app
  ```

  预期：仅 2 处命中（PERFORMANCE_TEST + LAP_SESSION）。如有第 3 处需检查 callsite scope。

- [x] 0.3 **`TelemetrySessionEntity` 字段当前定义核实**：

  ```bash
  grep -n "val \w" /Users/wattledgnata/traeProjects/gps-app/.worktrees/persist-session-summary-fields/core/data/src/main/java/com/blazepush/core/data/local/entity/TelemetrySessionEntity.kt
  ```

  预期：`sessionId / sessionType / startTs / endTs / binaryFilePath / lapCount / bestLapMs` 7 字段；本 round 加 3 个变 10 字段（topSpeedKmh / trackId / trackNameSnapshot）。

- [x] 0.4 **`TelemetrySession` domain model 当前字段核实**：

  ```bash
  grep -nA 10 "data class TelemetrySession" /Users/wattledgnata/traeProjects/gps-app/.worktrees/persist-session-summary-fields/core/domain/src/main/java/com/blazepush/core/domain/model/TelemetryModels.kt
  ```

  预期：现有同上 7 字段；本 round 加 3 个。

- [x] 0.5 **AppModule.kt Room 构造现状核实**：

  ```bash
  grep -n "Room.databaseBuilder\|fallbackToDestructive\|addMigrations" /Users/wattledgnata/traeProjects/gps-app/.worktrees/persist-session-summary-fields/feature/test/src/main/java/com/blazepush/feature/test/di/AppModule.kt
  ```

  预期：`Room.databaseBuilder` 在 line 38 + `.fallbackToDestructiveMigration()` 在 line 43 + 没有 `addMigrations`。本 round 后：`addMigrations(AppDatabase.MIGRATION_3_4)` 命中 / 全局 `.fallbackToDestructiveMigration()` 带括号字面零命中（路径 A 可保留 `.fallbackToDestructiveMigrationFrom(1, 2)` 兜底 v1/v2，apply 阶段根据 schema 历史决定）。

---

## 1. core/data：entity + migration + dao + repository

- [x] 1.1 `TelemetrySessionEntity` 加 3 字段：

  ```kotlin
  val topSpeedKmh: Double? = null,
  val trackId: String? = null,
  val trackNameSnapshot: String? = null,
  ```

- [x] 1.2 `TelemetrySession` domain model 同步加 3 字段（`core/domain/.../TelemetryModels.kt`）

- [x] 1.3 `AppDatabase`：
  - `@Database(version = 4)` 升版本号
  - 加 `MIGRATION_3_4` 在 companion object（不在 file-level）：
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

- [x] 1.4 **`feature/test/.../di/AppModule.kt`** Room.databaseBuilder 链路：
  - 加 `.addMigrations(AppDatabase.MIGRATION_3_4)`
  - **删全局 `.fallbackToDestructiveMigration()`**（防 v3→v4 migration 失败静默清库）
  - **apply 阶段决定**是否保留 `.fallbackToDestructiveMigrationFrom(1, 2)` 兜底 pre-A56 版本：
    - 跑 `git log --all --oneline -- core/data/src/main/java/com/blazepush/core/data/local/AppDatabase.kt` + `git log --all -p -- feature/test/src/main/java/com/blazepush/feature/test/di/AppModule.kt | grep "@Database\|version"` 看 schema version 历史
    - 如果 v1 / v2 在 release tag 上存在过（非 pre-release dev 提交） → 选**路径 A**：加 `.fallbackToDestructiveMigrationFrom(1, 2)` 兜底
    - 如果生产真机已知全部 v3 → 选**路径 B**：完全移除（v1/v2 真机升级会 crash 显式暴露）
    - 所选路径 MUST 写入 commit message body

- [x] 1.5 `TelemetrySessionDao` 加 `updateSummary` 方法（`@Query` 一次写齐 4 字段：endTs / lapCount / bestLapMs / topSpeedKmh，trackId/trackNameSnapshot 在 startSession 时已写）；保留 baseline `updateEndTs`（不删）

- [x] 1.6 `TelemetryRepository.startSession` 签名扩展：

  ```kotlin
  suspend fun startSession(
      type: TelemetrySessionType,
      trackId: String? = null,
      trackNameSnapshot: String? = null,
  ): String {
      ...
      val entity = TelemetrySessionEntity(
          sessionId = sessionId,
          sessionType = type.name,
          startTs = startTs,
          endTs = startTs,
          binaryFilePath = file.absolutePath,
          trackId = trackId,
          trackNameSnapshot = trackNameSnapshot,
      )
      ...
  }
  ```

- [x] 1.7 `TelemetryRepository.endSession` 改造：

  ```kotlin
  suspend fun endSession(sessionId: String) {
      val writer = activeWriter ?: return
      val filePath = activeWriter file path  // 从 activeWriter 拿 / 或从 entity 读
      writer.close()
      activeWriter = null
      activeSessionId = null
      val endTs = System.currentTimeMillis()

      val (topSpeedKmh, lapCount, bestLapMs) = withContext(Dispatchers.IO) {
          val samples = runCatching { readPerformanceSamples(filePath) }.getOrDefault(emptyList())
          val topSpeed = samples.maxOfOrNull { it.speedKmh }?.takeIf { it > 0.0 }
          val crossings = crossingDao.queryBySessionId(sessionId)
          val acceptedSF = crossings
              .filter { it.gateType.equals("StartFinish", ignoreCase = true) && it.accepted }
              .sortedBy { it.crossingTimestampMs }
          val durations = acceptedSF.zipWithNext { a, b -> b.crossingTimestampMs - a.crossingTimestampMs }
          Triple(topSpeed, durations.size, durations.minOrNull())
      }

      sessionDao.updateSummary(sessionId, endTs, lapCount, bestLapMs, topSpeedKmh)
  }
  ```

  **lapCount 派生语义明确**：accepted SF crossing pairs 数量（不读 LapRecord.qualityFlags）。Snackbar `finishActiveLapSession` 用的 in-memory completedLaps + qualityFlags 过滤是另一条派生路径，两端语义差异统一作为 follow-up `unify-lap-count-semantics` 单独立项。

  注意：filePath 从哪拿？baseline activeWriter 可能不持有 filePath；可在 startSession 时记入 `activeFilePath: String?` 内部状态，endSession 用。

- [x] 1.8 `TelemetryRepository.toDomain()` 映射加 `topSpeedKmh` + `trackId` + `trackNameSnapshot` 字段

- [x] 1.9 编译验证 `:core:data:compileDebugKotlin`

---

## 2. ViewModel callsite：传 trackId

- [x] 2.1 `TestSessionViewModel.bridgeGpsToLapTiming` 内 LAP_SESSION callsite（line ~569）：

  ```kotlin
  // baseline
  activeLapSessionId = telemetryRepository.startSession(TelemetrySessionType.LAP_SESSION)
  // 改为
  val resolvedTrack = trackCatalog.getTrack(config.trackId)
  activeLapSessionId = telemetryRepository.startSession(
      type = TelemetrySessionType.LAP_SESSION,
      trackId = config.trackId,                           // _lapRunConfig.value.trackId
      trackNameSnapshot = resolvedTrack?.name?.zh,        // catalog 解析当时的赛道名（snapshot）
  )
  ```

  **同函数潜在冲突警告**（看板 §5 已声明）：
  - **与 round A `fix-lap-binary-ts-hygiene`** 同函数：A 改 line 562 `tsDeltaMs` 公式（1 行）；本 round 改 line ~569 startSession callsite。两处 hunk 在同函数体内不同行，git rebase 可能产生 hunk 冲突
  - **合回顺序**：先合回方走顺利路径；后合回方在主干基础上 rebase 时 MUST 跑 `:feature:test:testDebugUnitTest` 复测确保对方 hunk 不影响本 round 语义

- [x] 2.2 PERFORMANCE_TEST callsite（line ~480）保持不动（默认 trackId / trackNameSnapshot = null）

- [x] 2.3 编译验证 `:feature:test:compileDebugKotlin`

---

## 3. UI 层改造

- [x] 3.1 **`LapSessionDetailScreen.kt`**：
  - 删除 `var topSpeed by remember { mutableStateOf<Double?>(null) }` + 对应 `LaunchedEffect` 内的 `readPerformanceSamples` 全扫派生逻辑
  - 改为直接读 `session?.topSpeedKmh`（DataLoad 后从 domain model 拿）
  - 加 `trackCatalog: TrackCatalog = koinInject()` 参数
  - **track name 用 when 分支严格分流**（**MUST NOT** 用 elvis 链 `?:` 串联，spec 已禁；elvis 链在 trackId 非空 + catalog 失败 + snapshot 为空时仍会落到 currentTrack，重引入"历史 session 显示当前赛道"bug）：

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

  - **distance 仅 catalog 解析成功时显示**（不依赖 currentSelectedTrack 的 lengthKm）：

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

  - **MUST NOT** 让"trackId 非空但 catalog 解析失败"走 `currentSelectedTrack` fallback（Codex P2.4 review 防误读）
  - 删除 import `kotlinx.coroutines.Dispatchers` + `withContext`（不再需要）

- [x] 3.2 **`RecordsHomeScreen.kt:LapsView`**：
  - `formatLapSessionRowTitle(session)` 改为含 `session.lapCount` / `session.bestLapMs` / `session.topSpeedKmh`：

    ```kotlin
    private fun formatLapSessionRowTitle(session: TelemetrySession): String {
        val date = formatter.format(Date(session.startTs))
        val lapCount = session.lapCount
        val best = session.bestLapMs?.let { formatLapTime(it) } ?: "--"
        return "$date · $lapCount laps · best $best"
    }
    ```

- [x] 3.3 编译验证 `:feature:test:compileDebugKotlin`

---

## 4. 单元测试

> **MUST NOT** 引入 `androidx.room:room-testing` / `Robolectric` / `androidx.test.core` / `mockk` 依赖（Codex 二/三轮 review 提出：会要求 Robolectric / androidTest 配置改造，超本 round scope；mockk 也不在 core/data 现有依赖里，仅有 mockito-core）。
> 本 round 测试形态：**Fake DAO（实现接口）+ 真实 BinaryTelemetryWriter / PerformanceTestTelemetryReader（用 createTempFile）+ mockito-core 的 mock(Context) + when().thenReturn(tempDir)**，与 baseline `core/data/src/test/.../TelemetryRepositoryTest.kt` 的测试模式完全一致。
> MIGRATION_3_4 自动化（MigrationTestHelper schema v3 → v4 验证）作为 follow-up `room-test-infrastructure` 单独立项。本 round MIGRATION 用 SQL 字符串自检兜底。

- [x] 4.1 新建 `core/data/src/test/.../local/AppDatabaseMigrationSqlTest.kt`：
  - **不**用 `MigrationTestHelper`（依赖未引入）
  - 直接断言 `AppDatabase.MIGRATION_3_4` 内含 3 个 `ALTER TABLE telemetry_sessions ADD COLUMN` 字符串：
    ```kotlin
    @Test fun migration_3_4_contains_three_add_column_statements() {
        // 通过反射读 MIGRATION_3_4.migrate body 的 SQL 字符串，或者
        // 在 MIGRATION_3_4 companion object 暴露 internal val SQL_STATEMENTS = listOf(...)
        // 测试断言 SQL_STATEMENTS 含 3 条 ALTER TABLE ADD COLUMN
    }
    ```
  - 断言 `@Database` 注解 version = 4（用 `AppDatabase::class.java.getAnnotation(Database::class.java)!!.version == 4`）

- [x] 4.2 新建 `core/data/src/test/.../repository/TelemetryRepositoryEndSessionPersistTest.kt`：
  - 测试形态：**Fake DAO + 真实 BinaryTelemetryWriter / PerformanceTestTelemetryReader**（**MUST NOT** 用 `Room.inMemoryDatabaseBuilder`；该 API 仍需要 Android Context，core/data JVM unit test 当前没有 Robolectric / ApplicationProvider 配置 — Codex 二轮 review P1.1 / P1.2 提出）
  - 实现 `class FakeTelemetrySessionDao : TelemetrySessionDao`：
    - `insert(entity)` 记录到 `var insertedEntities: MutableList<TelemetrySessionEntity>` 供测试断言（startSession 写入字段验证）
    - `updateSummary(sessionId, endTs, lapCount, bestLapMs, topSpeedKmh)` 记录到 `var lastUpdateSummary: UpdateSummaryCall?`（endSession 写入字段验证）
    - `queryBySessionId(sessionId)` 返回 insertedEntities 中匹配的（含 endSession 后的 update 应用）
    - `queryAll()` 返回 insertedEntities（按 startTs 倒序）
    - `updateEndTs(...)` 保留接口实现（baseline 兼容）
  - 实现 `class FakeCrossingEventDao : CrossingEventDao`：
    - `insertInTransaction(event)` 记录到 `var insertedCrossings: MutableList<CrossingEventEntity>`
    - `queryBySessionId(sessionId)` 返回 insertedCrossings 中匹配的
  - **真实 BinaryTelemetryWriter 跑**（不 mock）：用 `kotlin.io.path.createTempFile()` 创建 binary 文件路径，`writer.open(path, type, startTs)` + 多次 `writer.write(sample)` + `writer.close()`；这两个类 + `PerformanceTestTelemetryReader.read(path)` 在 JVM 可跑（无 Context 依赖）
  - **Context mock**：用 baseline `TelemetryRepositoryTest.kt:48-49` 同样的 mockito-core 模式（**MUST NOT** 用 `mockk` — 该依赖不在 core/data libs.versions.toml 里，apply 阶段引入会让 build 失败）：
    ```kotlin
    import org.mockito.Mockito.mock
    import org.mockito.Mockito.`when`

    @Before fun setUp() {
        tempDir = Files.createTempDirectory("test").toFile()
        context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(tempDir)   // startSession 内用 context.filesDir 生成 binary 路径
    }
    ```
  - 测试构造 `TelemetryRepository(context = context, sessionDao = fakeSessionDao, crossingDao = fakeCrossingDao)` —— context 是 mockito mock 但配了 `filesDir`，startSession 内 `File(context.filesDir, "telemetry/$sessionId.bin")` 能在 tempDir 下创建真实 binary 文件
  - 调 `repository.startSession(LAP_SESSION, trackId = "test-track", trackNameSnapshot = "成都天府国际赛道")` → 断言 `fakeSessionDao.insertedEntities` 含 trackId / trackNameSnapshot
  - 多次调 `repository.writeSample(sample)` 喂 4 个 sample（speed 100 / 150 / 200 / 180 km/h，writer 真实写入 binary 文件）
  - 多次调 `repository.writeCrossing(event)` 喂 4 个 crossings（writer 调 `fakeCrossingDao.insertInTransaction`）
  - 调 `repository.endSession(sessionId)` → endSession 内部 `readPerformanceSamples(filePath)` 真实读 tempDir 里的 binary，`crossingDao.queryBySessionId(sessionId)` 走 fake → 派生 + 调 `fakeSessionDao.updateSummary`
  - 断言 `fakeSessionDao.lastUpdateSummary` 含 `topSpeedKmh == 200.0` / `lapCount == 3` / `bestLapMs == durations.min()`
  - 至少 5 个 `@Test` 场景：trackId 持久化 / trackNameSnapshot 持久化 / topSpeed 派生 / lapCount 派生（accepted SF pair 语义）/ bestLapMs 派生 + bonus binary 缺失（用不存在的 path / 跳过 writeSample）fallback null
  - **MUST NOT** 引入：`Room.inMemoryDatabaseBuilder` / `androidx.room.testing` / `Robolectric` / `androidx.test.core.app.ApplicationProvider` / `mockk` / `io.mockk:*`
  - **MUST** 沿用：`org.mockito.Mockito.mock(Context::class.java)` + `when(...).thenReturn(...)` + `Files.createTempDirectory` + 真实 BinaryTelemetryWriter

- [x] 4.3 现有套件零回归：
  - `:core:data:testDebugUnitTest` 全绿
  - `:feature:test:testDebugUnitTest` 全绿（含本 round 添加的 ViewModel callsite 改动）

---

## 5. 编译/测试门槛

- [x] 5.1 `./gradlew :core:data:compileDebugKotlin` BUILD SUCCESSFUL
- [x] 5.2 `./gradlew :core:domain:compileDebugKotlin :core:domain:test` 全绿（domain model 加字段后 unit tests 兼容）
- [x] 5.3 `./gradlew :feature:test:compileDebugKotlin :feature:test:testDebugUnitTest` 全绿
- [x] 5.4 `./gradlew :app:compileDebugKotlin` BUILD SUCCESSFUL
- [x] 5.5 `./gradlew :core:data:testDebugUnitTest` 全绿（含新加的 Migration + EndSessionPersist 测试）

- [x] 5.6 **grep 自检**：
  ```bash
  # 1. 字段定义全部命中（3 个新字段）
  grep -n "topSpeedKmh: Double?\|trackId: String?\|trackNameSnapshot: String?" core/data/src/main/java/com/blazepush/core/data/local/entity/TelemetrySessionEntity.kt
  grep -n "topSpeedKmh: Double?\|trackId: String?\|trackNameSnapshot: String?" core/domain/src/main/java/com/blazepush/core/domain/model/TelemetryModels.kt

  # 2. Migration 在 AppDatabase 定义 + AppModule 注册 + fallbackToDestructive 已移除
  grep -n "MIGRATION_3_4\|version = 4" core/data/src/main/java/com/blazepush/core/data/local/AppDatabase.kt
  # 预期：MIGRATION_3_4 + version = 4 命中

  grep -n "addMigrations.*MIGRATION_3_4" feature/test/src/main/java/com/blazepush/feature/test/di/AppModule.kt
  # 预期：≥ 1 处命中（在 Room.databaseBuilder 链路）

  # 全局 destructive fallback 必须移除（带括号字面）
  grep -nE "\.fallbackToDestructiveMigration\(\)" feature/test/src/main/java/com/blazepush/feature/test/di/AppModule.kt
  # 预期：**零命中**（全局 .fallbackToDestructiveMigration() 已移除）

  # 路径 A 兜底：可能命中 fallbackToDestructiveMigrationFrom(1, 2)（不强求；如选路径 B 此 grep 也零命中）
  grep -nE "\.fallbackToDestructiveMigrationFrom\(1, 2\)" feature/test/src/main/java/com/blazepush/feature/test/di/AppModule.kt
  # 预期：选路径 A 时命中 1 处；选路径 B 时零命中。所选路径写入 commit message body

  # 3. detail 屏不再扫 binary
  grep -n "readPerformanceSamples\|readLapSamples" feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/LapSessionDetailScreen.kt
  # 预期：零命中

  # 4. detail 屏读 entity.topSpeedKmh
  grep -n "session.topSpeedKmh\|session?.topSpeedKmh" feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/LapSessionDetailScreen.kt
  # 预期：≥ 1 处命中

  # 5. detail 屏 track name 用 when 分支严格分流（不能用 elvis 链 fallback currentTrack）
  grep -nE "when \{|trackNameSnapshot|trackCatalog.getTrack" feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/LapSessionDetailScreen.kt
  # 预期：when {} + trackNameSnapshot + trackCatalog.getTrack 都命中

  awk '/displayTrackName/,/^    \}/' feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/LapSessionDetailScreen.kt | grep -nE "\?: currentTrack"
  # 预期：displayTrackName 派生表达式内**零命中** `?: currentTrack` elvis 链 fallback（防 Codex P1.2 重引入）

  # 6. endSession 不读 LapRecord.qualityFlags（accepted SF pair 语义）
  awk '/suspend fun endSession/,/^    \}/ ' core/data/src/main/java/com/blazepush/core/data/repository/TelemetryRepository.kt | grep -n "qualityFlags"
  # 预期：endSession 函数体内零命中

  # 7. room-testing 依赖未引入
  grep -n "androidx.room:room-testing\|room.testing\|androidx.room.testing" core/data/build.gradle.kts
  # 预期：零命中
  ```

---

## 6. 真机验证（manual gate · 看板 §4.2 串行强制）

> 真机验证前必须在主 session 对话窗口告知 user 申请授权（与并行 round A 真机验证不撞车）。

- [ ] 6.1 **申请授权**：在主 session 告知 user "round C `persist-session-summary-fields` 准备真机验证，apk 装到华为 8KE0219522008434，验证场景见 §6.2-6.6，请授权 / 让我等到 round A 验证完"

- [ ] 6.2 收到授权后安装：

  ```bash
  ANDROID_SERIAL=8KE0219522008434 ./gradlew :app:installDebug
  ```

- [ ] 6.3 **跑一次新圈速 session**（验证 endSession 写入字段）：
  - Laps 首页 → START LAP SESSION → 跑完一圈或几圈 → HOLD TO END → Snackbar "Lap session saved · X laps"
  - 进 detail 屏：
    - TOP SPEED 显示真实数字（不再是每次扫 binary，应该立即显示）
    - 不再有 ~50ms 加载延迟（本次进入时间 < 进入时其他屏的时间）
    - Track 名字显示为 session 当时选中的赛道（一期单赛道 TFIC LPCC）

- [ ] 6.4 **多赛道扩展模拟**（一期单赛道无法直接验证 trackId fallback；用以下方法兜底）：
  - 对一个**已存在的旧 session**（migration 前创建的，trackId = null）进 detail 屏
  - **预期**：track name 显示当前 currentSelectedTrack（fallback 路径生效，不抛异常 / 不显示 "Unknown"）
  - 旧 session 的 topSpeedKmh / lapCount / bestLapMs 字段为 default（migration 字段 null），UI 显示 "--"

- [ ] 6.5 **Records LAPS history 行**：
  - Records tab → LAPS sub-tab → SESSION HISTORY 列表
  - 验证：每行 title 含 lap count（如 "2026-05-01 10:35 · 3 laps · best 1:32.457"）
  - 不是仅时间 + duration

- [ ] 6.6 **Migration 兼容性验证**：
  - app 升级前：用旧版本（commit `6070ef5`）跑过 ≥ 1 个 session（schema v3）
  - 升级到新版本（本 round commit）后：
    - app 正常启动（没有"数据库 migration failed" crash）
    - Records LAPS history 仍能看到旧 session（topSpeedKmh / trackId 显示 "--" 或 fallback）
    - 跑新 session 后字段全部写入正常

- [ ] 6.7 **完成验证后告知 user**：在主 session 对话窗口报告"round C 真机验证 OK，可以放下一个 round 验证 / 准备合回"

---

## 7. Commit + 合回主干（看板 §3）

- [ ] 7.1 **Spec 验证**：

  ```bash
  cd /Users/wattledgnata/traeProjects/gps-app   # 工件 source-of-truth 在主区
  /Users/wattledgnata/.local/opt/node-v22.22.1-darwin-arm64/bin/openspec validate persist-session-summary-fields --strict
  ```

  预期：`Change 'persist-session-summary-fields' is valid`

- [ ] 7.2 **commit on worktree branch `feature/persist-session-summary-fields`**：

  ```
  feat(data): persist session summary · topSpeedKmh / lapCount / bestLapMs / trackId / trackNameSnapshot

  ...
  ```

  body 含：
  - capability `persisted-session-summary` 描述
  - Room schema v3 → v4 migration 说明 + AppModule 注册位置 + 移除 fallbackToDestructiveMigration（注明 v1/v2 处理策略 — 路径 A `fallbackToDestructiveMigrationFrom(1, 2)` 或路径 B 完全移除，apply 阶段决定）
  - startSession 签名扩展（trackId + trackNameSnapshot 两参数）/ endSession 行为变更（IO 调度 + 派生持久化）
  - detail 屏 track name 用 when 分支严格分流（snapshot / trackId 解析 / 历史 fallback 互斥）/ distance 仅 catalog 解析成功时显示 / Records list 直读 entity summary
  - lapCount 派生语义明确为 accepted SF crossing pairs（不承诺 qualityFlags 过滤；与 Snackbar in-memory 路径语义差异作为 follow-up `unify-lap-count-semantics`）
  - 单元测试覆盖（Fake DAO + 真实 BinaryTelemetryWriter / SQL 字符串自检；不引入 room-testing / Robolectric）
  - 真机验证状态（含 v3→v4 migration manual gate）
  - 与 add-lap-session-phase1 round Codex P2.4 review 的衔接（trackNameSnapshot 持久化根因消除"历史 session 显示当前赛道"bug）
  - **同函数潜在冲突警告**：与 round A `fix-lap-binary-ts-hygiene` 同 `bridgeGpsToLapTiming` 函数体不同行；后合回方需 rebase 复测

- [ ] 7.3 **合回主干**（看板 §3 checklist）：

  ```bash
  # 在 worktree 内
  git fetch origin
  git rebase feature/track-tech-v2  # **同函数潜在冲突**：如 round A 已先合回，rebase 时手工解决 bridgeGpsToLapTiming hunk 冲突
  ./gradlew :core:data:testDebugUnitTest :feature:test:testDebugUnitTest  # rebase 后再跑（**MUST**）

  # 切回主区
  cd /Users/wattledgnata/traeProjects/gps-app
  git checkout feature/track-tech-v2
  git merge feature/persist-session-summary-fields --ff-only
  ./gradlew :app:assembleDebug  # 主区编译验证
  git diff --stat HEAD~1..HEAD -- core/ feature/  # 验证 diff 边界
  ```

- [ ] 7.4 **更新看板 §5**：本 round 状态由 `推进中` 改为 `done`，最近合回 commit 字段填实际 hash；§6 共享文件占用全部标 done

- [ ] 7.5 **提醒 user 触发 Codex review**（看板 §4.1 push 顺序由 user 决定）

- [ ] 7.6 **D round（track-tech-v2-style-debt-cleanup）依赖解锁通知**：本 round 合回后，主 session 在看板 §5 把 D 状态由"待启动"改为"可启动"（如 A/B 也已合回）；告知 user

---

## 8. Post-apply follow-up（不在本 round scope）

- **Top speed in-flight 增量维护**（避免每次 endSession 扫 binary）：BinaryTelemetryWriter 在 write 阶段维护 `runningMaxSpeed`，close 时序列化到 binary header，endSession 直接读 header 不扫全文件 — 独立 round（性能优化，仅当 session 数据量极大时受益）
- **lapCount/bestLapMs 异常 session 修复工具**：app 启动时检测 endTs > 0 但 lapCount = 0 的 session，按 crossings 重算 + 写回（适用本 round 落地前的"假完成"session）— 独立 round
- **trackId 多赛道 catalog 同步**：track 删除 / rename 时旧 session 的 trackId 处理策略（保留 / 标记 deleted / migration 时清空）— 与多赛道扩展 round 配套
