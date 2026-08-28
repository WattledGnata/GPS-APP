# Specs：session-video-metadata-persist

## REQ-1：TelemetrySessionEntity 新字段

**SHALL** 在 `TelemetrySessionEntity` 加两个 nullable 字段：
- `videoFilePath: String? = null`
- `videoStartedAtWallClock: Long? = null`

**场景**
1. **正例**：新 session insert 时两字段均为 null，不影响现有 `startSession` 调用签名。
2. **正例**：`attachVideoToSession` 成功后 `queryBySessionId` 返回 entity，两字段均非 null。
3. **反例**：旧 session（v5 schema 写入）经 migration5To6 后，两字段应为 null；`entity.videoFilePath` 不得返回空字符串或 `-1`（sentinel 非法）。

---

## REQ-2：Room schema v5 → v6 migration

**SHALL** 在 `AppDatabase` 加 `migration5To6`：
- `startVersion = 5, endVersion = 6`
- SQL：`ALTER TABLE telemetry_sessions ADD COLUMN videoFilePath TEXT`
- SQL：`ALTER TABLE telemetry_sessions ADD COLUMN videoStartedAtWallClock INTEGER`
- 两列均无 NOT NULL 约束（对应 nullable Kotlin 字段）

**SHALL** 把 `migration5To6` 加入 `migrationChain`，使链条覆盖 v2→v6。

**SHALL** 把 `@Database version` 从 `5` 升至 `6`。

**场景**
1. **正例**：`migration5To6.startVersion == 5 && migration5To6.endVersion == 6`。
2. **正例**：`migration5To6Sql` 含两条 `ALTER TABLE telemetry_sessions ADD COLUMN` 语句，一条含 `TEXT`，一条含 `INTEGER`。
3. **反例**：SQL 中不得出现 `NOT NULL`（nullable 字段禁止 NOT NULL 约束）；否则 Room schema 不一致导致 migration 失败。

---

## REQ-3：migrationChain 完整性

**SHALL** 更新后的 `migrationChain` 满足：
- size = 4（2→3, 3→4, 4→5, 5→6）
- v2 到 v6 无间隙，每段 startVersion+1 连续

**场景**
1. **正例**：`migrationChain.any { it.startVersion==5 && it.endVersion==6 }` = true。
2. **正例**：排序后链条 endVersion 连续递增至 6。
3. **反例**：若 `migration5To6` 未加入 `migrationChain`，Room 遇到 v5 设备升级到 v6 时找不到路径，回退 fallback 行为（destructive 或 crash），不可接受。

---

## REQ-4：TelemetrySession domain model + mapper

**SHALL** 在 `TelemetrySession` 加：
- `videoFilePath: String? = null`
- `videoStartedAtWallClock: Long? = null`

**SHALL** 更新 `TelemetryRepository.toDomain()` 中 `TelemetrySession(...)` 构造调用，
传递 `videoFilePath = videoFilePath, videoStartedAtWallClock = videoStartedAtWallClock`。

**场景**
1. **正例**：Entity 有 videoFilePath = "/data/.../video/abc.mp4" → toDomain() 返回 domain.videoFilePath = "/data/.../video/abc.mp4"。
2. **正例**：Entity 两字段均 null → toDomain() 返回 domain 两字段均 null（not 空字符串）。
3. **反例**：若 mapper 未更新，domain.videoFilePath 永远 null 即便 Entity 有值——round 3 回放逻辑无法读到视频路径，静默失败。

---

## REQ-5：attachVideoToSession

**SHALL** 在 `TelemetryRepository` 新增：
```kotlin
suspend fun attachVideoToSession(
    sessionId: String,
    videoFilePath: String,
    videoStartedAtWallClock: Long,
)
```

**SHALL** 对应 DAO 查询更新 `videoFilePath` 和 `videoStartedAtWallClock` 两列。

**SHALL** 若 `sessionId` 不存在，调用无副作用（Room UPDATE 不存在行，不抛异常）。

**场景**
1. **正例**：attachVideoToSession 后 queryBySessionId 返回 entity 两字段非 null。
2. **正例**：对不存在的 sessionId 调用，方法正常返回，不抛。
3. **反例**：不得修改 endTs / startTs / binaryFilePath 等现有字段（单一职责，视频元数据独立更新）。

---

## REQ-6：deleteSession 视频文件白名单删除

**SHALL** `deleteSession` 在删除 binary 文件后，若 `entity.videoFilePath != null`，
尝试删除视频文件，删除前用 canonical-path 白名单校验（允许路径含 `/telemetry/` 或 `/video/`）。

**SHALL** 删除结果用日志埋点（`android.util.Log`，adb logcat 可查 tag `deleteSession`）：
- 成功：`Log.d("deleteSession", "deleted video: $path")`
- 文件不存在：`Log.d("deleteSession", "video file not found, skip: $path")`
- 删除失败（delete() 返回 false）：`Log.e("deleteSession", "failed to delete video: $path")`
- 路径不在白名单：`Log.d("deleteSession", "video path not in whitelist, skip: $path")`
（注：FileLogger 在 feature/test 模块中，core/data 不依赖 feature/test，使用 android.util.Log 保持模块边界。）

**SHALL** 路径不在白名单时不删文件、不抛异常（静默守卫）。

**SHALL** `File.delete()` 失败不影响整体 deleteSession 流程（不抛，继续）。

**场景**
1. **正例**：videoFilePath = `${filesDir}/video/abc.mp4`，canonical path 含 `/video/`，文件存在 → 删除成功，FileLogger.d 含 "deleted video"。
2. **正例**：videoFilePath = null → 跳过视频删除，binary 文件仍正常处理。
3. **正例**：videoFilePath 在 `/video/` 下但文件不存在 → FileLogger.d 含 "not found"，不抛。
4. **反例**：videoFilePath = `/etc/passwd` 不在白名单 → 不删文件，FileLogger.d 含 "not in whitelist"，不抛。
5. **反例**：videoFilePath = `../../data/data/other.app/file`（path traversal）→ canonical 解析后不含 `/video/` 或 `/telemetry/` → 拒绝删除。

---

## REQ-7：AppModule fallbackFrom P2 修正

**SHALL** `fallbackToDestructiveMigrationFrom(1, 2)` 改为 `fallbackToDestructiveMigrationFrom(1)`。

**SHALL** 注释同步更新，反映 v2→v6 全程由 migrationChain 严格覆盖，仅 v1 走 destructive fallback。

**场景**
1. **正例**：修改后代码无编译错误，Room builder 不抛异常。
2. **正例**：注释不再出现"MUST NOT 含 2"与代码含 2 的矛盾。
3. **反例**：不得改为 `fallbackToDestructiveMigration()`（无参版本 fallback 所有版本，会破坏 v3/v4/v5 数据）。
