# Design：session-video-metadata-persist

## 现状勘察（3-class 字段对应表）

### 现有 3-class 结构

| 层 | 类/文件 | 位置 |
|---|---|---|
| Entity（Room） | `TelemetrySessionEntity` | `core/data/.../entity/TelemetrySessionEntity.kt` |
| Domain model（in-memory） | `TelemetrySession` | `core/domain/.../model/TelemetryModels.kt` |
| Mapper | `TelemetrySessionEntity.toDomain()` | `TelemetryRepository.kt`（内联扩展函数，行 346-357） |
| DTO | 无 | — |

### 现有字段对应（Entity vs Domain）

| Entity 字段 | Domain 字段 | 类型 |
|---|---|---|
| sessionId | sessionId | String |
| sessionType | sessionType | TelemetrySessionType（valueOf 转） |
| startTs | startTs | Long |
| endTs | endTs | Long |
| binaryFilePath | binaryFilePath | String |
| lapCount | lapCount | Int |
| bestLapMs | bestLapMs | Long? |
| topSpeedKmh | topSpeedKmh | Double? |
| trackId | trackId | String? |
| trackNameSnapshot | trackNameSnapshot | String? |

**本 round 新增字段需同时修改 Entity + Domain + toDomain() 三处**（见 Decision 2）。

---

## Decision 1：nullable vs sentinel（不用 0/-1 哨兵）

### Alternatives

| 方案 | 说明 | 问题 |
|---|---|---|
| A: nullable（`String?`/`Long?`） | SQL NULL，Room 自动映射 | 调用方 explicit null check；但语义清晰 |
| B: sentinel（`String = ""`/`Long = -1`） | 非 nullable，永远有值 | 旧 session 写空字符串/−1，UI 误判"有视频 / 有时间戳"；#6 盲点 |

**选 A**。理由：`videoFilePath = null` 明确表示"无视频"，`videoStartedAtWallClock = null` 明确表示"未开始录制"。
sentinel `""` 或 `-1` 会让 `videoFilePath.isNotEmpty()` 或 `videoStartedAtWallClock > 0` 等常见守卫表达式产生歧义。
Round 3 录制引擎首帧回调前这两个字段必须为 null，不能有 sentinel 污染。

---

## Decision 2：字段加在哪几层

### 3-class 覆盖范围（#5 盲点自查）

新字段必须覆盖：

1. **`TelemetrySessionEntity`**（Room Entity）：加 `videoFilePath: String? = null` + `videoStartedAtWallClock: Long? = null`
2. **`TelemetrySession`**（domain model）：加同名同类型字段
3. **`TelemetryRepository.toDomain()`**（mapper）：把 Entity 新字段传给 domain constructor

无 DTO，无额外 mapper 层，三处全覆盖。

---

## Decision 3：migration5To6 列定义

Room 对 Kotlin nullable 类型的 SQL 列定义规则：
- `String?` → `TEXT`（无 NOT NULL 约束）
- `Long?` → `INTEGER`（无 NOT NULL 约束）

Migration SQL：
```sql
ALTER TABLE telemetry_sessions ADD COLUMN videoFilePath TEXT
ALTER TABLE telemetry_sessions ADD COLUMN videoStartedAtWallClock INTEGER
```

SQLite `ALTER TABLE ADD COLUMN` 不支持指定 NOT NULL 约束（除非有 DEFAULT），
两列均不加 NOT NULL，与 Room 对 nullable 字段生成的 schema 一致。

---

## Decision 4：attachVideoToSession 设计

### Alternatives

| 方案 | 说明 |
|---|---|
| A: 单方法一次写两字段 `attachVideoToSession(sessionId, videoFilePath, videoStartedAtWallClock)` | 原子写入，round 3 首帧回调后调用 |
| B: 两个独立方法 `setVideoFilePath()` + `setVideoStartedAtWallClock()` | 分两次写，可能产生半写状态 |

**选 A**。理由：视频元数据的两个字段语义上是一个整体（"某次录制"），拆成两方法会产生 `videoFilePath != null && videoStartedAtWallClock == null` 的半写中间态，
回放逻辑需要额外防御。一次写两字段更安全。

方法签名：
```kotlin
suspend fun attachVideoToSession(
    sessionId: String,
    videoFilePath: String,
    videoStartedAtWallClock: Long,
)
```

对应 DAO 新增：
```kotlin
@Query("UPDATE telemetry_sessions SET videoFilePath = :videoFilePath, videoStartedAtWallClock = :videoStartedAtWallClock WHERE sessionId = :sessionId")
suspend fun updateVideoMetadata(sessionId: String, videoFilePath: String, videoStartedAtWallClock: Long)
```

---

## Decision 5：deleteSession 视频白名单扩展

### Alternatives

| 方案 | 说明 |
|---|---|
| A: 扩展白名单 `/telemetry/` → `/telemetry/` 或 `/video/` | 复用现有 canonical-path 模式，两种 path 前缀都允许 |
| B: 独立白名单列表 `val allowedPaths = listOf("/telemetry/", "/video/")` | 同 A 但更可扩展 |

**选 B（列表形式）**。理由：视频目录预期为 `filesDir/video/<sessionId>.mp4`（round 3 约定）。
白名单列表比双 `||` 更可扩展（未来可能加 `/sensor/` 等目录）。

path traversal 防护：`file.canonicalPath.let { cp -> allowedPaths.any { cp.contains(it) } }`，
拒绝路径（如 `../../etc/passwd` 或 `/data/data/other.app/`）不在任一白名单内则不删。

**日志埋点**（road-test-first 必要锚点）：
`core/data` 模块不依赖 `feature/test`（FileLogger 在 feature/test 中）——用 `android.util.Log` 替代，
adb logcat 可查 tag `deleteSession` / `attachVideoToSession`：
- 删视频成功：`Log.d("deleteSession", "deleted video: $path")`
- 视频文件不存在：`Log.d("deleteSession", "video file not found, skip: $path")`
- 删视频失败（delete() 返回 false）：`Log.e("deleteSession", "failed to delete video: $path")`
- 路径白名单拒绝：`Log.d("deleteSession", "video path not in whitelist, skip: $path")`

---

## Decision 6：跨 round 共享字段 drift 登记（#16 盲点）

新增字段的消费方：

| 字段 | 生产方（写入） | 消费方 |
|---|---|---|
| `videoFilePath` | round 3 `camera-recording-and-gps-sync`（录制结束写） | round 3 视频回放（读） + `deleteSession`（删文件，本 round） |
| `videoStartedAtWallClock` | round 3（首帧回调写） | round 3 视频帧对齐（读） |

**round 3 实施时 MUST**：
1. 录制结束后调 `attachVideoToSession(sessionId, videoFilePath, videoStartedAtWallClock)` 写两字段
2. 首帧回调取 `videoStartedAtWallClock = System.currentTimeMillis()`（与 binary `absoluteTsMs` 同时钟域）
3. 读取路径确认两字段均非 null 才展示视频；任一 null 则降级"无视频"UI

---

## Decision 7：AppModule fallbackFrom P2 修正

当前：`fallbackToDestructiveMigrationFrom(1, 2)`

问题：`2` 是冗余。Room 优先找迁移路径，`migration2To3` 已提供完整 v2→v3 路径。
`fallbackFrom(2)` 不会触发（Room 有路径时不走 fallback），但注释 "MUST NOT 含 2、3、4、5" 自相矛盾。

修正：`fallbackToDestructiveMigrationFrom(1)`

Room 2.6.1 不会因为 "某版本既有 migration 又在 fallbackFrom 列表" 而抛 `IllegalArgumentException`——
两者独立：migration 注册与 fallback 列表是各自查询的，运行时 Room 优先找 migration 路径，找不到再查 fallback 列表。
（与 `fallbackToDestructiveMigrationFrom(4)` 不同——后者会因 migration3To4.endVersion=4 与 fallback 列表 4 在 Schema 矩阵中冲突而抛异常，
已在注释"已踩坑 2026-05-03"中记录。）
v2 有 migration2To3 路径，所以 `fallbackFrom(2)` 在 v2→v6 全程不会触发；
去掉后自洽：仅 v1（旧包名，无任何迁移路径）才走 destructive fallback。

**确认依据**（Room 2.6.1 行为）：`DatabaseConfiguration.requireMigration()` 判断逻辑是
"先查 `mMigrationContainer` 找路径，找不到再看 `mAllowedDestructiveMigrations`"，两张表独立查。
含 2 在 fallback 列表但 migration2To3 存在 → 运行时走 migration2To3，fallback 列表的 2 被忽略，不抛异常。

---

## Risks

| 风险 | Mitigation |
|---|---|
| migration5To6 列类型与 Room 自动生成 schema 不一致 | 断言 SQL 含 TEXT / INTEGER + 无 NOT NULL；Room 对 nullable 字段生成 nullable column（已验证 v3→v4 同款 nullable ADD COLUMN 三列） |
| `videoFilePath` 路径拼写被 path traversal 利用 | canonical-path + 白名单列表双重防护；无 raw 字符串拼接 |
| attachVideoToSession 传 sessionId 不存在 | Room UPDATE 不存在行无副作用；不抛，返回 Unit |
| deleteSession 删视频与删 binary 顺序不一致 | 先删 crossing_events → 删 session entity → 删 binary → 删 video；任何 File.delete 失败不影响其他步骤 |
| fallbackFrom 改成 `(1)` 后 v2 设备行为变化 | v2→v6 有完整 migration 链（2→3→4→5→6），fallback 不触发；已在 Decision 7 论证 |
