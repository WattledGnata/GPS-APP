## Why

**问题溯源**：`smooth-perftest-acceleration-curve` round（c7e5b06）将 Room builder 由 `fallbackToDestructiveMigrationFrom(1, 2)` 改成无参 `fallbackToDestructiveMigration()`，以便 v4→v5（新增 `maxDeceleration` 列）在 debug 阶段快速兜底。commit body 中明确记录"上线前 follow-up：必须补回严格 migration"（backlog §8.9 `restore-strict-migrations-pre-release`）。

**当前 baseline（代码证据）**：
- `AppDatabase` version = 5，显式 Migration 只有 `migration3To4`（v3→v4）
- `AppModule.databaseModule` Room builder：`.addMigrations(AppDatabase.migration3To4)` + `.fallbackToDestructiveMigration()`（无参）
- 风险：v4→v5 任何失败（或未来 v5→v6 migration 缺失）会**静默清空 test_records 和 telemetry_sessions，圈速记录全没**

**用户场景**：pre-release 里程碑前，开发设备（华为 8KE0219522008434）积累了一定圈速数据，若上线时版本跨越触发 destructive fallback，用户数据全失。必须在 release 前建立严格迁移纪律。

## What Changes

1. 补 `migration2To3`（v2→v3）：A56 round（d15a60c）新增 `telemetry_sessions` + `crossing_events` 两张表，当时没有显式 migration。v1/v2 已确认为 pre-A56 开发期 schema（无 release tag 用户），路径 A 方案：v1/v2 保留 `destructiveMigrationFrom(1, 2)`；v2→v3 补显式 Migration（CREATE TABLE 两张表）。
2. 补 `migration4To5`（v4→v5）：`test_records.maxDeceleration`（c7e5b06）+ `crossing_events.crossingWallClockTimestampMs`（5b9704f，在 v4 无 version bump 直接加列，需在 v4→v5 migration 中兜底 ADD COLUMN）。
3. `AppModule` Room builder：添加 `migration2To3` + `migration4To5`，保留 `destructiveMigrationFrom(1, 2)`，**移除无参 `fallbackToDestructiveMigration()`**。
4. `AppDatabase`：补 `migration2To3` + `migration4To5` companion 常量（含完整 SQL），暴露 `migrationChain` 聚合列表供 DI 和单测使用。
5. JVM 单测扩展：断言从 v2 到 v5 每一步 Migration 对象都已注册（startVersion/endVersion 连续，全覆盖到 v5）。

## Impact

- 受影响：`core/data/.../AppDatabase.kt`、`feature/test/.../di/AppModule.kt`、`core/data/src/test/.../AppDatabaseMigrationSqlTest.kt`
- 不改 version 号（仍 5），不改任何 entity 字段
- 不涉及公共协议 / GPS 接收链路 / binary writer
