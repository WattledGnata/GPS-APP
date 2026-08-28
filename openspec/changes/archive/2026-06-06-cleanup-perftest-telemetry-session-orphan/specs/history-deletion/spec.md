# history-deletion Delta Specification

> 修改 capability(change `cleanup-perftest-telemetry-session-orphan`):PERFORMANCE 删除路径补 telemetry_sessions cascade,与 LAPS 删除链路同标准;存量孤儿启动 sweep。

## ADDED Requirements

### Requirement: TestResultRepository.deleteResult 必须 cascade 清除 telemetry_sessions 同 sessionId 行

`TestResultRepository.deleteResult(entity)` SHALL 在删除 `test_records` 行后,从 `entity.dataFilePath` 的 basename(去 `.bin` 扩展名)提取 sessionId 并用 UUID regex(`[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}`)验证;验证通过 SHALL 调用既有 `telemetryRepository.deleteSession(sessionId)` 完成 telemetry_sessions cascade(构造函数依赖已存在,W1 round 引入)。验证不通过(空 path / 非 UUID 命名)SHALL 跳过 cascade,仅执行原有 binary 白名单删除。原有 `dataFilePath` 的 `/telemetry/` canonicalPath 白名单文件删除逻辑 MUST 保留为兜底(cascade 与兜底双删同一文件时第二次 `delete()` 返回 false,无异常)。**MUST NOT** 绕过 `telemetryRepository.deleteSession` 自行调 DAO 删行(cascade 语义单点维护,J round 已测路径)。

#### Scenario: 正常 PERFORMANCE 记录删除三处全清(正例)

- **WHEN** `test_records` 有记录 R(dataFilePath=`.../telemetry/<uuid>.bin`),`telemetry_sessions` 有同 `<uuid>` 的 PERFORMANCE_TEST 行,binary 文件存在;调用 `deleteResult(R)`
- **THEN** `test_records` 行删除
- **AND** `telemetry_sessions` 该行删除(`queryBySessionId(<uuid>)` 返回 null)
- **AND** binary 文件被删除

#### Scenario: telemetry_sessions 无对应行时静默成功(正例)

- **WHEN** `test_records` 有记录 R,但 `telemetry_sessions` 无同 sessionId 行(早期数据);调用 `deleteResult(R)`
- **THEN** 不抛异常,`test_records` 行 + binary 文件正常删除(`deleteSession` null-safe return)

#### Scenario: 非 UUID basename 跳过 cascade(正例,向后兼容防御)

- **WHEN** 记录 R 的 dataFilePath=`.../telemetry/legacy_data.bin`(basename 非 UUID 格式);调用 `deleteResult(R)`
- **THEN** 不调用 `deleteSession`(sessionId 提取返回 null)
- **AND** `test_records` 行删除 + 原有白名单 binary 删除正常执行

#### Scenario: cascade 不误删其他 session 行(反例)

- **WHEN** `telemetry_sessions` 另有 sessionId=`<uuid-B>` 的 PERFORMANCE_TEST 行与一条 LAP_SESSION 行;调用 `deleteResult(R)`(R 对应 `<uuid-A>`)
- **THEN** `<uuid-B>` 行与 LAP_SESSION 行 MUST 完整保留——若实现误用全表/按 type 删除,本 scenario 断言失败

### Requirement: 存量 PERFORMANCE_TEST 孤儿行必须由启动 sweep 一次性清除

`TelemetrySessionDao` SHALL 新增 `deletePerftestOrphans(): Int`,SQL 形态 MUST 为反向关联检查:

```sql
DELETE FROM telemetry_sessions
WHERE sessionType = 'PERFORMANCE_TEST'
  AND NOT EXISTS (
    SELECT 1 FROM test_records tr
    WHERE tr.dataFilePath LIKE '%' || sessionId || '%'
  )
```

**MUST NOT** 使用 path 前缀 REPLACE 提取写法(对 `/data/user/<N>/` 多用户路径、厂商 ROM filesDir 差异、dataFilePath 格式迁移敏感,有误删正常记录风险——memo §5.3 反例)。`TelemetryRepository` SHALL 暴露 `cleanupPerftestOrphans(): Int` wrapper 返回删除行数;`BlazePushApplication.onCreate` SHALL 在 `startKoin` 之后于 IO 协程调用一次,并以 `FileLogger`(tag=`PerftestCascade`)将行数落盘(core/data 模块内仅用 `android.util.Log`——FileLogger 在 feature/test,依赖方向不可达,见 design Decision 5)。DAO 接口新增方法后,7 个 `FakeTelemetrySessionDao` 测试实现 MUST 同步补 override stub(清单见 tasks §3)。

#### Scenario: 孤儿行被清除(正例)

- **WHEN** `telemetry_sessions` 有 PERFORMANCE_TEST 行 X,`test_records` 无任何 dataFilePath 包含 X.sessionId 的记录;调用 `deletePerftestOrphans()`
- **THEN** X 行删除,返回值 ≥1

#### Scenario: 有引用的 PERFORMANCE 行保留(正例)

- **WHEN** `telemetry_sessions` 有 PERFORMANCE_TEST 行 Y,且 `test_records` 存在 dataFilePath=`.../telemetry/<Y.sessionId>.bin` 的记录;调用 `deletePerftestOrphans()`
- **THEN** Y 行 MUST 保留(反向 LIKE 命中关联)

#### Scenario: LAP_SESSION 行绝不参与 sweep(反例)

- **WHEN** `telemetry_sessions` 有一条 LAP_SESSION 行 Z,其 sessionId 不被任何 test_records.dataFilePath 包含(LAP 路径本就不写 test_records,天然"无引用");调用 `deletePerftestOrphans()`
- **THEN** Z 行 MUST 完整保留——若实现遗漏 `sessionType='PERFORMANCE_TEST'` WHERE 限定,本 scenario 断言失败(LAP 数据被误删是不可接受的数据丢失)

#### Scenario: 混合 fixture 精确清理(正反混合)

- **WHEN** 表内同时有:孤儿 PERFORMANCE 行 ×2、有引用 PERFORMANCE 行 ×1、LAP_SESSION 行 ×2;调用 `deletePerftestOrphans()`
- **THEN** 返回 2,且仅 2 条孤儿删除,其余 3 行保留
