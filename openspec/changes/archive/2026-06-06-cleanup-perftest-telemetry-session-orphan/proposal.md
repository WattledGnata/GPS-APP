# Proposal: cleanup-perftest-telemetry-session-orphan

## Why

PERFORMANCE 测试记录(0-100/100-0)在 baseline 上**双写两张 Room 表**:`test_records`(成绩主表,UI Records → PERFORMANCE 子页数据源)+ `telemetry_sessions`(`sessionType='PERFORMANCE_TEST'`,A56 round `unify-gps-telemetry-persistence` 引入的 GPS 点阵统一持久化 metadata,W1 round `getDataPointsForResult` 至今消费它读 binary)。但删除路径只清一半:`TestResultRepository.deleteResult`(`core/data/src/main/java/com/blazepush/core/data/repository/TestResultRepository.kt:108-117`)只删 `test_records` 行 + binary 文件,**不清 `telemetry_sessions` 同 sessionId 的 PERFORMANCE_TEST 行**,每删一条留一行指向已删文件的孤儿。

数据证据(J round `add-history-deletion` 2026-05-02 华为 8KE0219522008434 真机实测):删 2 条 PERFORMANCE 记录后,`test_records` 5→3 行 ✅、binary 11→9 个 ✅、`telemetry_sessions` 11→11 行 ❌——残留 2 条孤儿(sessionId `6db34ea2-...` / `de30411a-...`,binaryFilePath 指向已不存在的文件)。完整分析见 `docs/design/perftest-cascade-orphan-cleanup-deferred.md`(2026-06-06 盘点核实缺口至今原样存在)。

直接影响轻微(孤儿 ~150 字节/条,现有查询均不消费:PERFORMANCE 子页只读 `test_records`,LAPS 查询带 `WHERE sessionType='LAP_SESSION'`),但删除链路**双标准**:LAPS 的 `TelemetryRepository.deleteSession`(`TelemetryRepository.kt:251-265`)是完整 cascade(crossing_events + entity + binary 白名单 + 视频文件),PERFORMANCE 的 `deleteResult` 是半成品;任何未来的"全 session 列表 / session-id 查询"功能都会踩到孤儿行。属 release 前应还的债,user 2026-06-06 拍板立项(memo §8 触发条件"user 主动开 round")。

## What Changes

- **`TestResultRepository.deleteResult` 补 cascade**:从 `entity.dataFilePath` basename 提取 sessionId(UUID regex 验证,防御非 UUID 命名的旧 binary path 误匹配)→ 调既有 `telemetryRepository.deleteSession(sessionId)` 清 telemetry_sessions 行。**方案选型相对 memo 修订**:memo(2026-05-02)推荐方案 B(直接依赖 `TelemetrySessionDao`)是因为当时方案 A 需要新增 `TestResultRepository → TelemetryRepository` 依赖;W1 round(2026-05-04)已为 `getDataPointsForResult` 把该依赖加进构造函数(`TestResultRepository.kt:33`,DI `AppModule.kt:113` 已是三参数)——**方案 A 现在是零构造改动 + 复用 J round 已测 cascade**(crossing/视频步骤对 PERFORMANCE 行自然 no-op,`deleteSession` 对不存在的 sessionId null-safe return),改选 A。
- **存量孤儿一次性 sweep**:`TelemetrySessionDao` 新增 `deletePerftestOrphans(): Int`(反向 `NOT EXISTS ... LIKE` 写法,J round 真机 sanity check 已验证 2/2 命中 0 误删;**不用** path 前缀 REPLACE 写法——对多用户路径/厂商 ROM/格式迁移敏感,memo §5.3 反例),`BlazePushApplication.onCreate` 启动协程调一次。**不动 @Database version,无 schema migration**。
- **FileLogger 埋点**(road-test-first 模式 MANDATORY):cascade 删除锚点 + sweep 清理行数落盘。
- **测试**:`deleteResult` cascade 新测试(~4 cases:cascade 命中 / telemetry_sessions 无行 no-op / 非 UUID basename 防御 / sweep 孤儿删正常留)+ **7 个 `FakeTelemetrySessionDao` 同步补 `deletePerftestOrphans` stub**(v3 #14:DAO 接口加 abstract 方法波及全部 fake,清单见 tasks §3)。

## Capabilities

### New Capabilities

(无)

### Modified Capabilities

- `history-deletion`: PERFORMANCE 测试记录删除路径的 requirement 变化——删除 SHALL 同时清除 `telemetry_sessions` 中同 sessionId 的 PERFORMANCE_TEST 行(与 LAPS 删除链路同标准);新增存量孤儿启动 sweep 行为。

## Impact

- `core/data/src/main/java/com/blazepush/core/data/repository/TestResultRepository.kt` — `deleteResult` 补 cascade + sessionId 提取 helper(~20 行)
- `core/data/src/main/java/com/blazepush/core/data/local/dao/TelemetrySessionDao.kt` — 新增 `deletePerftestOrphans` @Query(~8 行)
- `core/data/src/main/java/com/blazepush/core/data/repository/TelemetryRepository.kt` — 暴露 `cleanupPerftestOrphans()` wrapper(~6 行,含 FileLogger)
- `app/src/main/java/com/blazepush/BlazePushApplication.kt` — onCreate 启动协程调 sweep(~8 行)
- `core/data/src/test/` — 7 个 `FakeTelemetrySessionDao` 补 stub + 新增 cascade 测试文件
- **零改动**:`TestResultRepository` 构造 / DI 注册 / @Database version / 现有 `LapTelemetryReadersTest.kt:59` 构造 callsite

### 协议兼容性

不涉及 GPS 接收链路 / replay / RaceChrono BLE 公共协议;纯接收端本地 Room 删除链路。

### 复杂度与 review 模式

small(~50 行生产代码 + 测试,2 module 但函数级独立,无 schema migration)→ road-test-first 模式(user 2026-06-06 拍板本批次顺序时默认沿用);未命中强制升级 medium 的 5 例外(无公共协议/无跨 capability ripple/无 Room migration/无新 module/非实施层暴露设计缺陷的派生)。
