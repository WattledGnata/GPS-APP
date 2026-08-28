## 现状/数据证据

**AppDatabase.kt（core/data/...local/AppDatabase.kt）**
- `@Database(version = 5)`
- entities：TestRecordEntity、SpeedSegmentEntity、CarModelEntity、BluetoothDeviceEntity、TelemetrySessionEntity、CrossingEventEntity（共 6 张表）
- 显式 Migration：仅 `migration3To4`（Migration(3,4)，3 条 ALTER TABLE telemetry_sessions ADD COLUMN）

**AppModule.kt（feature/test/.../di/AppModule.kt）Room builder 链**
```
.addMigrations(AppDatabase.migration3To4)
.fallbackToDestructiveMigration()   ← 无参，兜底所有 missing migration
```

**版本历史重构（git log 验证）**
| commit | version 变化 | 改动说明 |
|--------|------------|---------|
| 2a041b9 | 创建 v2 | `MIGRATION_1_2` 存在（AppDatabase companion），多模块重构 dc0c011 丢失 |
| dc0c011 | 重构（v2 不变） | `MIGRATION_1_2` 在新多模块 AppDatabase 中未携带；AppModule 用 `fallbackToDestructiveMigration()` |
| d15a60c | v2→v3 | A56 加 `telemetry_sessions` + `crossing_events`，无显式 Migration，走 destructive |
| 5b9704f | **v4（无 bump）** | 加 `crossing_events.crossingWallClockTimestampMs`（nullable），AppModule 未改，仍是 dd01aeb 配置 |
| dd01aeb | v3→v4 | `migration3To4` ADD COLUMN × 3；`fallbackToDestructiveMigrationFrom(1, 2)` 兜底 v1/v2 |
| c7e5b06 | v4→v5 | 加 `test_records.maxDeceleration`，改回无参 `fallbackToDestructiveMigration()` |

**迁移链缺口**
- v1→v2：`MIGRATION_1_2` 历史存在但在 dc0c011 多模块重构时丢失 → 决策保留 `destructiveMigrationFrom(1, 2)`
- v2→v3：**缺口**，需补 Migration（CREATE TABLE × 2）
- v3→v4：`migration3To4` ✅
- v4→v5：**缺口**，需补 Migration（ADD COLUMN × 2，其中 `crossingWallClockTimestampMs` 需检查是否已存在）

---

## Decision 1：v1/v2 采用 destructiveMigrationFrom(1, 2) 保留，不补严格 Migration

**Alternatives**
- A) 补 `migration1To2`（从 2a041b9 复原 MIGRATION_1_2 SQL）+ 补 `migration2To3` → 完整链 1→2→3→4→5
- B) 保留 `destructiveMigrationFrom(1, 2)`，只补 v2→v3 + v4→v5 ← **选定**

**Rationale**
- v1/v2 是 pre-A56 开发期 schema（package `com.race.gps.*`，包名已迁移），无 release tag 用户
- dd01aeb commit body 已明确：「v2 是 pre-A56 开发期 schema，对它 destructive 可接受」
- 补 `migration1To2` 需重构 SQL（DROP/CREATE，破坏性操作），复杂度高，ROI 低
- 拒绝 A：引入不必要的历史 SQL 负担，且 v1 schema 结构已无可考

---

## Decision 2：补 migration2To3（CREATE TABLE telemetry_sessions + crossing_events）

**Alternatives**
- A) 扩大 destructiveMigrationFrom 范围为 `(1, 2, 3)` → 简单但会清空 v3 用户数据 **拒绝**（A56 已有圈速数据积累）
- B) 补 Migration(2, 3)，CREATE TABLE 两张表 ← **选定**

**SQL 推导**（从 d15a60c 实体定义推）
```sql
-- TelemetrySessionEntity（d15a60c）
CREATE TABLE IF NOT EXISTS telemetry_sessions (
    sessionId TEXT NOT NULL PRIMARY KEY,
    sessionType TEXT NOT NULL,
    startTs INTEGER NOT NULL,
    endTs INTEGER NOT NULL,
    binaryFilePath TEXT NOT NULL,
    lapCount INTEGER NOT NULL DEFAULT 0,
    bestLapMs INTEGER
)

-- CrossingEventEntity（d15a60c，含 FK + index）
CREATE TABLE IF NOT EXISTS crossing_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    sessionId TEXT NOT NULL,
    lapIndex INTEGER NOT NULL,
    crossingTimestampMs INTEGER NOT NULL,
    speedKmh REAL NOT NULL,
    gateId TEXT NOT NULL,
    gateType TEXT NOT NULL,
    accepted INTEGER NOT NULL,
    reason TEXT NOT NULL,
    directionScore REAL,
    FOREIGN KEY (sessionId) REFERENCES telemetry_sessions(sessionId) ON DELETE CASCADE
)
CREATE INDEX IF NOT EXISTS index_crossing_events_sessionId ON crossing_events(sessionId)
```

**Rationale**：v3 设备已有 lap session 数据，destructive 会导致用户数据丢失，必须保数据。

---

## Decision 3：补 migration4To5（ADD COLUMN × 2，crossingWallClockTimestampMs 需兜底已有列的 v4 设备）

**背景**：5b9704f 在 v4 schema 内直接加了 `crossingWallClockTimestampMs`（没 version bump）。这意味着：
- 5b9704f **之前**安装的 v4 设备：`crossing_events` 没有 `crossingWallClockTimestampMs` 列
- 5b9704f **之后**安装的 v4 设备：`crossing_events` 已有该列

**Alternatives**
- A) 强制 ADD COLUMN（忽略已有列问题）→ 对"已有列的 v4 设备"migration 会失败，Room 抛异常 **拒绝**
- B) 用 `PRAGMA table_info` 检查是否已有列，已有则跳过 ← **选定**
- C) 扩大 destructiveMigrationFrom 为 `(1, 2, 4)` → 清空 v4 用户数据 **拒绝**（v4 有已积累测试记录）

**Rationale**：方案 B 是 SQLite 常见的幂等 migration 模式，SupportSQLiteDatabase 可查 PRAGMA table_info。

**SQL 推导**
```sql
-- test_records.maxDeceleration（c7e5b06）
ALTER TABLE test_records ADD COLUMN maxDeceleration REAL NOT NULL DEFAULT 0.0

-- crossing_events.crossingWallClockTimestampMs（5b9704f，nullable，仅当列不存在时执行）
-- 通过 PRAGMA table_info 检查，走 Kotlin 条件判断
ALTER TABLE crossing_events ADD COLUMN crossingWallClockTimestampMs INTEGER
```

---

## Decision 4：测试策略——JVM 单测（断言 Migration 注册完整性）

**Alternatives**
- A) MigrationTestHelper（instrumentation test，androidTest）：最完整，验证实际 schema 升级 + 数据不丢
- B) JVM 单测断言 Migration 对象注册完整性（startVersion/endVersion 连续覆盖到 v5）← **选定（本 round）**
- C) 不写新测试

**Rationale**：
- `core/data/build.gradle.kts` 无 `androidx.room:room-testing` 且无 androidTest 基础设施（只有 `testImplementation`）
- room-testing MigrationTestHelper 是 instrumentation test，需真机/模拟器，无法离线编译和在 CI 跑
- 本 round 离线约束（`--offline`），不引入新依赖（未缓存 room-testing 依赖风险高）
- 方案 B 在现有框架内可立即运行，扩展现有 `AppDatabaseMigrationSqlTest.kt`
- 方案 A 作为 follow-up `room-test-infrastructure`（已在 AppDatabaseMigrationSqlTest.kt 注释中登记）

**Risks**
- 方案 B 不能运行时验证实际 schema 升级的数据完整性 → 已由 `migrationChain` 常量 + migration SQL 的人工审查 + 路测兜底
- `crossingWallClockTimestampMs` 两种 v4 状态：migration 中用 PRAGMA 条件判断处理，路测前无法自动验证 → 透明声明

---

## Risks

| 风险 | 缓解 |
|------|------|
| `migration2To3` SQL 与 d15a60c 实际 Room 生成 schema 不一致 | 从 entity 字段定义推导 + 参照 Room 注解 @PrimaryKey / @ForeignKey；路测验证 |
| v4 双状态导致 `migration4To5` 对"已有列"设备失败 | Decision 3 选 PRAGMA 条件跳过 |
| JVM 单测不能捕获 SQL 语法错误 | migration SQL 与 entity 定义双向对齐审查；follow-up room-test-infrastructure 补完整 schema 测试 |
