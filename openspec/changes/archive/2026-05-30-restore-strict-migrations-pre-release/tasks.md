# Tasks

## 1. 勘察与迁移链分析
- [x] 1.1 grep AppDatabase.kt：version=5，显式 Migration 仅 migration3To4
- [x] 1.2 grep AppModule.kt：`.addMigrations(AppDatabase.migration3To4)` + `.fallbackToDestructiveMigration()` 无参
- [x] 1.3 git log 还原版本历史：v1/v2（MIGRATION_1_2 存在但 dc0c011 丢失）；v2→v3（缺）；v3→v4（migration3To4 ✅）；v4→v5（缺，5b9704f 还加了 crossingWallClockTimestampMs 无 bump）
- [x] 1.4 确认迁移策略：保留 destructiveMigrationFrom(1, 2)；补 migration2To3 + migration4To5；移除无参 fallbackToDestructiveMigration()

## 2. AppDatabase.kt 补 Migration 定义
- [x] 2.1 文件：`core/data/src/main/java/com/blazepush/core/data/local/AppDatabase.kt`
  - 新增 `migration2To3Sql: List<String>`（CREATE TABLE telemetry_sessions + crossing_events + index SQL）
  - 新增 `val migration2To3: Migration`（Migration(2, 3)，遍历 migration2To3Sql）
  - 新增 `migration4To5Sql: List<String>`（ALTER TABLE test_records ADD COLUMN maxDeceleration）
  - 新增 `val migration4To5: Migration`（Migration(4, 5)，执行 maxDeceleration ADD COLUMN + PRAGMA 条件执行 crossingWallClockTimestampMs ADD COLUMN）
  - 新增 `val migrationChain: List<Migration> = listOf(migration2To3, migration3To4, migration4To5)`
  - Done condition：`grep 'Migration(2, 3)\|Migration(4, 5)\|migrationChain' AppDatabase.kt` 各命中 1 次 ✅

## 3. AppModule.kt 更新 Room builder
- [x] 3.1 文件：`feature/test/src/main/java/com/blazepush/feature/test/di/AppModule.kt`
  - 将 `.addMigrations(AppDatabase.migration3To4)` 改为 `.addMigrations(*AppDatabase.migrationChain.toTypedArray())`
  - 将 `.fallbackToDestructiveMigration()` 改为 `.fallbackToDestructiveMigrationFrom(1, 2)`
  - 更新 databaseModule 注释（说明本 round 严格迁移策略）
  - Done condition：grep 确认 `fallbackToDestructiveMigration()` 无参形式不存在 ✅；`migrationChain` 命中 1 次 ✅；`fallbackToDestructiveMigrationFrom(1, 2)` 命中 1 次 ✅

## 4. 测试扩展
- [x] 4.1 文件：`core/data/src/test/java/com/blazepush/core/data/local/AppDatabaseMigrationSqlTest.kt`
  - 新增：断言 `migration2To3.startVersion == 2 && endVersion == 3` ✅
  - 新增：断言 `migration2To3Sql` 包含 2 条建表语句（CREATE TABLE telemetry_sessions、CREATE TABLE crossing_events）✅
  - 新增：断言 `migration4To5.startVersion == 4 && endVersion == 5` ✅
  - 新增：断言 `migration4To5Sql.any { it.contains("ADD COLUMN maxDeceleration") }` ✅
  - 新增：断言 `migrationChain.size == 3` + 跃迁连续（2→3, 3→4, 4→5）✅
  - Done condition：`gradle :core:data:testDebugUnitTest --offline` 21 tests / 0 failures ✅

## 5. 编译验证
- [x] 5.1 `gradle :core:data:compileDebugKotlin --offline` → BUILD SUCCESSFUL ✅
- [x] 5.2 `gradle :core:data:testDebugUnitTest --offline` → 21 tests, 0 failures, 0 errors ✅

## 10. follow-up backlog
- [ ] 10.1 `room-test-infrastructure`：引入 `androidx.room:room-testing` 的 MigrationTestHelper instrumentation 测试，验证 v1→v5 顺序 migration 不丢表 + 关键列存在（替代本 round 的 SQL string 自检）
- [ ] 10.2 v4 双状态 `crossingWallClockTimestampMs` 路测验证：v4 旧设备（5b9704f 之前）安装 v5 后 PRAGMA 条件分支生效确认
