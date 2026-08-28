# Tasks：session-video-metadata-persist

## §1 顺手修 P2：AppModule fallbackFrom 去掉 `2`

- [x] **1.1** `feature/test/src/main/java/com/blazepush/feature/test/di/AppModule.kt` 行 55：
  `.fallbackToDestructiveMigrationFrom(1, 2)` → `.fallbackToDestructiveMigrationFrom(1)`。
  Done condition：文件中 `fallbackToDestructiveMigrationFrom` 不含 `, 2` 参数。
- [x] **1.2** 同文件注释同步更新：体现 migrationChain 覆盖 v2→v6，仅 v1 走 destructive。
  Done condition：注释中 "MUST NOT 含 2" 与实参自洽。

## §2 TelemetrySessionEntity 加字段

- [x] **2.1** `core/data/src/main/java/com/blazepush/core/data/local/entity/TelemetrySessionEntity.kt`：
  在末尾 `trackNameSnapshot` 之后加：
  ```kotlin
  // schema v6 起：视频文件 absolute path（round 3 录制结束后由 attachVideoToSession 写入）；null = 无视频
  val videoFilePath: String? = null,
  // schema v6 起：录制首帧回调时刻 System.currentTimeMillis()，与 binary absoluteTsMs 同时钟域
  // 供未来视频帧↔遥测对齐；null = 未开始录制或无视频
  val videoStartedAtWallClock: Long? = null,
  ```
  Done condition：`TelemetrySessionEntity` 含两个新 nullable 字段，编译通过。

## §3 TelemetrySession domain model 加字段

- [x] **3.1** `core/domain/src/main/java/com/blazepush/core/domain/model/TelemetryModels.kt`：
  `TelemetrySession` data class 末尾（`trackNameSnapshot` 之后）加：
  ```kotlin
  val videoFilePath: String? = null,
  val videoStartedAtWallClock: Long? = null,
  ```
  Done condition：`TelemetrySession` 含两新字段，编译通过。

## §4 toDomain() mapper 更新

- [x] **4.1** `core/data/src/main/java/com/blazepush/core/data/repository/TelemetryRepository.kt`
  私有扩展函数 `TelemetrySessionEntity.toDomain()` 中，`TelemetrySession(...)` 构造调用末尾加：
  ```kotlin
  videoFilePath = videoFilePath,
  videoStartedAtWallClock = videoStartedAtWallClock,
  ```
  Done condition：mapper 传递新字段，编译通过，`getSession` 返回 domain model 含新字段。

## §5 AppDatabase v5 → v6 + migration5To6

- [x] **5.1** `core/data/src/main/java/com/blazepush/core/data/local/AppDatabase.kt`：
  `@Database version = 5` → `version = 6`。
  Done condition：注解版本号为 6。
- [x] **5.2** 同文件 companion object 加 `migration5To6Sql`（`internal val`，供测试断言）：
  ```kotlin
  internal val migration5To6Sql: List<String> = listOf(
      "ALTER TABLE telemetry_sessions ADD COLUMN videoFilePath TEXT",
      "ALTER TABLE telemetry_sessions ADD COLUMN videoStartedAtWallClock INTEGER",
  )
  ```
  Done condition：`migration5To6Sql.size == 2`，含两条 ALTER TABLE 语句。
- [x] **5.3** 同文件加 `migration5To6: Migration`（`object : Migration(5, 6)`），
  `migrate()` 内 `migration5To6Sql.forEach { db.execSQL(it) }`。
  Done condition：`migration5To6.startVersion == 5 && migration5To6.endVersion == 6`。
- [x] **5.4** `migrationChain` 列表从 `listOf(m2To3, m3To4, m4To5)` → `listOf(m2To3, m3To4, m4To5, m5To6)`。
  Done condition：`migrationChain.size == 4`，最后一个 endVersion == 6。
- [x] **5.5** AppDatabase 注释更新：体现 schema v6 新增两个视频元数据字段。
  Done condition：注释中提及 v6 / videoFilePath / videoStartedAtWallClock。

## §6 AppModule migrationChain 更新（已加 migration5To6 后自动 cover）

- [x] **6.1** AppModule `databaseModule` 注释更新：体现 migrationChain 覆盖 v2→v6。
  Done condition：注释不再说 v2→v5，改说 v2→v6。

## §7 TelemetrySessionDao 加 updateVideoMetadata

- [x] **7.1** `core/data/src/main/java/com/blazepush/core/data/local/dao/TelemetrySessionDao.kt`：
  新增 `@Query("UPDATE telemetry_sessions SET videoFilePath = :videoFilePath, videoStartedAtWallClock = :videoStartedAtWallClock WHERE sessionId = :sessionId")
  suspend fun updateVideoMetadata(sessionId: String, videoFilePath: String, videoStartedAtWallClock: Long)`。
  Done condition：DAO 含新方法，编译通过。

## §8 TelemetryRepository 加 attachVideoToSession + 扩展 deleteSession

- [x] **8.1** `TelemetryRepository.kt` 新增 `attachVideoToSession(sessionId, videoFilePath, videoStartedAtWallClock)` 方法：
  内部调 `sessionDao.updateVideoMetadata(...)` 并加 `FileLogger.d("attachVideoToSession", "video attached: $videoFilePath")` 日志。
  Done condition：方法存在，签名正确，编译通过。
- [x] **8.2** `deleteSession(sessionId)` 扩展：在现有 binary 删除逻辑之后，加视频删除逻辑：
  - `val videoPath = entity.videoFilePath`
  - 若非 null，`val videoFile = File(videoPath)`
  - canonical-path 白名单列表 `listOf("/telemetry/", "/video/")`
  - 白名单内：`if (videoFile.exists()) { if (videoFile.delete()) FileLogger.d(...) else FileLogger.e(...) } else FileLogger.d(...)`
  - 白名单外：`FileLogger.d("deleteSession", "video path not in whitelist, skip: $videoPath")`
  Done condition：`deleteSession` 含视频删除逻辑 + 3 种 FileLogger 埋点，编译通过。

## §9 单测

- [x] **9.1** `core/data/src/test/java/com/blazepush/core/data/local/AppDatabaseMigrationSqlTest.kt`：
  新增 migration5To6 SQL 断言 tests（`migration5To6 targets v5 to v6` / `migration5To6Sql contains exactly two statements` / `migration5To6Sql adds videoFilePath TEXT` / `migration5To6Sql adds videoStartedAtWallClock INTEGER` / `migration5To6Sql targets telemetry_sessions` / migrationChain size=4 / chain covers v5 to v6 / chain has no gaps v2→v6）。
  Done condition：新 tests 通过。
- [x] **9.2** `core/data/src/test/java/com/blazepush/core/data/local/DeleteSessionVideoWhitelistTest.kt`（新文件）：
  - `videoFilePath in /video/ dir - should be accepted by whitelist`：canonical path 含 `/video/` → allowedPaths.any { cp.contains(it) } == true
  - `videoFilePath in /telemetry/ dir - should be accepted by whitelist`：canonical path 含 `/telemetry/` → true
  - `videoFilePath outside whitelist /etc - should be rejected`：canonical path `/etc/passwd` → false
  - `videoFilePath path traversal attempt - should be rejected`：`../../../etc/shadow` → canonical path 解析后不含白名单 → false
  - `null videoFilePath - should skip video deletion`：直接测试 null 守卫逻辑
  Done condition：所有测试通过。

## §10 Follow-up backlog

- [ ] **round 3 `camera-recording-and-gps-sync`（待立项）**：
  录制引擎 MUST 在录制首帧回调取 `videoStartedAtWallClock = System.currentTimeMillis()`（与 binary absoluteTsMs 同时钟域）；
  录制结束后调 `TelemetryRepository.attachVideoToSession(sessionId, videoFilePath, videoStartedAtWallClock)` 写入这两个字段。
  详见 design.md Decision 6（跨 round 共享字段 drift 登记）。
