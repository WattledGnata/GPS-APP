## 1. 盘点 + 上下文确认（v3 review §P0#1 锚点强制 grep）

- [x] 1.1 **强制 grep 验证锚点行号**：`grep -n "telemetryRepository.writeCrossing(\|TelemetryCrossingEvent(" feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt`，命中位置必须**仅 1 处**且在 LAP_SESSION 过线检测块内（不在 PERFORMANCE_TEST 触发逻辑或其他无关位置）。如果实际行号 ≠ 891，按实际命中位置改，并在本 task 注明实际行号
- [x] 1.2 grep 全工程 `crossingTimestampMs|TelemetryCrossingEvent` 所有引用，列出现有读写路径（写入：TestSessionViewModel.kt 锚点位置；读取：getCrossings + LapSessionDetailScreen.deriveDetailMetrics + LapDebugExecutionScreen UI 显示等）
- [x] 1.3 确认 `LapDebugExecutionScreen.kt:222-243` UI 显示路径仍用 `crossingTimestampMs`（GPS 协议时间），不能切到 wallClock
- [x] 1.4 确认当前 `AppDatabase` schema 在 v4，本 round 升 v5
- [x] 1.5 grep 全 src/test 内 `TelemetryCrossingEvent(` 命中文件清单，**断言命中数量恰好等于 §5.1 列表（3 个：Test / EndSessionPersist / DeleteSession）**；命中 ≠ 3 时 STOP 与本 round 协调（如 BinaryLapTelemetryRoundTripTest 命中说明该文件被改成构造 crossing，需先研判）
- [x] 1.6 grep `addMigrations` 在 **`feature/test/src/main/java/com/blazepush/feature/test/di/AppModule.kt`**（v3 review 第 3 轮 §P0 修订：路径在 feature/test 模块，core/data 无 di 子目录；DSL 是 Koin `single { Room.databaseBuilder(...).addMigrations(...).build() }` lambda 块）内所有命中位置：
  - 命中**恰好 1 行**（生产代码 line 51 `.addMigrations(AppDatabase.migration3To4)` 单行调用）
  - 该行所在 `databaseModule` 内的 Koin `single { ... }` 块（line 38-54 范围内，单一 single 块）
  - 本 round §3.3 改造方式：把单参数 `addMigrations(migration3To4)` 扩成 `addMigrations(migration3To4, migration4To5)` **同一行同一参数列表**，**禁止**新建第二个 `.addMigrations(...)` 调用或拆到第二个 `single { ... }` 块

## 2. core/domain 加字段（**仅 DTO，不动 in-memory CrossingEvent**）

- [x] 2.1 修改 `core/domain/src/main/java/com/blazepush/core/domain/model/TelemetryModels.kt`：`TelemetryCrossingEvent` data class 加 `val crossingWallClockTimestampMs: Long?` 字段（**nullable**，在 crossingTimestampMs 字段下方，default = null）
- [x] 2.2 **不改** `feature/test/src/main/java/com/blazepush/feature/test/model/laptiming/CrossingEvent.kt`（v3 review §P0#2：in-memory pure domain object，由 LapTimingEngine 纯函数产出，无 wallClock 注入路径；其下游消费方都在同一活跃帧/小时段内做协议时间减法，跨时钟域问题不暴露）
- [x] 2.3 编译验证 `./gradlew :core:domain:compileKotlin` 通过——会暴露 `core/data` `feature/test` 所有 caller 的 missing argument 编译错误

## 3. core/data 加字段 + Room migration

- [x] 3.1 修改 `core/data/src/main/java/com/blazepush/core/data/local/entity/CrossingEventEntity.kt`：加 `val crossingWallClockTimestampMs: Long?` 列（**nullable**，default = null，在 crossingTimestampMs 下方，无 @ColumnInfo 装饰，沿用 entity 现有惯例）
- [~] 3.2 **user 拍板跳过**（2026-05-03，老原则 + debug 期）：不写严格 v4→v5 migration 函数，schema 升级走 destructive fallback。主区 user round `smooth-perftest-acceleration-curve` 已设置 `fallbackToDestructiveMigration()` 无参全开兜底，本 round entity 加 `crossingWallClockTimestampMs` 列在 v4→v5 升级时自动随 destructive 重建表生成。**Follow-up**：上线前补回严格 migration（`restore-strict-migrations-pre-release` round，含 ALTER `crossing_events` 加 wallClock）— 已加 §11.6。原 task 内容保留作为后续立项参考：修改 `core/data/src/main/java/com/blazepush/core/data/local/AppDatabase.kt`：
  - `@Database(version = 5, ...)` 升级
  - 加 `internal val migration4To5Sql: List<String> = listOf("ALTER TABLE crossing_events ADD COLUMN crossingWallClockTimestampMs INTEGER")`（**nullable，无 NOT NULL 约束**；v3 review §P1#1 修订：避免 0 哨兵让未来 UI 用旧数据时误命中全 session 帧）
  - 加 `val migration4To5: Migration = object : Migration(4, 5) { override fun migrate(db) { migration4To5Sql.forEach { db.execSQL(it) } } }`（参照 migration3To4 同款结构 + KDoc 注释）
- [~] 3.3 **user 拍板跳过**（同 §3.2）：本 round 不注册 migration4To5（无该函数）。主区 AppModule 已由 user round 设置 `fallbackToDestructiveMigration()` 无参全开。原 task 保留：修改 **`feature/test/.../di/AppModule.kt`**：line 51 `.addMigrations(...)` 加 migration4To5（**禁用**——会与 destructive fallback 冲突，Room build() 抛 IllegalArgumentException）
- [x] 3.4 修改 `core/data/src/main/java/com/blazepush/core/data/repository/TelemetryRepository.kt`：
  - `writeCrossing` 内 `CrossingEventEntity(...)` 构造表达式加 `crossingWallClockTimestampMs = event.crossingWallClockTimestampMs,`
  - `CrossingEventEntity.toDomain()` 加 `crossingWallClockTimestampMs = crossingWallClockTimestampMs,`
- [x] 3.5 编译验证 `./gradlew :core:data:compileDebugKotlin` 通过

## 4. feature/test 写入路径

- [x] 4.1 修改 `feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt`（构造 `TelemetryCrossingEvent` 调用 `repository.writeCrossing` 的位置；按 §1.1 grep 命中的实际行号——预期 line 891 附近，但行号会随其他 round 合回漂移，**MUST 用 grep 命中而非硬编码行号**）：在 `crossingTimestampMs = crossing.timestampMillis,` 行后加 `crossingWallClockTimestampMs = System.currentTimeMillis(),`（同协程上下文内立即取值，不通过异步路径间接计算——见 spec 第"写入路径 grep gate"Scenario）
- [x] 4.2 编译验证 `./gradlew :feature:test:compileDebugKotlin` 通过

## 5. 修同款 Fake DAO 避免现有测试编译失败（v3 review §P2#1：grep 驱动确保完整性）

- [x] 5.1 按 §1.5 grep 命中清单补字段（实测主区当前命中 3 个文件，**不**包含 BinaryLapTelemetryRoundTripTest——它只构造 TelemetrySample 不构造 TelemetryCrossingEvent）：
  - `core/data/src/test/.../TelemetryRepositoryTest.kt`
  - `core/data/src/test/.../TelemetryRepositoryEndSessionPersistTest.kt`
  - `core/data/src/test/.../TelemetryRepositoryDeleteSessionTest.kt`（J round 引入，v3 review §P2#1 揭示 v2 工件遗漏）
  - 每处 `TelemetryCrossingEvent(...)` 构造表达式都需加 `crossingWallClockTimestampMs = null` default（nullable，default null 不影响测试断言）
- [x] 5.2 编译验证 `./gradlew :core:data:compileDebugUnitTestKotlin :feature:test:compileDebugUnitTestKotlin` 通过

## 6. 单元测试 · 强合流门槛

- [x] 6.1 新文件路径：`core/data/src/test/java/com/blazepush/core/data/repository/CrossingClockRoundTripTest.kt`，复用现有 `BinaryLapTelemetryRoundTripTest` / `TelemetryRepositoryEndSessionPersistTest` 的 Fake DAO + mockito-core mock(Context) pattern
- [x] 6.2 case **A · 双时钟域字段 round trip 映射不漏（精确等，nullable 形态明示）**：测试代码手工构造 `event = TelemetryCrossingEvent(crossingTimestampMs = X, crossingWallClockTimestampMs = Y, ...)`，writeCrossing(event) + getCrossings → 三层断言：(1) `assertEquals(X, retrieved.crossingTimestampMs)`（精确等，验证写入路径不污染）(2) `assertNotNull(retrieved.crossingWallClockTimestampMs)`（验证 toDomain 映射不漏；**禁用 `?.let` 形态**否则 null 时 assertion 静默 skip 不 fail）(3) `assertEquals(Y, retrieved.crossingWallClockTimestampMs)`（精确等，测试场景下 wallClock 是手工注入值不需 100ms 容差）
- [x] 6.3 case **B · per-lap segment readLapSamples 用 wallClock 窗口命中**：startSession 后从 `repository.activeSessionStartTs` query 拿 T1（== header.startTs，由 fix-lap-binary-ts-hygiene round 锁定同源）+ 写 N=100 帧 samples（每 40ms 一帧，tsDeltaMs=0..3960）+ writeCrossing(c1, wallClock=T1+1000) + writeCrossing(c2, wallClock=T1+3000) + endSession → readLapSamples(filePath, T1+1000, T1+3000) 返回 50±1 帧（端点 boundary 容差 = LapTelemetryReader.kt:39 闭区间端点取整）
- [x] 6.4 case **C1 · 极端偏差反例（0 命中锁死）**：构造 wallClock = T1+1000, T1+3000，protocolTs = T1+1000+1_000_000_000（~16 分钟偏移，模拟跨小时切换 / simulator 重启的协议时间跳变），readLapSamples(filePath, c[0].protocolTs, c[1].protocolTs) 返回 0 帧
- [x] 6.5 case **C2 · 小偏差错位反例（silent failure 锁死，v3 review §P1#3 揭示）**：构造 wallClock = T1+1000, T1+3000，protocolTs = wallClock + 1500（典型 GPS clock skew）。两次读取对比：
  - `readLapSamples(filePath, c[0].crossingWallClockTimestampMs, c[1].crossingWallClockTimestampMs)` 返回 50±1 帧（正确）
  - `readLapSamples(filePath, c[0].crossingTimestampMs, c[1].crossingTimestampMs)` 返回**不等于** wallClock 集合的样本（数量差非 0；典型情况是窗口偏移 1500ms 后命中错的样本子集）
  - 此 case 锁死"小偏差不会让窗口空，但会命中错误样本"的 silent failure 模式
- [x] 6.6 case **D · 写入路径 grep gate（v3 review §P0#3 重写）**：扩展或新建 grep test：
  - 在 `TestSessionViewModel.kt` 内 grep `crossingWallClockTimestampMs\s*=\s*System\.currentTimeMillis\(\)` 命中**恰好 1 次**
  - 命中行号位于 `telemetryRepository.writeCrossing(` 调用上方 ≤30 行（确保写入路径就在 LAP_SESSION 锚点位置，不被错位插入到 PERFORMANCE_TEST 路径或其他无关位置）
- [x] 6.7 case **D' · 跨文件逃逸 grep gate（v3 review v3 §P1 实现指南补全）**：
  - **测试代码归属模块**：`feature/test/src/test/.../viewmodel/CrossingWallClockEscapeContractTest.kt`（同模块自检 src/main 源码）
  - **路径解析（v3 review v4 §C#2 修订）**：**禁止用裸字面量相对路径** `Paths.get("feature/test/src/main")` —— Gradle 跑 `:feature:test:testDebugUnitTest` 时 working dir = `feature/test/`，相对路径解析失败。MUST 复用现有 `projectRoot()` helper 惯例（参见 `feature/test/src/test/.../repository/PresetTrackAssetTest.kt:26-32`）：从 `javaClass.protectionDomain.codeSource.location.toURI()` 起步往上找 `settings.gradle` / `settings.gradle.kts` 找到 repo root，再拼 `feature/test/src/main` 子路径
  - **实现 pattern**：`Files.walk(File(projectRoot(), "feature/test/src/main").toPath())` 递归遍历 + filter `path.toString().endsWith(".kt")` + 排除条件 (a) `!path.toString().contains("/src/test/")` (b) `!path.toString().contains("/.worktrees/")`（仓库内多 worktree 副本会产生额外命中）
  - **import 清单**：`java.io.File` + `java.nio.file.Files` + `java.nio.file.Path` + `kotlin.streams.toList`（或等价 Java Stream collector）
  - **断言三层**（防扫错路径假性绿）：
    1. **下界断言**：扫描到的 .kt 文件总数 ≥ 50（防 path 拼错或 worktree 内跑导致 0 文件假性绿）
    2. **命中文件恰好 1 个**：`TestSessionViewModel.kt`，路径 endsWith `feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt`
    3. **明示禁止文件不命中**（任一命中即 fail，作为正向 sanity check）：`LapDebugExecutionScreen.kt` / `LapSessionDetailScreen.kt` / `TestSessionUiState.kt`（如存在）/ 全 ui/screen 与 ui/tracktech 子目录文件
  - **regex pattern**：`crossingWallClockTimestampMs`（精确字段名，不模糊）
- [~] 6.8 case **E · Migration v4→v5 SQL 自检** **user 拍板跳过**（同 §3.2/§3.3，无严格 migration 可断言）；spec scenario 7 同步声明 honestly 跳过。Follow-up：`restore-strict-migrations-pre-release` round 重新加。原 task 保留：
  - **case E1 (core/data/src/test/.../AppDatabaseMigrationSqlTest.kt)**：assert `migration4To5Sql.size == 1`，恰好包含 `ALTER TABLE crossing_events ADD COLUMN crossingWallClockTimestampMs INTEGER`（**无 NOT NULL，无 DEFAULT**）；`migration4To5` Migration 对象 startVersion=4 + endVersion=5
  - **case E2 (feature/test/src/test/.../di/AppModuleMigrationRegistrationTest.kt 新文件)**：用 file IO 读 `feature/test/src/main/java/com/blazepush/feature/test/di/AppModule.kt` 源码（同模块），grep `\.addMigrations\(` 行**恰好 1 行命中**；该行同时包含 `migration3To4` 和 `migration4To5` 引用（同一行同一参数列表，确保同一 `single { ... }` 块内注册）。**注**：跨 module 验证不再合理（core/data test classpath 无 feature/test src），只能在 feature/test 模块内自验
    - **路径解析**（v3 review v4 §C#3 同款修订）：**禁止裸字面量相对路径**；MUST 用 `projectRoot()` helper（同 case D'，参 `PresetTrackAssetTest.kt:26-32`）：`File(projectRoot(), "feature/test/src/main/java/com/blazepush/feature/test/di/AppModule.kt").readText()`
  - **替代方案**（如 file IO 复杂）：把 case E2 改成 §10 review 阶段 user 人工 checklist 项（"verify AppModule.kt:51 单行包含 migration3To4 + migration4To5"），保 review gate 但不写自动化测试。本 round 默认走自动化（case E2），review 时如发现实现复杂可拍板降级
- [x] 6.9 case **F · v4→v5 真实 row migration 自动化验证（spec Scenario 5b · deferred）**：仅记录到 §11.4 follow-up，不在本 round 实施（需要 `androidx.room:room-testing` MigrationTestHelper + Robolectric，design §5 决策不引入这些库；继承 C round v3→v4 同款延期项 `room-test-infrastructure`）
- [x] 6.10 跑测试 `./gradlew :core:data:testDebugUnitTest --tests "*CrossingClockRoundTrip*" --tests "*AppDatabaseMigrationSql*"` 全绿——本测试套件是合流强门槛

## 7. grep 自检（spec 第 4 + 第 6 个 Scenario）

- [x] 7.1 grep `crossingWallClockTimestampMs` 在 `feature/test/src/main/.../ui/` 范围内**结果为空**（UI 显示文件不该消费 wallClock）
- [x] 7.2 grep `crossingWallClockTimestampMs\s*=\s*System\.currentTimeMillis\(\)` 在 `TestSessionViewModel.kt` 内必须命中（同步取值，不走异步）
- [x] 7.3 grep `crossingWallClockTimestampMs.*launch\|withContext\|delay` 在 `TestSessionViewModel.kt` 内**结果为空**（不得通过异步路径间接计算）

## 8. OpenSpec 工件自检

- [x] 8.1 跑 `openspec validate fix-lap-crossing-clock-hygiene --strict` 通过
- [x] 8.2 工件四件齐全：proposal / design / specs/binary-telemetry-storage/spec.md / tasks
- [x] 8.3 deferred memo 引用：本 round 的 design.md 引用 `docs/design/lap-crossing-clock-hygiene-deferred.md`，proposal Why 段也引用
- [x] 8.4 同步更新 `docs/implementation-design/parallel-change-collab.md` §5 row（追加新 round 行 / 状态推进）

## 9. 真机不回归（user 拍板时机）

> A round 跳过先例：本 round 同样是 baseline 数据层修复，下游 UI 当前不消费 → 真机看不到立即可见证据。建议 user 拍板跳过；如做需先告知 + 等授权（看板 §4.2 串行规则）。

- [~] 9.1-9.4 **user 拍板跳过**（2026-05-03，A round 同款先例）：本 round 是 baseline 数据层修复，下游 UI 不消费 wallClock（spec scenario UI 不回归 grep gate 锁定），真机看不到效果。功能正确性证据由 §6 6 cases 单测全绿覆盖（commit 历史可审）+ L2 review 0 P0/P1 覆盖。如未来 per-lap UI 落地（fix-lap-crossing-clock-hygiene 解锁的能力），独立 round 立项时配套真机端到端验证

## 10. 合回 + Codex review

- [x] 10.1 worktree 内 commit（Conventional Commits：`feat(telemetry): add crossing wall-clock timestamp for per-lap segment readLapSamples`）
- [x] 10.2 ff-only 合回 `feature/track-tech-v2`
- [x] 10.3 主区合回态编译 + 测试验证
- [x] 10.4 提醒 user 触发 Codex review
- [x] 10.5 Codex review 通过后 user 拍板 push 顺序 + 执行 push
- [x] 10.6 worktree + 本地分支清理
- [x] 10.7 OpenSpec 归档 → `archive/YYYY-MM-DD-fix-lap-crossing-clock-hygiene/`

## 11. follow-up backlog

- [x] 11.1 （**独立 round** · 已沉淀 deferred memo）`fix-perftest-binary-ts-hygiene`（A round §8.4 转移过来）—— PERFORMANCE_TEST 路径同 anchor + 时钟域 bug。**独立 round，不依赖本 round 任何产出**（PERFORMANCE_TEST 没有过线事件概念，跟 crossing wallClock 字段无关），可在任意时机立项（甚至可以与本 round 并行实施）。设计文档：`docs/design/perftest-binary-ts-hygiene-deferred.md`，下次 `/opsx:ff fix-perftest-binary-ts-hygiene` 直接立项
- [x] 11.2 （UI round）Analysis Mode 单圈轨迹 / Records LAPS sub-tab 圈分段 / sector 分段 等 per-lap UI 消费方——本 round 解锁的数据层能力的下游消费，由独立 UI round 立项
- [x] 11.3 （cleanup round）`LapSessionDetailScreen` 把 `readPerformanceSamples` quick fix 回切回 `readLapSamples(filePath, session.startTs, session.endTs)`，配合真机端到端验证 TOP SPEED（A round §8.1 转移过来）
- [x] 11.4 （test infra）`room-test-infrastructure` —— 引入 androidx.room:room-testing + Robolectric 跑完整 v3→v4→v5 row migration 自动化验证（替代 SQL string 自检），与 C round 已声明的同款延期项合并立项；本 round spec Scenario 5b 明示推到此 round 验证
- [x] 11.5 （**独立 round** · v3 review §P1#4 新沉淀；v3 review v3 §P1#2 fallback 表达式修订）`migrate-lap-duration-derivation-to-wallclock` —— 切 `TelemetryRepository.endSession` line 161-164 + `LapSessionDetailScreen.deriveDetailMetrics` line 471-515 的 lap durations 派生从协议时间减法到 wallClock 减法。**依赖本 round 合回**（复用 wallClock 字段）。修复 GPS 跨小时切换时 bestLapMs 产生负数 / 1+ 小时错乱值的 silent failure。

- [x] 11.6 （**独立 round · 上线前必做** · 2026-05-03 user 拍板跳过 §3.2/§3.3/§6.8 时新沉淀）`restore-strict-migrations-pre-release` —— 上线前补回 schema migration 严格函数（包括本 round 应有的 `migration4To5` ALTER `crossing_events` ADD COLUMN crossingWallClockTimestampMs INTEGER + user round smooth-perftest 应有的 `migration4To5` ALTER `test_records` ADD COLUMN maxDeceleration REAL + 重新组织 `fallbackToDestructiveMigrationFrom(1, 2)` 不含 4 避免与 strict migration 冲突）+ 配 case E1/E2 SQL 自检 + AppModule 注册自检。**强制 release 前必做**（debug 期 fallback 接受数据丢失，release 期不接受）
  - **改动文件清单**（apply 时按实际行号）：
    - `core/data/.../repository/TelemetryRepository.kt` endSession 内 `acceptedSF.zipWithNext { a, b -> ... }`
    - `feature/test/.../ui/tracktech/LapSessionDetailScreen.kt:474-480` `deriveDetailMetrics`
  - **fallback 表达式 · per-pair 二选一（v3 review v3 §P1#2 修订）**：**禁止**用独立 elvis `(b.wallClock ?: b.protocol) - (a.wallClock ?: a.protocol)` —— 该形态在混合 row 时会退化成跨时钟域减法（即本 round 要修的核心 bug 复发）；正确形态：
    ```kotlin
    val durations = acceptedSF.zipWithNext { a, b ->
        if (a.crossingWallClockTimestampMs != null && b.crossingWallClockTimestampMs != null) {
            b.crossingWallClockTimestampMs - a.crossingWallClockTimestampMs    // 全 wallClock pair
        } else {
            b.crossingTimestampMs - a.crossingTimestampMs                       // 任一 null 则整 pair fallback 协议时间
        }
    }
    ```
  - **混合 session 处理**（一些 row 有 wallClock 一些 null —— 仅 v4→v5 升级"碰巧跨界"出现）：fallback 已保证不崩 + 不跨时钟域；**额外加 ERROR 级 logcat 报警**：`FileLogger.e(TAG, "mixed-clock-domain crossings detected for session $sessionId, lapDuration fallback to protocolTs")`，方便诊断。生产中"修复后启动的 session 全部 row 有 wallClock；旧 session row 都没有"，混合是异常态
  - **测试 case**：(a) 跨小时 wallClock 减法返回正确值（vs 协议时间 baseline 错乱）；(b) wallClock=null 旧数据 row fallback 到协议时间无崩溃；(c) **混合 session 异常态**：3 row 中 row[0] wallClock=null + row[1] wallClock=非null + row[2] wallClock=非null，断言 lap0(row0→row1) 走 protocolTs fallback + lap1(row1→row2) 走 wallClock + ERROR logcat 报警出现；(d) ERROR logcat 报警在全 wallClock 或全 null session 不出现（仅混合时触发）
  - **预估**：~20 行代码（fallback + ERROR 日志）+ 4 个测试 case + 1 天工件 + 1 天实施 + 0.5 天 review
