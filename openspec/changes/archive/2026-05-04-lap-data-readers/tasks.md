## 1. 锚点 verify + 看板登记（apply 起步）

- [x] 1.1 跑 `git rev-parse HEAD` 确认 worktree HEAD == `e2a42a1`（Phase 0 exit）；不一致则停手回主区研判 rebase 影响
- [x] 1.2 跑 `grep -n "var activeSessionStartTs\|crossingWallClockTimestampMs" core/data/src/main/java/com/blazepush/core/data/repository/TelemetryRepository.kt core/data/src/main/java/com/blazepush/core/data/local/entity/CrossingEventEntity.kt core/domain/src/main/java/com/blazepush/core/domain/model/TelemetryModels.kt` 确认依赖前提存在（A round + §8.3 已合）；done = 命中 `var activeSessionStartTs: Long?`（TelemetryRepository.kt 第 47 行附近）+ `val crossingWallClockTimestampMs: Long?`（CrossingEventEntity 第 41 行附近 + TelemetryModels 第 68 行附近）
- [x] 1.3 跑 `grep -nE "^\s*single \{ TelemetryRepository\(|^\s*single \{ TestResultRepository\(" feature/test/src/main/java/com/blazepush/feature/test/di/AppModule.kt` 锁定 Koin DSL 实际 line 号（baseline TelemetryRepository = line 92，TestResultRepository = line 89；rebase 后会漂移）；记录实际 line 用于 5.x 修改
- [x] 1.4 跑 `grep -n "val dataFilePath" core/data/src/main/java/com/blazepush/core/data/local/entity/TestRecordEntity.kt core/domain/src/main/java/com/blazepush/core/domain/model/TestModels.kt` 确认 G round `dataFilePath` 字段存在且 default `""`；done = TestRecordEntity 命中 1 次 + TestModels 命中 2 次（TestResult + TestResultSummary）
- [x] 1.5 在主工作区 `docs/implementation-design/parallel-change-collab.md` §6 共享文件登记表追加 3 条 ongoing 行（W1 同时占用 TelemetryRepository.kt + TestResultRepository.kt + AppModule.kt）；标注 "与 W2/W3/W4 函数级 0 交叉" + 同步 §5 W1 状态改 "推进中"；**MUST** 同步修订看板 §5 W1 行 "独占路径（概要）"列：把 baseline 文字 `core/data/.../repository/TelemetryRepository.kt:getLapTelemetry/getDataPointsForResult 新增方法` → `TelemetryRepository.kt:getLapTelemetry 新增 + TestResultRepository.kt:getDataPointsForResult 新增（D1 决策按真相源分流，详 design.md D1 alternatives A8）`，让看板与 design D1 决策同步

## 2. 数据契约新建（core/domain）

- [x] 2.1 新建 `core/domain/src/main/java/com/blazepush/core/domain/model/LapTelemetry.kt`，按 spec Requirement 3 字段顺序定义 `LapTelemetrySample` / `LapTelemetry` / `PerformanceTelemetry` 三个 data class；KDoc 含 `@author CC` / `@description` / `@date 2026-05-04`；MUST 满足 spec：
   - `LapTelemetrySample.accelerationG: Double? = null`（**MUST** default null，不可改 0.0）
   - `LapTelemetry.lapDurationMs: Long`（注释明确 == lapEndWallClock - lapStartWallClock）
   - `PerformanceTelemetry.samples: List<LapTelemetrySample>`（**MUST** 复用 LapTelemetrySample 类型，不新建 PerformanceSample）
   - 三类全部 data class，无 default constructor 之外构造方法（让 JVM 可以反射生成）
- [x] 2.2 跑 `:core:domain:compileDebugKotlin` 编译通过（done = exit 0）

## 3. TelemetryRepository.getLapTelemetry 实现（core/data）

- [x] 3.1 在 `core/data/src/main/java/com/blazepush/core/data/repository/TelemetryRepository.kt` 末尾（`readLapSamples` 函数之后、`telemetryFile` private helper 之前；apply 时按 grep 实际位置）追加 `suspend fun getLapTelemetry(sessionId: String, lapIndex: Int): LapTelemetry?` 实现，逻辑：
   1. `val entity = sessionDao.queryBySessionId(sessionId) ?: return null`
   2. `val crossings = crossingDao.queryBySessionId(sessionId)`
   3. `val acceptedSF = crossings.filter { it.gateType.equals("StartFinish", ignoreCase = true) && it.accepted }.sortedBy { it.crossingWallClockTimestampMs ?: Long.MAX_VALUE }`（null 排到末尾避免污染配对）
   4. `if (lapIndex < 0 || lapIndex + 1 >= acceptedSF.size) return null`
   5. `val lapStartWallClock = acceptedSF[lapIndex].crossingWallClockTimestampMs ?: return null`
   6. `val lapEndWallClock = acceptedSF[lapIndex + 1].crossingWallClockTimestampMs ?: return null`
   7. `val rawSamples = withContext(Dispatchers.IO) { runCatching { LapTelemetryReader.read(entity.binaryFilePath, lapStartWallClock, lapEndWallClock) }.getOrDefault(emptyList()) }` —— `withContext(Dispatchers.IO)` 包 IO 调用，`runCatching` 防 IOException 抛
   8. `val samples = rawSamples.map { sample -> LapTelemetrySample(absoluteTsMs = entity.startTs + sample.tsDeltaMs, elapsedMsInLap = entity.startTs + sample.tsDeltaMs - lapStartWallClock, lat = sample.lat, lon = sample.lon, speedKmh = sample.speedKmh, bearingDeg = sample.bearingDeg, accelerationG = null, flags = sample.flags) }` —— **MUST** 透传 `flags = sample.flags`（spec Requirement 1 normative 锁定）`
   9. `return LapTelemetry(sessionId = sessionId, lapIndex = lapIndex, lapStartWallClock = lapStartWallClock, lapEndWallClock = lapEndWallClock, lapDurationMs = lapEndWallClock - lapStartWallClock, samples = samples, sectorBoundaries = listOf(lapStartWallClock), trackId = entity.trackId, trackNameSnapshot = entity.trackNameSnapshot)`
   - **MUST** import `com.blazepush.core.domain.model.LapTelemetry` + `LapTelemetrySample`（包路径已固定）
   - done condition: `:core:data:compileDebugKotlin` 编译通过；spec Requirement 1 / 4 全部 scenario 在单测中验证
- [x] 3.2 **MUST NOT** 改既有任何方法签名（grep gate verify：`git diff core/data/src/main/java/com/blazepush/core/data/repository/TelemetryRepository.kt` 仅含追加 + import 行，0 既有方法 line 修改）；**MUST NOT** 引入 `TestResultRepository` / `TestRecordDao` / `TestRecordEntity` / `TestResult` / `TestResultSummary` 任何 import（spec Requirement 5 锁定）

## 4. TestResultRepository.getDataPointsForResult 实现（core/data）

- [x] 4.1 改 `core/data/src/main/java/com/blazepush/core/data/repository/TestResultRepository.kt` 构造函数（baseline line 26 附近）：追加 `private val telemetryRepository: TelemetryRepository`（第 3 个 constructor 参数，紧跟 `speedSegmentDao`）；done = `class TestResultRepository(private val testRecordDao: TestRecordDao, private val speedSegmentDao: SpeedSegmentDao, private val telemetryRepository: TelemetryRepository)` 形态
- [x] 4.2 在 `TestResultRepository.kt` 末尾追加 `suspend fun getDataPointsForResult(testId: String): PerformanceTelemetry?` 实现，逻辑：
   1. `val entity = testRecordDao.getTestRecordById(testId) ?: return null`
   2. `if (entity.dataFilePath.isEmpty()) return null` —— **MUST** 显式判空，不传给 readPerformanceSamples 让它判
   3. `val rawSamples = withContext(Dispatchers.IO) { runCatching { telemetryRepository.readPerformanceSamples(entity.dataFilePath) }.getOrDefault(emptyList()) }`
   4. `if (rawSamples.isEmpty()) return null` —— 空 binary 视为读取失败（与 binary 文件缺失同语义降级）
   5. `val testStartWallClock = entity.timestamp`
   6. `val testEndWallClock = entity.timestamp + (rawSamples.lastOrNull()?.tsDeltaMs ?: 0L)`
   7. `val samples = rawSamples.map { sample -> LapTelemetrySample(absoluteTsMs = testStartWallClock + sample.tsDeltaMs, elapsedMsInLap = sample.tsDeltaMs, lat = sample.lat, lon = sample.lon, speedKmh = sample.speedKmh, bearingDeg = sample.bearingDeg, accelerationG = null, flags = sample.flags) }` —— **MUST** 透传 `flags = sample.flags`（spec Requirement 2 normative 锁定）`
   8. `return PerformanceTelemetry(testId = testId, testStartWallClock = testStartWallClock, testEndWallClock = testEndWallClock, samples = samples)`
   - **MUST** import `com.blazepush.core.domain.model.LapTelemetrySample` + `PerformanceTelemetry` + `kotlinx.coroutines.Dispatchers` + `kotlinx.coroutines.withContext`
- [x] 4.3 改 `feature/test/src/main/java/com/blazepush/feature/test/di/AppModule.kt`（baseline `TestResultRepository = line 89`，`TelemetryRepository = line 92`，TestResultRepository 在 TelemetryRepository **之前**声明；按 1.3 grep 结果为准）：把 `single { TestResultRepository(get(), get()) }` → `single { TestResultRepository(get(), get(), get()) }`；**保留 baseline 行序**（不调整 line 89 / 92 的相对位置，Koin DSL 用懒加载 `get()` 解析依赖，声明顺序不影响构造）；done = AppModule.kt 编译通过 + 启动 app 不抛 NoBeanDefFoundException
- [x] 4.4 跑 `:core:data:compileDebugKotlin :feature:test:compileDebugKotlin :app:compileDebugKotlin` 全绿（done = 三个 task exit 0）

## 5. 单元测试新建（core/data）

- [x] 5.1 baseline fake DAO pattern verify：跑 `find core/data/src/test -name "*.kt" | xargs grep -l "private class Fake.*Dao"` 确认 baseline 6 个既有 test 类各自 `private class` 重复定义 fake DAO 模式；本 round 沿用此 pattern——在 `LapTelemetryReadersTest.kt` 文件内部 **`private class` 重复定义** `FakeTelemetrySessionDao` / `FakeCrossingEventDao` / `FakeTestRecordDao` / `FakeSpeedSegmentDao`（每个 test 类自包含 fixture，不引入 file-level shared fake 类）；**MUST** 跑 `grep -n "abstract\|fun\|suspend fun" core/data/src/main/java/com/blazepush/core/data/local/dao/TestRecordDao.kt` 列出 TestRecordDao 接口所有 abstract / suspend fun 方法签名 + 同步在 file-internal fake 类中 override 全部方法（v3 高频盲点 #14：上游加 abstract 方法后 fake DAO 漏 stub 编译失败）；同样 grep `SpeedSegmentDao` 列签名（speedSegmentDao 测试中可仅 stub `throw NotImplementedError`，本 round 不关心其行为，仅是 TestResultRepository 构造参数 placeholder）；done = `:core:data:testDebugUnitTest` setup 编译通过
- [x] 5.2 新建 `core/data/src/test/java/com/blazepush/core/data/repository/LapTelemetryReadersTest.kt`，复用 `TelemetryRepositoryTest.setup` pattern（temp dir + Fake DAO + real BinaryTelemetryWriter + mock Context），含 cases A-J（spec Requirement 1 / 2 / 3 / 4 / 5 全部 scenario）：
   - **case A**：正常单圈读取——startSession + writeSample × 100（fixture sample 的 `flags` 字段写入非 0 值，如 5）+ writeCrossing × 3（accepted SF，wallClock 形成 2 对）+ getLapTelemetry(s, 0) → 非 null + samples.size > 0 + sectorBoundaries.first() == lapStartWallClock + lapDurationMs > 0 + **`samples.first().flags == 5`**（透传断言锁死 spec Requirement 1 normative "flags 透传"）
   - **case B**：lapIndex 越界 → null（lapIndex = 5 但 pairs.size = 2）
   - **case C**：session 不存在 → null（getLapTelemetry("non-existent", 0)）
   - **case D**：binary 文件缺失 → null（startSession + endSession 后手动删 binary 文件 + getLapTelemetry → null）
   - **case E**：crossing wallClock 全 null → null（测试反例锁死，spec Requirement 4 Scenario 1）
   - **case F**：getDataPointsForResult 正常路径——fake TestRecordEntity 含 dataFilePath 指向真实 binary 文件（fixture sample 的 `flags` 字段写入非 0 值，如 7）→ 非 null，samples.size > 0，testStartWallClock == entity.timestamp + **`samples.first().flags == 7`**（透传断言锁死 spec Requirement 2 normative "flags 透传"）
   - **case G**：testId 不存在 → null
   - **case H**：entity.dataFilePath = "" → null（baseline default 反例，spec Requirement 2 Scenario 3）
   - **case I**：dataFilePath 指向不存在文件 → null
   - **case J**（grep gate，**强 pattern + 排除 KDoc**）：测试代码内跑 `Files.readAllBytes(...)` 读 `TelemetryRepository.kt` 与 `TestResultRepository.kt` 源文件，断言：
     - (a) **gate-A**：`TelemetryRepository.kt` 内 `crossingWallClockTimestampMs\s*\?\:\s*return\s+null` 形态 Kotlin Regex 命中**恰好 2 次**（lapStart + lapEnd 两次 Elvis early return，spec Requirement 4 gate-A）；< 2 次说明实现绕开 wallClock 校验；> 2 次说明引入额外冗余分支。CLI 兼容形态：`grep -nE 'crossingWallClockTimestampMs \?: return null'`（L2 review Opus 双线修订：原稿 `crossing(\w+)?\.crossingWallClockTimestampMs \?:` 同时命中 sortedBy 内 `?:` fallback 命中数=3 + macOS BSD `\w` 不兼容；新 pattern 锁死 early return 形态恰好 2 次）
     - (b) **gate-B**：在 `TelemetryRepository.kt` 中提取 `getLapTelemetry` 函数 block + 跑 `\.crossingTimestampMs\b`（不带 WallClock 后缀的裸 GPS 协议时间字段）→ 命中 0 次（spec Requirement 4 gate-B）。**算法 MUST 用栈匹配 `{` / `}`**（避免朴素"找下一个 `}`"在 `withContext { ... }` / `runCatching { ... }` / `.map { ... }` lambda 嵌套层提前截断），verbatim 实现：
       ```kotlin
       val lines = source.lines()
       val startLine = lines.indexOfFirst { it.contains("suspend fun getLapTelemetry") }
       require(startLine >= 0) { "未找到 getLapTelemetry 函数定义" }
       var depth = 0; var endLine = -1
       outer@ for (i in startLine..lines.lastIndex) {
           // 简化处理：忽略字符串字面量内的 {} —— 测试代码生产用 KISS 实现
           lines[i].forEach { c ->
               if (c == '{') depth++
               else if (c == '}') { depth--; if (depth == 0) { endLine = i; return@outer } }
           }
       }
       require(endLine > startLine) { "栈式匹配未闭合 getLapTelemetry 函数 block" }
       val block = lines.subList(startLine, endLine + 1).joinToString("\n")
       val protocolTsHits = Regex("""\.crossingTimestampMs\b""").findAll(block).count()
       assertEquals(0, protocolTsHits)
       ```
     - (c) **依赖契约 gate（排除 KDoc）**：`TelemetryRepository.kt` 内跑 Regex `^\s*(import |private val |val |var |fun |class |suspend fun ).*\b(TestResultRepository|TestRecordDao|TestRecordEntity|TestResultSummary)\b`（仅扫 import / 字段 / 方法签名 / class 头，**MUST 排除** `^\s*\*` 前缀的 KDoc 注释行——baseline line 229 KDoc 含合法 cross-link `[TestResultRepository.deleteResult]`）→ 命中 0 次（spec Requirement 5 Scenario 1）
     - (d) **TestResultRepository 仅消费 readPerformanceSamples**（反向白名单形态，覆盖 baseline 全部 14 个公开方法）：`TestResultRepository.kt` 内跑 negative-lookahead Regex `telemetryRepository\.(?!readPerformanceSamples\b)\w+` → 命中 0 次（任何**非** `readPerformanceSamples` 的 telemetryRepository 方法调用都 fail；含 baseline `getRecentLapSessions` / `getBestLapForTrack` / `getSessionCountForTrack` / `getTotalLapCountForTrack` / `getRecentSessionsForTrack` / `deleteSession` 等本 round scope 不消费的方法，防 future round 跨边界扩散）
   - **路径处理 caveat**：测试 working dir = 模块根（Gradle test working dir = `core/data/`，**非** repo root），裸字面量 `Paths.get("core/data/src/main/...")` 会 fail。本 round 的 fix：在 `LapTelemetryReadersTest.kt` 文件内 **inline 重写** `private fun projectRoot()` helper（**MUST NOT** 直接 import `PresetTrackAssetTest.projectRoot`——它是 `private fun` 嵌在 `feature/test` module 的 test class 内，跨 module 不可引用）；helper 代码 verbatim 复制 `feature/test/src/test/java/com/blazepush/feature/test/repository/PresetTrackAssetTest.kt:26-32`：
     ```kotlin
     private fun projectRoot(): File {
         val classesDir = File(javaClass.protectionDomain.codeSource.location.toURI())
         val userDir = File(System.getProperty("user.dir"))
         return sequenceOf(classesDir, userDir)
             .flatMap { start -> generateSequence(start) { current -> current.parentFile }.filterNotNull() }
             .first { File(it, "settings.gradle").exists() || File(it, "settings.gradle.kts").exists() }
     }
     ```
   - **跨文件 grep 范围 caveat**（v3 高频盲点 #8）：执行 grep 时 MUST 排除 `.worktrees/` 子目录（避免误扫 worktree 副本让 gate 自我 trip）+ `core/data/src/test` 自身字符串（避免测试代码字面量构成假命中）；建议 pattern：`grep -rE 'pattern' core/data/src/main/java --include='*.kt'`（限定生产代码 + Kotlin 源）
- [x] 5.3 跑 `:core:data:testDebugUnitTest` 全部 10 cases 全绿（done = test report 0 fail）

## 6. 编译 + 端到端验证

- [x] 6.1 跑 `./gradlew :core:domain:compileDebugKotlin :core:data:compileDebugKotlin :core:data:testDebugUnitTest :feature:test:compileDebugKotlin :app:compileDebugKotlin` 主 worktree 全绿
- [x] 6.2 跑 `./gradlew :feature:test:testDebugUnitTest` 确认本 round 不破坏 feature/test 既有测试（baseline lab session / records / lap timing 测试 0 回归）
- [x] 6.3 跑 git diff --stat 验证本 round diff 边界：仅 `core/domain/.../model/LapTelemetry.kt`（新建）+ `core/data/.../repository/TelemetryRepository.kt`（追加 1 方法 + import）+ `core/data/.../repository/TestResultRepository.kt`（追加 1 方法 + import + 构造函数 1 参数）+ `feature/test/.../di/AppModule.kt`（1 行 single 改）+ `core/data/src/test/.../LapTelemetryReadersTest.kt`（新建）+（可选）`core/data/src/test/.../FakeTestRecordDao.kt`（新建）

- [x] 6.4 同步 deferred memo `docs/design/speed-curve-real-data-persistence-deferred.md`（v3 高频盲点 #15 防漂移）：在文件顶部加 ⚠️ 状态更新块说明"已合并到 lap-data-readers round (apply 2026-05-04)，最新接口契约见 lap-data-readers/spec.md Requirement 2"；显式声明接口形态变化（旧稿 `Flow<List<GpsDataPoint>>` → 新决策 `suspend fun ...PerformanceTelemetry?` + 位置从 TelemetryRepository → TestResultRepository）；保留 §1-§4 历史背景资料；§5/§6/§7/§9 涉及接口形态的内容标注"已被 lap-data-readers round 工件 override"；done = memo 顶部状态块存在 + 跑 `grep -n "Flow<List<GpsDataPoint>>" docs/design/speed-curve-real-data-persistence-deferred.md` 时虽仍命中（保留旧文 contextual 引用），但 ⚠️ 状态块顶部已说明"实际接口为 PerformanceTelemetry?"——避免下次 `wire-records-performance-real-curve` round 立项时按旧形态起草

## 7. 合回主区（user 拍板顺序）

- [x] 7.1 用户授权后从 worktree 执行 `git fetch origin && git rebase feature/track-tech-v2` —— 有冲突就地解决；rebase 后再次跑 6.1 + 6.2 全绿才合回（mimo apply 期间已合回，HEAD = `3c2f2d9`；metrics.yaml 旧稿写 `13c4791` 是 mimo dangling commit，已在 L2 review B 线 P1-4 修订）
- [x] 7.2 用户授权后切回主工作区 + 主区编译 + 测试再跑一遍全绿（HEAD `3c2f2d9` 实测 10/10 cases 0.554s pass + W2/W3 contract test 0 回归，详 review-l2-opus-b.md §B/C）
- [x] 7.3 主区合回后更新看板 §5 W1 行 + §6 共享文件 commit hash —— 见 task 14（合并到主看板修正 task）

## 8. L2 实施期 review

- [x] 8.1 跑 Opus 子 agent L2 adversarial review (参 `docs/templates/adversarial-review-prompt.md`)；Codex 后端失效（reconnect 失败 + 僵尸任务已 cancelled）→ 改用 Opus 双线（A 线模板 prompt + B 线差异化 prompt 角度：实施代码 line-by-line vs spec normative + 跨 module ripple + W2 兼容性 + v3 高频盲点 #9-#15）；trail 沉淀 `review-l2-opus-a.md` + `review-l2-opus-b.md`
- [x] 8.2 review 反馈消化：(a) 工件级自相矛盾归档前修（D6 公式 / spec gate-A pattern + 内部矛盾 / spec Req1 isEmpty normative）→ done；(b) P1-1 跨时钟域 anchor 推到新 round → memo `docs/design/lap-perftest-anchor-cross-clock-deferred.md` + tasks §10.9 backlog；(c) 测试 invariant assertion + P2 项推到 §10 backlog
- [x] 8.3 review pass 后归档：`mv openspec/changes/lap-data-readers/* openspec/changes/archive/2026-05-04-lap-data-readers/` + 修订 metrics.yaml（review_rounds_l2: 0 → 2 + commit_merge 13c4791 → 3c2f2d9 + 删假理由 + review_findings_l2 全部）

## 9. 真机验证（数据层 round，by design 跳过）

- [ ] 9.1 真机端到端验证留给 Phase 1 Tier2 `lap-detail-screen-with-cursor` round 落地时统一做（华为 8KE0219522008434）—— 本 round 是数据层 reader API，无 UI 消费可见证据，spec case A 端到端 round trip 单测已锁定正确性

## 10. Follow-up backlog

- [ ] 10.1 W3 round `lap-comparison-time-align` 落地时考虑是否在 `LapTelemetrySample.accelerationG` 字段回填派生值（per-sample 差分 vs SG 5 点 vs 留 null 让 UI 层算）—— 本 round Non-Goals 已声明 default null，由 W3 design 期决策
- [ ] 10.2 future sector round 落地时把 `LapTelemetry.sectorBoundaries` 从单元素扩展为多 sector 边界（依赖 `TimingGate` 非 SF gate 的 baseline 完善）—— 本 round 数据契约已留好接口
- [ ] 10.3 Tier2 `lap-detail-screen-with-cursor` round 落地时 UI 层处理 `getLapTelemetry` 返回 null 的"暂无该圈数据"空态（旧 row migration 之前数据，§8.3 known limitation）
- [ ] 10.4 Phase 2 `wire-records-performance-real-curve` round 落地时让 `RecordsHomeScreen.SpeedCurveStub` → `SpeedCurveReal` 消费 `testResultRepository.getDataPointsForResult(testId)`，删除硬编码 4.21s + 100km/h 字面量（参 deferred memo #5 §7 Phase 2 路径）—— 本 round 已铺好 reader API
- [ ] 10.5 若 future round 需要在 PERFORMANCE_TEST 场景填 `sectorBoundaries`（performance 测试一般无 sector 概念，留空 list 即可）—— 当前实现 PerformanceTelemetry 不含 sectorBoundaries 字段，符合语义

- [ ] 10.6 立项 follow-up `unify-lap-count-pairing-semantics` round：决策是否把 baseline `TelemetryRepository.endSession` line 164 的 `acceptedSF.sortedBy { it.crossingTimestampMs }` 同步切到 `crossingWallClockTimestampMs`，让 `entity.lapCount` 与本 round `getLapTelemetry` 可读 lap 计数收敛。本 round D5 caveat 显式接受双语义不收敛（spec normative "调用方 MUST 通过依次调用直到首次 null 来遍历可读 lap"），但 entity.lapCount 字段在 Records LAPS UI 列表显示中仍被使用——若 future round 真把 lapCount 切到 wallClock pairing，需评估对 Records UI 展示的影响

- [ ] 10.7 立项 follow-up `future-sector-derivation-round`：在 baseline `TimingGate` 非 SF gate 实现完善后，扩展本 round `LapTelemetry.sectorBoundaries` 从单元素到多 sector 边界。本 round 数据契约已留好 `List<Long>` 接口形态，扩展时不改既有 caller。对应 W2 SectorBar 组件在 mock 期可用多元素 mock 数据，但 Tier2 集成期需等 sector round 落地才能 enable 真实 sector 渲染（design R7 已声明 mitigation）

- [ ] 10.8 若 future round 引入 "writeCrossing wallClock 写失败但 crossingTimestampMs 写成功" 路径让生产数据出现交错 null（非"前缀 null + 后缀非空"模式），需 revisit 本 round `getLapTelemetry` 的 `sortedBy { ?: Long.MAX_VALUE }` 策略——交错场景下当前实现会破坏原始时序产生跨 row 配对。本 round spec normative 已显式锁定 invariant"前缀连续 null 段 + 后缀连续非空段"，违反该 invariant 是 future round 责任而非本 round 缺陷

- [ ] 10.9 立项 follow-up `unify-perftest-anchor-cross-clock` round：在 `TestResultRepository.getDataPointsForResult` 加 sentinel guard `if (entity.timestamp == Long.MIN_VALUE) return null` + spec Requirement 2 显式锁 invariant 三条款（entity.timestamp non-sentinel + GPS-UTC-本地壁钟漂移容许 + session 内无 GPS 失锁周期）。来源 = L2 review (Opus B 线) P1-1 finding；详细 design memo `docs/design/lap-perftest-anchor-cross-clock-deferred.md`（按"延期立项的设计 memo"9 章结构沉淀）。trivial 复杂度（~1.5h），建议放 Phase 1 Tier1.5（W4 之后、Tier2 之前）

- [ ] 10.10 测试 invariant 断言增强（属本 round 已 ship 代码层加固，trivial 复杂度，下次顺手做）：
  - case A 加 `assertTrue("elapsedMsInLap >= 0", r.samples.all { it.elapsedMsInLap >= 0 })` —— 锁 D6 派生不可负数（来源 L2 Opus A 线 P1-4）
  - case A 加 `assertEquals(1, r.sectorBoundaries.size)` —— 锁本 round 实施 size 严格 == 1（来源 L2 Opus A 线 P1-3）

- [ ] 10.11 测试代码 P2 改进（trivial cleanup，可 D round style-debt-cleanup 一并）：
  - LapTelemetryReadersTest fake DAO 命名参数与 supertype 对齐（17 处 IDE 警告，来源 L2 Opus A 线 P2-2）
  - case J `projectRoot()` `protectionDomain` nullable warning 加 `?` 安全调用（来源 L2 Opus A 线 P2-3，baseline 历史问题，PresetTrackAssetTest verbatim copy）
  - case A assertion message 字符串恢复（来源 L2 Opus A 线 P2-1，`13c4791` → `3c2f2d9` 期间被 mimo squash 时削减）

- [ ] 10.12 spec Scenario 7（混合 row）case K 补齐：tasks 5.2 加 case K 覆盖 spec line 57-59 "5 条 SF crossing 前 2 null + 后 3 非空"混合场景，锁死 `sortedBy { ?: Long.MAX_VALUE }` invariant（来源 L2 Opus B 线 P2-3）

- [ ] 10.13 design R7 SectorBar Tier2 决策拍板：当前 design R7 列了 "(a) 等 sector round 落地后才 enable" 或 "(b) Tier2 round 内派生" 两选项但未拍板（来源 L2 Opus A 线 P2-4 + B 线 P2-2）；Tier2 `lap-detail-screen-with-cursor` round 立项时 design 期 MUST 选定一项

## 11. 高风险动作清单（需 user 显式授权）

- [x] 11.1 task 7.1 rebase（mimo apply 期已合回，user 当时已授权）
- [x] 11.2 task 7.2 ff-only merge 到 feature/track-tech-v2（实际策略非 ff-only，是 cherry-pick / squash 重提交，metrics.yaml 已修订）
- [x] 11.3 task 8.3 归档（mv 工件目录）—— 2026-05-05 user 授权后执行
- [ ] 11.4 push（**永远不在本 task 内执行**；user 拍板 push 顺序，避免 kt-format-checker 顺序冲突）
