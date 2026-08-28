# room-migration-chain Specification

## Purpose
TBD - created by archiving change restore-strict-migrations-pre-release. Update Purpose after archive.
## Requirements
### Requirement: migration2To3 存在且 SQL 完整

本 round 完成后，`AppDatabase.migration2To3` SHALL 是一个 `Migration(2, 3)` 实例，其 `migrate()` SHALL 执行以下 SQL：

1. `CREATE TABLE IF NOT EXISTS telemetry_sessions`（含 7 列：sessionId/sessionType/startTs/endTs/binaryFilePath/lapCount/bestLapMs）
2. `CREATE TABLE IF NOT EXISTS crossing_events`（含 11 列，含 FK + Index）

#### Scenario: v2 设备升 v5（正例）

v2 设备安装 v5 应用 → `migration2To3` 执行 → `telemetry_sessions` 和 `crossing_events` 表创建成功，原有表数据保留。

#### Scenario: v3 设备升 v5（正例，跳过 2→3）

v3 设备安装 v5 应用 → `migration2To3` 不执行（Room 已选下一步 migration）→ `migration3To4` 和 `migration4To5` 执行，telemetry 数据保留。

#### Scenario: migration2To3Sql 缺 crossing_events 建表（反例）

`migration2To3Sql` 未包含 `CREATE TABLE ... crossing_events` → 测试断言失败：`migration2To3Sql` 必须包含 2 条建表语句 + 1 条建索引语句（size == 3）。

---

### Requirement: migration4To5 存在且处理 crossingWallClockTimestampMs 双状态

`AppDatabase.migration4To5` SHALL 是一个 `Migration(4, 5)` 实例，其 `migrate()` SHALL：

1. 执行 `ALTER TABLE test_records ADD COLUMN maxDeceleration REAL NOT NULL DEFAULT 0.0`
2. 检查 `crossing_events` 是否已有 `crossingWallClockTimestampMs` 列（`PRAGMA table_info(crossing_events)`）；**仅当该列不存在时**执行 `ALTER TABLE crossing_events ADD COLUMN crossingWallClockTimestampMs INTEGER`

#### Scenario: v4 新状态设备（已有 crossingWallClockTimestampMs 列，正例）

设备在 5b9704f 之后安装过 v4，`crossing_events` 已有 `crossingWallClockTimestampMs` 列 → migration4To5 执行时 PRAGMA 检查命中，跳过该 ALTER，不抛异常。

#### Scenario: v4 旧状态设备（未有 crossingWallClockTimestampMs 列，正例）

设备在 5b9704f 之前安装过 v4，`crossing_events` 没有 `crossingWallClockTimestampMs` 列 → migration4To5 执行 ADD COLUMN，列成功添加，后续圈速 wallClock 字段可正常写入。

#### Scenario: migration4To5Sql 缺 maxDeceleration（反例）

`migration4To5Sql` 不包含 `ADD COLUMN maxDeceleration` → 测试断言 `migration4To5Sql.any { it.contains("ADD COLUMN maxDeceleration") }` 失败。

---

### Requirement: migrationChain 覆盖 v2→v5 全部连续跃迁

`AppDatabase.migrationChain: List<Migration>` SHALL 包含且仅包含 `migration2To3`、`migration3To4`、`migration4To5`（共 3 个实例）。

`AppModule.databaseModule` Room builder 调用 `.addMigrations(*AppDatabase.migrationChain.toTypedArray())`。

#### Scenario: chain size 正确（正例）

`migrationChain.size == 3`，版本跃迁连续覆盖（2→3, 3→4, 4→5）。

#### Scenario: 无无参 fallbackToDestructiveMigration（正例）

`.fallbackToDestructiveMigration()` 无参调用不存在于 Room builder 链中；仅保留 `fallbackToDestructiveMigrationFrom(1, 2)`。

#### Scenario: migrationChain 缺 migration4To5（反例）

`migrationChain` 缺少 `migration4To5` → 测试断言 `chain.any { it.startVersion == 4 && it.endVersion == 5 }` 失败。

---

### Requirement: destructiveMigrationFrom(1, 2) 保留

`AppModule.databaseModule` Room builder SHALL 保留 `.fallbackToDestructiveMigrationFrom(1, 2)` 调用（兜底 pre-A56 v1/v2 开发期 schema）。

#### Scenario: v1 设备升 v5（正例，destructive 兜底）

v1 或 v2 设备安装 v5 应用 → tables destructive 重建，没有崩溃（pre-A56 开发期 schema，无 release 用户，数据丢失可接受）。

#### Scenario: v3 设备升 v5（正例，严格 migration）

v3 设备安装 v5 → migrationChain 严格路径执行（migration3To4 + migration4To5），不走 destructive，telemetry 数据保留。

#### Scenario: fallbackToDestructiveMigrationFrom 包含 3 或 4（反例）

Room builder 中 `fallbackToDestructiveMigrationFrom(...)` 的参数包含 3、4 或 5 → 与 migrationChain 中对应 Migration 的 endVersion 冲突，Room build() 时抛 `IllegalArgumentException "Inconsistency detected"`（已踩坑 2026-05-03）。

### Requirement: migration7To8 存在且为 bluetooth_devices 加两个 nullable 列

`AppDatabase.migration7To8` SHALL 是一个 `Migration(7, 8)` 实例,其 `migrate()` SHALL 执行 `migration7To8Sql` 列表中的全部 SQL:

1. `ALTER TABLE bluetooth_devices ADD COLUMN alias TEXT`(nullable,无 DEFAULT)
2. `ALTER TABLE bluetooth_devices ADD COLUMN lastConnectedAtMs INTEGER`(nullable,无 DEFAULT)

`@Database` version SHALL 为 8;`migrationChain` SHALL 包含 migration7To8 且其为链尾(startVersion=7, endVersion=8)。

#### Scenario: v7 设备升 v8(正例)

已安装 v7 库的设备升级 → migration7To8 执行 → bluetooth_devices 多两列,历史行 alias=NULL / lastConnectedAtMs=NULL(语义:无别名/无成功连接记录),原有 address/name 数据保留。

#### Scenario: 两列均为 NULL 的历史行不参与冷启动选择(正例,跨 spec 联动)

migration 后历史行 lastConnectedAtMs=NULL → `getLastConnectedDevice()`(WHERE lastConnectedAtMs IS NOT NULL)不返回该行——不会自动连一台从未在新机制下成功连接过的设备。

#### Scenario: migration7To8Sql 含 NOT NULL DEFAULT 哨兵(反例)

`migration7To8Sql` 中 lastConnectedAtMs 若写成 `NOT NULL DEFAULT 0` → 单测断言失败(断言 SQL MUST NOT 含 `NOT NULL`/`DEFAULT`——盲点 #6 哨兵守护)。

#### Scenario: migrationChain 漏挂 7→8(反例)

`migrationChain` 不含 startVersion=7 && endVersion=8 的实例 → 单测 `migrationChain_containsSevenToEight` 断言失败;真机 v7 升级将因 missing migration 走不到(本工程已移除无参 fallbackToDestructiveMigration,会直接崩溃暴露)。

