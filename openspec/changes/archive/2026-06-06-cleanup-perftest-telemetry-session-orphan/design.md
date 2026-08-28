# Design: cleanup-perftest-telemetry-session-orphan

## Context

PERFORMANCE 测试数据双写(`test_records` + `telemetry_sessions`)是 A56 round 的 baseline 设计且仍 active(W1 `getDataPointsForResult` 消费 telemetry_sessions 读 binary)。删除链路现状(2026-06-06 代码级核实):

- `TestResultRepository.deleteResult`(`TestResultRepository.kt:108-117`):删 `test_records` 行 + 按 `dataFilePath` 删 binary(`/telemetry/` canonicalPath 白名单),**不清 telemetry_sessions**
- `TelemetryRepository.deleteSession(sessionId)`(`TelemetryRepository.kt:251-265`):完整 cascade——`queryBySessionId` null-safe return → `crossingDao.deleteCrossingsBySessionId` → `sessionDao.deleteSession(entity)` → binary 白名单删除 → `deleteVideoFileIfPresent(entity.videoFilePath)`(video-storage-cleanup round 抽的 helper,null/空 skip)
- `TestResultRepository` 构造(`TestResultRepository.kt:30-34`):`(testRecordDao, speedSegmentDao, telemetryRepository)` 三参数——**W1 round 已引入 `TelemetryRepository` 依赖**,DI `AppModule.kt:113` `single { TestResultRepository(get(), get(), get()) }`
- 关联 invariant(A56 写入端):PERFORMANCE 路径 `test_records.dataFilePath` 的 basename(去 `.bin`)与 `telemetry_sessions.sessionId` 等价(同一 `UUID.randomUUID()` 派生)
- `TelemetrySessionDao` 是 interface,**7 个 `FakeTelemetrySessionDao` 测试实现**(清单见 tasks §3),加 abstract 方法必须同步补 stub(v3 #14)
- `BlazePushApplication.onCreate`(`BlazePushApplication.kt:19-39`):`FileLogger.init` + `startKoin`,无现成 CoroutineScope

设计依据 memo:`docs/design/perftest-cascade-orphan-cleanup-deferred.md`(2026-05-02 沉淀,2026-06-06 核实问题原样存在;**方案对比一节因 W1 改变现状需修订**,见 Decision 1)。

## Goals / Non-Goals

**Goals:**

- PERFORMANCE 删除路径与 LAPS 同标准:`deleteResult` 一次调用后,`test_records` 行 / `telemetry_sessions` 行 / binary 文件三处全清
- 存量孤儿(已删记录留下的 PERFORMANCE_TEST 死行)一次性 sweep 清除
- road-test-first 日志兜底:cascade 与 sweep 关键路径 FileLogger 落盘

**Non-Goals:**

- 不改 baseline 双写设计(memo 方案 D 根治路径,代价大且 `getDataPointsForResult` 依赖现状)
- 不动 @Database version / 不写 schema migration(sweep 走运行时 SQL)
- 不动 LAPS 删除链路(`TelemetryRepository.deleteSession` 函数体零修改,仅被复用)
- 不处理视频孤儿文件(`video-segmentation-data-model` round 范围,见对应 memo)

## Decisions

### Decision 1: cascade 复用 `telemetryRepository.deleteSession`(memo 方案 A 修订版),而非直接依赖 DAO(memo 推荐的方案 B)

memo(2026-05-02)推荐方案 B 的核心理由是"方案 A 需引入 `TestResultRepository → TelemetryRepository` 新依赖"。该前提已失效:W1 round(2026-05-04,commit `3c2f2d9`)为 `getDataPointsForResult` 把 `TelemetryRepository` 加进了构造函数。重新评估:

| 方案 | 构造/DI 改动 | cascade 完整性 | 评价 |
|---|---|---|---|
| **A 修订(选定)**:`deleteResult` 内调 `telemetryRepository.deleteSession(sessionId)` | **零**(依赖已在) | 继承 J round 全 cascade:entity + crossing(PERFORMANCE 无 crossing,no-op)+ binary 白名单 + 视频(PERFORMANCE 无视频,no-op);null-safe | 复用已测路径,删除语义单点维护 |
| B(memo 原推荐):构造加 `TelemetrySessionDao` 第四参数,直接调 `sessionDao.deleteSession(entity)` | 构造 + DI + 现有测试 callsite(`LapTelemetryReadersTest.kt:59`)三处改 | 需自行 query + 判 null + 删行;binary 删除靠 `deleteResult` 原逻辑 | memo 写作时合理,现状下纯增加改动面 |
| C:不补 cascade,仅靠周期 sweep | 零 | 用户删除后孤儿在 sweep 间隔窗口内可观察;治标不治本 | 拒绝:memo §3 已列劣势 |

**拒绝 B 的具体理由**:现状下 B 相比 A 多改 3 处(构造/DI/测试 callsite)且复制 cascade 逻辑;memo §5 MUST 条款 6"不引入 cross-repository 依赖"针对的是当时不存在的依赖,如今该依赖已是 baseline 事实,条款失去约束对象。**memo 须随本 round 回标此修订**(v3 #15)。

### Decision 2: sessionId 提取 = dataFilePath basename + UUID regex 验证

`extractSessionIdFromDataFilePath(path)`:`File(path).nameWithoutExtension` 取 basename,UUID regex(`[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}`)验证后才调 `deleteSession`;非 UUID(空 path / 旧格式文件名)返回 null → 跳过 cascade,只走原有 binary 删除。

- 替代:直接拿 basename 不验证 → 拒绝:`deleteSession` 虽 null-safe,但把任意字符串当 sessionId 查库浪费且语义混乱;UUID 验证是 memo §5 MUST 条款 3,防御旧 binary 命名向后兼容
- 替代:在 `test_records` 表加 sessionId 列正向关联 → 拒绝:schema migration,违反 Non-Goal

### Decision 3: cascade 顺序 = 先删主表行 → 再 `deleteSession` → 原 binary 删除保留为兜底

```
testRecordDao.deleteTestRecord(entity)          // 1. 主表行
extractSessionId(...)?.let {
    telemetryRepository.deleteSession(it)        // 2. telemetry_sessions 行 + binary(经 binaryFilePath)
}
原有 dataFilePath 白名单删除逻辑保留              // 3. 兜底:非 UUID 旧记录 / telemetry_sessions 无行时仍删文件
```

- binary 双删语义:步骤 2 与 3 指向同一文件时,3 的 `File.delete()` 返回 false,无异常无害(`canonicalPath` 解析不要求文件存在,与现有代码行为一致)
- 顺序与 J round LAPS cascade 一致(关联先删 / db 后 fs),对齐 memo §5 MUST 条款 4
- 替代:cascade 成功后跳过步骤 3 → 拒绝:增加条件分支,且失去对"telemetry_sessions 无行但文件在"的早期数据兜底

### Decision 4: 存量 sweep = DAO @Query 反向 `NOT EXISTS ... LIKE` + Application 启动协程一次性执行

`TelemetrySessionDao` 新增:

```kotlin
@Query("""
    DELETE FROM telemetry_sessions
    WHERE sessionType = 'PERFORMANCE_TEST'
      AND NOT EXISTS (
        SELECT 1 FROM test_records tr
        WHERE tr.dataFilePath LIKE '%' || sessionId || '%'
      )
""")
suspend fun deletePerftestOrphans(): Int
```

`TelemetryRepository` 暴露 `cleanupPerftestOrphans()` wrapper(FileLogger 记录删除行数);`BlazePushApplication.onCreate` 在 `startKoin` 之后用 `CoroutineScope(SupervisorJob() + Dispatchers.IO)` 调一次。

- SQL 写法:反向 LIKE 是 J round 真机 sanity check 实测写法(2/2 命中孤儿、0 误命中正常行);**MUST NOT** 用 path 前缀 REPLACE 写法(对 `/data/user/<N>/` 多用户路径、厂商 ROM filesDir、未来 path 格式迁移都敏感,有误删正常记录风险)——memo §5.3 反例原文
- 性能:O(N×M) 无索引扫描,当前量级(双位数行)毫秒级;`WHERE sessionType='PERFORMANCE_TEST'` 先过滤,LAP_SESSION 行绝不参与
- 挂点替代:Room migration 内 sweep → 拒绝:不动 schema version(Non-Goal);Records 屏首次组合时惰性调 → 拒绝:UI 生命周期耦合数据维护任务,且用户不开 Records 就永不清理
- 每次启动都跑(非"仅一次"标记):sweep 幂等,空结果毫秒级,省去 DataStore 标记位;若 cascade 修复后理论上永远 0 行,日志可观测验证

### Decision 5: 日志锚点(road-test-first MANDATORY)——分模块取齐

**模块边界约束**(自审发现,future-sector-derivation round P2 先例):`FileLogger` 位于 `feature/test` 模块,core/data 依赖方向上不可达;core/data 既有日志惯例是 `android.util.Log`(`TelemetryRepository.kt:298` `deleteSessionVideo` 同款)。落盘可 pull 的 FileLogger 锚点放在 app 模块调用方:

| 位置(模块) | 手段 | 内容 |
|---|---|---|
| `deleteResult` cascade 分支(core/data) | `Log.d("PerftestCascade", ...)` | sessionId 提取结果(命中/非 UUID skip)+ deleteSession 调用 |
| `cleanupPerftestOrphans`(core/data) | 返回 `Int` 删除行数,内部 `Log.d` | sweep 执行 |
| `BlazePushApplication` sweep 调用处(app,可用 FileLogger) | `FileLogger.d("PerftestCascade", "sweep removed N")` / 异常 `FileLogger.e` | **落盘锚点**:sweep 行数(=0 静默健康 / >0 说明有旧存量或 cascade 漏),路测 adb pull 可见 |

- 替代:core/data 加 logger 抽象注入 → 拒绝:为一条日志引入跨模块抽象,过度设计;Log.d 在连 USB 的 logcat 路测足够,关键量化指标(sweep 行数)已经由 app 层 FileLogger 落盘
- 25Hz 高频路径不涉及,全部 d 级别。

## Risks / Trade-offs

- [sweep 误删正常 PERFORMANCE 行] → `WHERE sessionType='PERFORMANCE_TEST'` + 反向 LIKE(sessionId UUID 36 字符,在 dataFilePath 中出现即关联);单测用"孤儿 + 正常行混合"fixture 锁死 0 误删;LAP_SESSION 行被 WHERE 排除绝不触碰
- [`deleteSession` 内 `deleteVideoFileIfPresent` 对 PERFORMANCE 行误删视频] → PERFORMANCE_TEST 行 `videoFilePath` 恒为 null(无录像链路写它),helper null-safe skip;单测 fixture 显式断言
- [Application 启动协程与 Koin 初始化竞态] → 协程在 `startKoin` 完成后才 launch,`GlobalContext.get()` 取 repository;IO dispatcher 不阻塞主线程冷启动
- [双删 binary 文件的 IO 浪费] → 第二次 `delete()` 返回 false 仅一次 stat 开销,删除是低频用户操作,可忽略
- [7 个 fake DAO 漏补 stub 编译失败] → tasks §3 逐文件列清单,apply 时 `:core:data:compileDebugUnitTestKotlin` 先行验证

## Migration Plan

无 schema migration。部署即生效:首次启动 sweep 清存量孤儿,此后 cascade 保证不再新增。回滚 = revert commit(已被 sweep 的孤儿行不可恢复,但其 binary 文件早已不存在,本就是死数据,无功能影响)。

## Open Questions

(无——方案 / 挂点 / SQL 写法 / 测试边界全部已决,memo 9 章 + 本轮代码核实覆盖。)
