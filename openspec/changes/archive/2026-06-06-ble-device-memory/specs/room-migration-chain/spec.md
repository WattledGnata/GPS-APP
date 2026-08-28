# room-migration-chain Delta Specification

> Delta for change `ble-device-memory` — 新增 v7→v8 migration requirement。

## ADDED Requirements

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
