# PERFORMANCE 测试记录删除 cascade 不彻底（telemetry_sessions 孤儿行）— 延期立项设计 memo

> ✅ **已消化（2026-06-06，round `cleanup-perftest-telemetry-session-orphan`，commit `3d5b5be`）**：
> cascade + 存量 sweep + 8 fake stub + 8 cases 单测全落地。**方案修订**：实施时选的是
> **方案 A 修订版**（复用 `telemetryRepository.deleteSession`）而非本 memo §3 推荐的方案 B——
> 推荐 B 的前提"方案 A 需新增 TestResultRepository → TelemetryRepository 依赖"已被 W1 round
> （2026-05-04，`getDataPointsForResult` 构造依赖）改变，A 修订版变成零构造改动 + 复用 J round
> 已测 cascade（crossing/视频步骤对 PERFORMANCE 行自然 no-op）。§5 MUST 条款 6"不引入
> cross-repository 依赖"因依赖已是 baseline 事实而失去约束对象。sweep 未走 migration
> （schema 已 v8），改为 BlazePushApplication 启动 IO 协程一次性调用（幂等，每次启动跑）。
> 方案 D（双写设计根治）维持 deferred。详见 archive `2026-06-06-cleanup-perftest-telemetry-session-orphan`。

> **触发场景**：add-history-deletion round（J）2026-05-02 真机验证阶段，对照 db 状态发现 `TestResultRepository.deleteResult(entity)` cascade 不清 `telemetry_sessions` 里同 `sessionId` 的 PERFORMANCE_TEST 行。本 round scope 不动 cascade（proposal § 不做的事 第 8 条已明示），按 CLAUDE.md「延期立项设计 memo 规矩」沉淀本文。
>
> **下次立项 round 名建议**：`fix-perftest-cascade-double-write` 或 `cleanup-perftest-telemetry-session-orphan`。
>
> **状态**：延期立项 memo（未 schedule round）。归档触发：用户主动开 round / Codex review 提到该 baseline 不一致 / 用户报"删了 PERFORMANCE 测试记录但 LAPS Track 统计数还在算它"。

---

## 1. 现状

PERFORMANCE 测试记录在 baseline 设计上**双写两张 Room 表**：

| 表 | Schema | 写入时机 | 角色 |
|---|---|---|---|
| `test_records` | `id` (PK String) / `testTemplateId` / `carModel` / `timestamp` / `totalTime` / `totalDistance` / `dataFilePath` | `TestSessionViewModel` 测试结束 → `TestResultRepository.saveResult(entity)` | "测试记录"语义主表，UI Records → PERFORMANCE 子页 RecentRuns 列表数据源 |
| `telemetry_sessions` | `sessionId` (PK String) / `sessionType='PERFORMANCE_TEST'` / `startTs` / `endTs` / `binaryFilePath` / 其它 4 字段（lapCount/bestLapMs/topSpeedKmh/trackId/trackNameSnapshot 对 PERFORMANCE 路径无意义） | A56 round `unify-gps-telemetry-persistence` 引入：所有 GPS 点阵走 `TelemetryRepository.startSession(PERFORMANCE_TEST)` → `endSession`，metadata 写 telemetry_sessions | binary 文件统一持久化抽象，`/telemetry/<sessionId>.bin` 写入端 |

**关联 invariant**：PERFORMANCE 路径 `test_records.dataFilePath` 末尾 basename 跟 `telemetry_sessions.sessionId` **必然等价**（两边都用同一个 `UUID.randomUUID()` 派生）。具体证据：

```
test_records.dataFilePath = /data/user/0/com.blazepush/files/telemetry/<X>.bin
telemetry_sessions.binaryFilePath = /data/user/0/com.blazepush/files/telemetry/<X>.bin
telemetry_sessions.sessionId = <X>
```

但**删除路径只清 test_records 主表**：

```kotlin
// core/data/.../repository/TestResultRepository.kt:99-111（baseline）
suspend fun deleteResult(entity: TestRecordEntity) {
    testRecordDao.deleteTestRecord(entity)               // ✅ test_records 行删除
    if (entity.dataFilePath.isNotEmpty()) {
        val file = File(entity.dataFilePath)
        if (file.canonicalPath.contains("/telemetry/")) {
            file.delete()                                 // ✅ binary 文件删除
        }
    }
    // ❌ 不动 telemetry_sessions 里同 sessionId 的 PERFORMANCE_TEST 行
}
```

→ 删除完后 `telemetry_sessions` 残留 sessionType=PERFORMANCE_TEST 行，指向已不存在的 binary 文件，没人负责清理。

---

## 2. 数据证据

### 2.1 J round (add-history-deletion) 2026-05-02 真机数据

华为 8KE0219522008434 真机测试，user 删 2 条 PERFORMANCE 测试（5/1 18:47 + 5/1 22:55），删后 db + fs 状态：

```
=== test_records ===                              （删后 3 行，已减少 2 条 ✅）
489a7deb-... brake_100_0  5/2 0:18  → ddadf6ec.bin
044ce474-... acc_0_100    5/2 0:17  → d5597153.bin
f569c927-... acc_0_100    5/2 0:16  → 9a0e8554.bin

=== telemetry_sessions PERFORMANCE_TEST 行 ===     （删后 5 行，**未减少**❌）
ddadf6ec-... PERFORMANCE_TEST  5/2 0:18  → 文件存在 ✅
d5597153-... PERFORMANCE_TEST  5/2 0:17  → 文件存在 ✅
9a0e8554-... PERFORMANCE_TEST  5/2 0:16  → 文件存在 ✅
6db34ea2-... PERFORMANCE_TEST  5/1 22:55 → 文件不存在 ❌（孤儿行）
de30411a-... PERFORMANCE_TEST  5/1 18:47 → 文件不存在 ❌（孤儿行）

=== telemetry/ fs ===                              （删后 9 个 .bin，已减少 2 个 ✅）
ddadf6ec.bin / d5597153.bin / 9a0e8554.bin / 4 个 LAP_SESSION binary
（5/1 18:47 + 5/1 22:55 的 binary 已被 deleteResult 清掉）
```

→ 出现 **2 条 telemetry_sessions 孤儿行**，sessionType=PERFORMANCE_TEST 但 binaryFilePath 指向不存在的文件。

### 2.2 影响范围分析

#### 2.2.1 直接影响：占用 db 行（轻微）

每条孤儿 ~150 字节（sessionId UUID + binaryFilePath 字符串 + 几个 Long/Int 字段），10000 条 = 1.5 MB。**当前不构成存储压力**。

#### 2.2.2 间接影响：query 语义不一致（中度）

`TelemetryRepository.queryAll()` 和 `TelemetrySessionDao.queryAll()` 返回的 list 含 PERFORMANCE_TEST 孤儿行，**目前没有 query 路径会消费它们**（PERFORMANCE 子页只读 `test_records`，LAPS 子页 query 加 `WHERE sessionType='LAP_SESSION'` 过滤）。

但任何未来加的"全 session 列表" / "session-id-based 查询" 都会读到孤儿行，需要每个 callsite 防御 null binary 文件读。

#### 2.2.3 LAPS 跟 PERFORMANCE 双标准（重度）

J round 引入的 `TelemetryRepository.deleteSession(sessionId)` 是干净的 LAPS cascade（删 entity + crossing_events + binary），跟 `TestResultRepository.deleteResult` 形成对照：

| 路径 | 删 db 主表 | 删关联表 | 删 binary | 路径白名单 |
|---|---|---|---|---|
| **LAPS** (`TelemetryRepository.deleteSession`) | ✅ telemetry_sessions | ✅ crossing_events | ✅ /telemetry/ 白名单 | ✅ 严格 |
| **PERFORMANCE** (`TestResultRepository.deleteResult`) | ✅ test_records | ❌ 不清 telemetry_sessions PERFORMANCE_TEST 行 | ✅ /telemetry/ 白名单 | ✅ 严格 |

→ 删除链路**双标准**。LAPS 严谨，PERFORMANCE 半成品。code review 角度可读性差。

---

## 3. 方案对比

| 方案 | 工作量 | 优 | 劣 |
|---|---|---|---|
| **A. `deleteResult` 内追加 `telemetryRepository.deleteSession(sessionId)`** | 小（10 行） | 复用 J round 已有 cascade，删除路径单标准；无 schema 改动 | 引入 `TestResultRepository` → `TelemetryRepository` 直接依赖（当前不依赖），需要 DI 加参数 |
| **B. 在 `TestResultRepository.deleteResult` 内直接调 DAO 清 telemetry_sessions** | 小（5 行） | 无新依赖，DAO 已存在 `deleteSession(entity)` | 跳过 `TelemetryRepository.deleteSession` 的 binary 文件白名单检查，binary 文件还需 `TestResultRepository` 自己处理（已有），**but** 不复用 `crossingDao.deleteCrossingsBySessionId`（PERFORMANCE 路径本来就没 crossing）→ 实际跳过的只是无意义检查 |
| **C. 单独立 `PerftestCleanupRepository.cleanupOrphans()` 后台 sweep** | 中（30 行 + 调度） | 解耦 cascade 端，定期清残留 | 引入新概念 + 调度端依赖 + 用户能在 sweep 间隔窗口内观察到孤儿行；治标不治本 |
| **D. 改 baseline 双写设计**：PERFORMANCE 不再写 telemetry_sessions，仅 test_records 持有 binary 路径 | 大（重写写入 + 测试 + 兼容旧记录） | 根治问题 | 跨 A56 round 反向，需要写入端 + 兼容 + migration 代价高 |

**推荐方案：B**。简单、本地、不引入新依赖、不改 schema。

---

## 4. 推荐方案 + 影响分析

### 4.1 实施核心代码

```kotlin
// core/data/.../repository/TestResultRepository.kt
class TestResultRepository(
    private val testRecordDao: TestRecordDao,
    private val telemetrySessionDao: TelemetrySessionDao,  // 新增依赖
) {
    suspend fun deleteResult(entity: TestRecordEntity) {
        testRecordDao.deleteTestRecord(entity)

        // round fix-perftest-cascade-double-write：cascade 清 telemetry_sessions 孤儿行
        // PERFORMANCE 路径 sessionId 跟 dataFilePath basename 等价（A56 写入端 invariant）
        val sessionId = extractSessionIdFromDataFilePath(entity.dataFilePath)
        if (sessionId != null) {
            telemetrySessionDao.queryBySessionId(sessionId)?.let { sessionEntity ->
                telemetrySessionDao.deleteSession(sessionEntity)
            }
        }

        if (entity.dataFilePath.isNotEmpty()) {
            val file = File(entity.dataFilePath)
            if (file.canonicalPath.contains("/telemetry/")) {
                file.delete()
            }
        }
    }

    private fun extractSessionIdFromDataFilePath(path: String): String? {
        if (path.isEmpty()) return null
        val basename = File(path).nameWithoutExtension  // <sessionId>.bin → <sessionId>
        // sanity check：sessionId 必须是 UUID 格式
        return if (UUID_REGEX.matches(basename)) basename else null
    }

    private companion object {
        private val UUID_REGEX = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}
```

### 4.2 性能分析

- 单条 PERFORMANCE 删除：原 1 次 DAO（`deleteTestRecord`）+ 1 次 fs（`File.delete`） → 改后 1 + 1 + 2 次 DAO（`queryBySessionId` + `deleteSession`） + 1 次 fs。增加 ~5ms（in-memory query + delete）
- 批量删除（用户 1 次操作 1 条）：可忽略
- baseline 已有孤儿行 sweep：通过 migration 一次性清理（见 §5.3）

### 4.3 兼容性

- DI 改动：`TestResultRepository` 构造增加 `telemetrySessionDao` 参数。`AppModule.kt` 已经注册了 `TelemetrySessionDao`，加一行参数即可
- 测试代码：现有 `TestResultRepository` 测试需要更新构造参数（grep `TestResultRepository(` 找全部 callsite，~3-5 处）
- 数据兼容：旧的孤儿行不会自动清理，需要 migration sweep（见 §5.3）

---

## 5. 实施约束（MUST 条款）

1. **MUST 复用既有 `TelemetrySessionDao.deleteSession(entity)` 而非新增 DAO 方法** — 该 DAO 由 J round add-history-deletion 引入，已经过单元测试覆盖
2. **MUST 保留 binary 文件 `/telemetry/` 路径白名单** — 不能因为引入 sessionId 提取就跳过 fs 防御
3. **MUST 用 UUID regex 验证 sessionId 提取的 basename** — 防御非 UUID 命名的旧 binary 文件 path 误匹配（向后兼容）
4. **MUST 保留 cascade 顺序**：先删 test_records → 再删 telemetry_sessions → 再删 binary 文件 — 跟 J round LAPS cascade 顺序保持一致（关联表先删 / db 后 fs）
5. **MUST 的 follow-up sweep migration**（见 §5.3）：清理 baseline 已有孤儿行
6. **MUST NOT 引入 cross-repository 直接依赖** — `TestResultRepository` 不应 import `TelemetryRepository`，仅依赖共享的 `TelemetrySessionDao`（这是方案 B 的核心优点，跟方案 A 区分）

### 5.1 单元测试覆盖

新增 cases on `TestResultRepositoryTest`（如不存在则新建）：

1. `deleteResult - cascades to telemetry_sessions when sessionId extractable`
   - 插入 test_record + telemetry_sessions PERFORMANCE_TEST + binary file
   - deleteResult(entity) → 三处全清 ✅
2. `deleteResult - no-op on telemetry_sessions when sessionId not in db`
   - 插入 test_record（dataFilePath 指向不存在的 telemetry_sessions sessionId）
   - deleteResult(entity) → test_records 删 + telemetry_sessions 不抛 + binary 删（按白名单）
3. `deleteResult - safe when dataFilePath basename is not UUID`
   - 假设 baseline 旧 binary path `<random-string>.bin`
   - extractSessionIdFromDataFilePath 返回 null → 不做 telemetry_sessions 操作

### 5.2 J round 数据验证 cases 复盘

跑完上述 follow-up round 后，J round 的真机验证场景应该额外覆盖：

```
PERFORMANCE 长按删除 →
db: test_records 行删 ✅
db: telemetry_sessions PERFORMANCE_TEST 行删 ✅（新增 cascade）
fs: binary 删 ✅
```

### 5.3 Migration sweep（清理 baseline 孤儿）

加 Room migration v4 → v5（如有 J round 之后的 v4 schema）或者一次性在 `TelemetryRepository.cleanupOrphans()` 函数（app 启动时调一次）。

#### 推荐：sessionId 反向 LIKE 匹配（鲁棒）

```sql
DELETE FROM telemetry_sessions
WHERE sessionType = 'PERFORMANCE_TEST'
  AND NOT EXISTS (
    SELECT 1 FROM test_records tr
    WHERE tr.dataFilePath LIKE '%' || sessionId || '%'
  )
```

**为什么这个写法**（J round 真机 sanity check 已用此查询并 100% 命中预期 2 条孤儿 ✅）：

- 不依赖 path 前缀（`/data/user/0/...` vs `/data/user/<N>/...` vs 厂商定制路径）
- 不依赖 migration 是否改过 dataFilePath 格式（绝对路径 vs 相对路径 vs basename-only）
- sessionId 是 UUID（36 字符），在任何字符串里出现都是足够唯一的 token，不会跟其它字段误命中
- 性能：N 条 telemetry_sessions × M 条 test_records，O(N×M) 无索引扫描；当前数据量级（10-100 条）完全可接受；如果未来量级升到 10000+，可对 `test_records.dataFilePath` 加索引或预 extract sessionId 列

#### 反例：path 前缀 REPLACE（**不要用**）

```sql
-- ❌ 不要用：对设备路径前缀敏感
SELECT REPLACE(REPLACE(dataFilePath, '/data/user/0/com.blazepush/files/telemetry/', ''), '.bin', '')
FROM test_records
```

**风险**：

1. **多用户 / work profile**：`/data/user/<N>/...` 中 N 可能不是 0
2. **厂商 ROM 差异**：某些 ROM 的 `Context.filesDir` 路径不是标准 `/data/user/0/<package>/files`
3. **migration 改格式**：若未来 migration 把 `dataFilePath` 从绝对路径改为相对路径（如 `telemetry/<sessionId>.bin`），REPLACE 串失配
4. **新设备首次跑 sweep**：路径前缀跟代码硬编码不一致 → **误判**正常记录为孤儿（**会误删**）或漏识别真正的孤儿

J round (add-history-deletion) 2026-05-02 真机验证时已经在 §9 sanity check 部分用反向 LIKE 写法证实**2/2 命中**预期孤儿 + **0 误命中**正常记录，结论：反向 LIKE 是稳的，应直接采用。

---

## 6. 与当前 round（J. add-history-deletion）的协同关系

- J round 的 `TestResultRepository.deleteResultById(id)` wrapper 内部调 `deleteResult(entity)` — 上文提到的 cascade 改进**会自动适用** by-id 入口（无需额外改动 J round 引入的 wrapper）
- J round 的 `TelemetryRepository.deleteSession(sessionId)` cascade 链路保持不动 — LAPS 路径已干净
- J round 的单测 `TelemetryRepositoryDeleteSessionTest` / `RecordsHomeScreenLongPressContractTest` 跟该 follow-up 不冲突
- 改造后 `RecordsHomeScreenLongPressContractTest.FORBIDDEN_PATTERNS` 内 `testResultRepository.deleteResult(` / `.deleteResult(entity)` 仍然适用（UI 层禁止直接调 by-entity 入口；by-id wrapper 内部调 by-entity 是 repository 内部细节）

---

## 7. 不并入 J round（add-history-deletion）的理由

1. **scope 控制**：J round proposal § 不做的事 第 8 条已明示"不动 PERFORMANCE 测试记录的删除 repo（`deleteResult` 已存在）"，user 当时拍板。在已通过 review 的 round scope 内回插 cascade 改造会破坏 commit 拓扑
2. **影响面**：cascade 改造涉及 `TestResultRepository` DI 改动 + 可能的 migration sweep + 至少 3-5 处测试 callsite 更新；单独立 round 测试责任清晰，单独 review
3. **跟 baseline A56 双写设计的关系**：根因是 PERFORMANCE 双写 `test_records` + `telemetry_sessions` 的 baseline 设计本身值得 review。本 round 仅做"trim cascade"是治标，不治本（方案 D 才是根治）。立项前可让 user / Codex 在 follow-up round 决定 trim or rewrite

---

## 8. 立项节奏估算

- 1 个 round，0.5-1 个工作日
- proposal / design / specs / tasks 工件 ~30 分钟
- 实施 ~30 分钟（cascade 改造 + 测试更新）
- migration sweep ~15 分钟（包括 device-specific path prefix 处理）
- 单测 ~20 分钟
- 真机验证 ~10 分钟
- Codex review + commit + 合回 ~15 分钟

**单独立项触发条件**（CLAUDE.md 「延期立项设计 memo 规矩」§5）：

- user 主动开 round
- Codex review 在某个 round 提到该 baseline 不一致
- user 报"删了 PERFORMANCE 测试记录但 LAPS Track 统计数还在算它"
- D round（kt-format-cleanup）批量补 KDoc 时顺手发现可以一并改
- baseline 检查 / 数据迁移工具开发时用到 telemetry_sessions 全量数据

---

## 9. 附录：J round 真机验证日志锚点

- 设备：华为 `8KE0219522008434`
- apk：`/Users/wattledgnata/traeProjects/gps-app/.worktrees/add-history-deletion/app/build/outputs/apk/debug/BlazePush_v1.0_debug.apk`
- 删除前：`telemetry_sessions` 11 行 / `test_records` 5 行 / `telemetry/` 11 个 .bin
- 删除后：`telemetry_sessions` 11 行（不变 ❌）/ `test_records` 3 行（少 2 ✅）/ `telemetry/` 9 个 .bin（少 2 ✅）
- 残留孤儿 sessionId：`6db34ea2-9b9d-426d-8781-fb035bc4da67` (5/1 22:55) + `de30411a-c4c7-4859-9ffc-ff621c202b26` (5/1 18:47)
