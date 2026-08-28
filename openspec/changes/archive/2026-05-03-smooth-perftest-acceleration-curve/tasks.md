## 0. 取证（不动产线代码）

- [x] 0.1 `adb -s 8KE0219522008434 shell run-as <package> ls files/telemetry/` 确认 debug 包可访问 + 列 binary 文件清单（若 release 包 → 0.1.b 临时装 debug 包）
- [x] 0.2 `adb shell run-as <package> sqlite3 databases/<db> "SELECT id, totalTime, dataFilePath FROM test_records WHERE testTemplateId = 'acc_0_100' ORDER BY timestamp DESC LIMIT 20"` 找 10.2s 与 7.2s 对应 sessionId 与 dataFilePath
- [x] 0.3 `adb shell run-as <package> cat files/telemetry/<id>.bin` 拉取 10.2s / 7.2s 两条 binary 到本地 `/tmp/<id>.bin`
- [x] 0.4 写一次性 Python 解码脚本（不进 codebase，本地 `/tmp/decode.py`）：按 `GpsBinaryFormat` 22-byte header + 17-byte/sample 解出 `(tsDeltaMs, lat, lon, speedKmh, bearing)` CSV
- [x] 0.5 在 Python 中实现旧算法（相邻一阶差分）+ 新算法（5 点 SG 中心差分 + forward/backward 边界），用 matplotlib 画两图：每条记录一张"旧 vs 新"G 曲线对比 + 速度曲线参照
- [x] 0.6 量化对比指标（spec 修订后）：(a) 新算法峰值落物理合理区间（acc [0.3, 1.5]G / brake [0.5, 1.5]G） — 3/3 通过；(b) 峰值邻居偏差均值 < 30% — 27.0% 通过；(c) HF RMSE（200ms 窗口）降幅均值 ≥ 60% — 63.7% 通过。注：spec 原指标 "峰值新算法 / 旧算法 ≥ 95%" 经数据验证为反向指标（旧峰值是噪声 spike 而非真值），已在 spec.md 与 design.md 中修订删除
- [x] 0.7 把对比图保存到 `openspec/changes/smooth-perftest-acceleration-curve/evidence/` 目录（PNG）+ 配套 CSV，并在 `design.md` 末尾补「## 数据证据」附录章引用图与量化指标 + 单条 outlier 物理解释
- [x] 0.8 数据已验证 5 点 SG 充分有效（汇总指标全部达标），不需要回退到 3 点 SG。Decisions D1 维持原方案
- [x] 0.9 L1 review 修订（2026-05-02）：spec.md / design.md / tasks.md 同步以下变更
  - design.md acc_10.68s outlier 物理解释改写（root cause = GpsDataFilter warmup 失效，非脚本缺 median）
  - spec.md 删除 "先 9 点 median 再 SG" scenario，加 "SG 自身可压低单帧跳变 + 不依赖 GpsDataFilter" + "非均匀 dt 反例"
  - design.md D1 加"等间距假设 + correctTimingPoints 顺序 hidden hazard"
  - design.md D2 加 `previousOutputSpeed` 与 A12/A13/A14 invariants 对称表
  - spec.md Requirement 3 加 3 条 invariant + dt > 200ms / 异常帧两条新 scenario
  - design.md D3 + Q2 决议改写：100-0 brake V1 记录 PEAK G 显示 "—"（不复用旧 abs 数据）
  - design.md D3 加 NOT NULL vs nullable 风格论证 + AppModule 注册 + 轻量 migration 单测策略
  - design.md 风险章 mitigation 改"回退到 3 点 SG"为"升级到 7 点 SG / EMA"
  - spec.md 数据证据章加"3 条样本是本 round 决策依据，扩展到 ≥ 5 条作为 backlog"
- [x] 0.10 PRAGMA user_version 验证当前真机 schema 版本 = 4 ✅（已确认 user_version=4，test_records 现有 13 列无 maxDeceleration，本 round migration 起点 from=4 → to=5 正确）

## 1. AccelerationSmoother 新文件 + 单测

- [x] 1.1 新建 `core/domain/src/main/java/com/blazepush/core/domain/usecase/AccelerationSmoother.kt`：定义 `data class TimedSpeedSample(val timestamp: Long, val speedKmh: Double)` + 纯对象 `AccelerationSmoother` + `fun compute(samples: List<TimedSpeedSample>): List<Double>`（输出 m/s²，正向 > 0）。done condition：grep `data class TimedSpeedSample` + `object AccelerationSmoother` 各 1 命中
- [x] 1.2 在 `AccelerationSmoother.kt` 内常量声明 SG 系数表（注释引用 Press et al. Numerical Recipes §14.8）：
  - 5 点中心 `[-2, -1, 0, 1, 2]/10`（分母 dt）
  - forward i=0：`[-25, 48, -36, 16, -3]/12`；i=1：`[-3, -10, 18, -6, 1]/12`
  - backward i=N−2：`[-1, 6, -18, 10, 3]/12`；i=N−1：`[3, -16, 36, -48, 25]/12`
  - 退化 N=2：两点直接差分
  - 退化 N=3：教科书 3 点 SG i=0/1/2 系数
  - 退化 N=4：4 点 forward i=0/1 系数 `[-11,18,-9,2]/6` 与 `[-2,-3,6,-1]/6`，i=2/3 用 backward 对称
- [x] 1.3 实现 `compute`：(a) 长度 ≤ 1 → `List(N) { 0.0 }`；(b) 长度 2-4 → 走「退化系数表」对应分支；(c) 长度 ≥ 5 → 计算 dts、`dt_median`，检查每帧 dt 是否在 `[0.8 × dt_median, 1.2 × dt_median]` 区间，全部合规走 5 点 SG（系数分母统一 `dt_median`），任一帧偏差 ≥ 20% 则整段退化为逐点 3 点 SG（中心 i=1..N-2 用 `[-1,0,1]/(2·dt_local)`，边界 i=0/N-1 用 `[-3,4,-1]/(2·dt_local)` / `[1,-4,3]/(2·dt_local)`）
- [x] 1.4 单测 1 — 等间隔 25Hz 匀加速序列（10 点，每帧 +10 km/h）→ 中心区间（i=2..7）偏差 < 0.5 m/s² ✅
- [x] 1.5 单测 2 — 合成"匀加速 + 高频小噪声"序列（200 帧 25Hz，基线 a=5 m/s² + ±0.1 km/h 量化噪声）→ 断言 SG 相对真值的 RMSE 较朴素差分降幅 ≥ 70% ✅
- [x] 1.6 单测 3 — 边界长度 0/1/2/3/4 走退化路径（拆为 5 个 testcase）✅
- [x] 1.7 单测 4 — SG 自身的单点跳变压制（**不级联 9 点 median**）✅
- [x] 1.8 单测 5 — 边界系数（forward/backward）二次多项式精度 ✅
- [x] 1.9 单测 6 — **反例**：非均匀 dt 拒绝走 5 点 SG 中心系数 ✅

## 2. 离线/UI 路径切换为 AccelerationSmoother

- [x] 2.1 修改 `core/domain/.../usecase/CalculateResultUseCase.kt:invoke`：**调换顺序**——先 `val accelerations = calculateAccelerations(rawDataPoints)`，再 `correctTimingPoints(rawDataPoints, template)`（注入 preciseStart/preciseEnd 锚点的 corrected 序列只用于 totalTime / segments，不喂 SG）。理由：5 点 SG 严格要求等间距，corrected 序列首尾两帧 dt 不等于 40ms 会污染 SG 边界系数（见 design.md D1 等间距假设）
- [x] 2.2 修改 `calculateAccelerations`：构造 `List<TimedSpeedSample>`（用 `dataPoints[i].elapsedTime * 1000` 转 ms 作为 timestamp，speed 来自 `dataPoints[i].speed`，已经是 outputSpeed），调用 `AccelerationSmoother.compute`，返回 `List<Double>`（m/s²）。done condition：grep 该文件不存在 `(curr.speed - prev.speed) / 3.6 / dt` 形式
- [x] 2.3 移除 `Math.abs()` 与 `if (accel < 3.0)` 过滤逻辑；改写 `invoke` 内统计：`maxAcceleration = accelerations.filter { it > 0 }.maxOrNull()?.div(9.81) ?: 0.0`、`maxDeceleration = accelerations.filter { it < 0 }.minOrNull()?.let { -it / 9.81 } ?: 0.0`、`avgAcceleration = if (accelerations.isNotEmpty()) accelerations.average() / 9.81 else 0.0`（保留语义但不 abs）
- [x] 2.4 跟进 `core/domain/src/test/.../CalculateResultUseCaseTest.kt`（如已存在）或新增：断言 0-100 fixture 与 100-0 fixture 的 max* 字段填充正确性（0-100：maxA > 0 + maxD = 0；100-0：maxA = 0 + maxD > 0）
- [x] 2.5 修改 `feature/test/src/main/java/com/blazepush/feature/test/ui/components/SpeedChart.kt:GForceChart`：用 `val gForcePoints = remember(dataPoints) { AccelerationSmoother.compute(...).mapIndexed { i, a -> Pair(dataPoints[i].elapsedTime, a / 9.81) } }` 包裹（避免 Compose 重组重算）；内部 G 值计算 MUST 走 `AccelerationSmoother.compute`
- [x] 2.6 同文件把 `if (abs(gForce) >= 3.0) return null` 改为 `gForce.coerceIn(-3.0, 3.0)`（保留所有点 + clip 到 ±3G），同步注释更新
- [x] 2.7 静态检查（grep）：确认 `CalculateResultUseCase.kt` 与 `SpeedChart.kt` 内不再存在直接 `(speed - prev) / 3.6 / dt` 形式的差分（`AccelerationSmoother` 内部除外）

## 3. TestResult / TestRecordEntity 字段拆分 + Room migration

- [x] 3.1 修改 `core/domain/src/main/.../model/TestModels.kt:TestResult`：新增 `val maxDeceleration: Double = 0.0`，更新文档注释明确两字段语义（`maxAcceleration` = 正向最大 G，`maxDeceleration` = 负向最大 G 的绝对值）
- [x] 3.2 同步修改 `CalculateResultUseCase.invoke` 与 `emptyResult` 构造 `TestResult` 处填入 `maxDeceleration`
- [x] 3.3 修改 `core/data/src/main/.../entity/TestRecordEntity.kt`：新增 `val maxDeceleration: Double = 0.0` 字段
- [x] 3.4 修改 `core/data/src/main/.../local/AppDatabase.kt`：仅升 `version = 5`，**不写 strict migration**（debug 阶段走 destructive fallback，user 决策 2026-05-03）：(a) 类注解 `@Database(version = 5, ...)`；(b) 在 companion object 内新增 `internal val migration4To5Sql: List<String> = listOf("ALTER TABLE test_records ADD COLUMN maxDeceleration REAL NOT NULL DEFAULT 0.0")`；(c) 新增 `val migration4To5 = object : Migration(4, 5) { override fun migrate(db) { migration4To5Sql.forEach { db.execSQL(it) } } }`
- [x] 3.5 修改 `feature/test/.../di/AppModule.kt:51`：保留 `addMigrations(migration3To4)`，把 `fallbackToDestructiveMigrationFrom(1, 2)` 扩展为 `(1, 2, 4)` 让 v4→v5 走 destructive。装新包时存量 test_records 清空：`.addMigrations(AppDatabase.migration3To4, AppDatabase.migration4To5)`（漏注册会触发 fallbackToDestructiveMigration 清库）。done condition：grep `addMigrations.*migration4To5` 命中 ≥ 1 处
- [x] 3.6 修改 `core/data/src/main/.../repository/TestResultRepository.kt:saveResult`：构造 `TestRecordEntity` 时填入 `maxDeceleration = result.maxDeceleration`；`toSummary()` 跟随 §4 决定是否携带
- [x] 3.7 检查 `TestResultSummary` 是否需要带 `maxDeceleration`（决议：**不需要**。grep 显示 RecordsHomeScreen / TestSessionViewModel 用 TestResultSummary 但不消费 max* 字段）（依赖 §4 UI 决定）；若 RecordsHomeScreen / V1 BEST tile 不消费则不加
- [x] 3.8 不新增 v4→v5 SQL 断言（destructive fallback 路径下无 SQL 可验）；保留 v3→v4 既有 7 个 testcase。`AppDatabaseMigrationSqlTest` 加注释说明 v4→v5 决策 `core/data/src/test/.../local/AppDatabaseMigrationSqlTest.kt`：JVM 单测断言 `AppDatabase.migration4To5Sql` 含字符串 `ALTER TABLE test_records ADD COLUMN maxDeceleration REAL NOT NULL DEFAULT 0.0`（**不引入 MigrationTestHelper / Robolectric**，与 AppDatabase.kt:75 既有 baseline 决策一致；正式 schema 验证作为 follow-up `room-test-infrastructure`）
- [x] 3.9 grep 结果列入 §4：消费点 = (a) `PerformanceResultScreen.kt:115` GForceCurveCard、(b) `PerformanceResultScreen.kt:233` PEAK G tile、(c) V1 `TestResultScreen.kt:88/148`；RecordsHomeScreen 不消费 max* / app/ 模块无 max* 直引

## 4. UI 渲染 maxDeceleration

- [x] 4.1 阅读 `feature/test/src/main/.../ui/tracktech/PerformanceResultScreen.kt:115` `GForceCurveCard(... maxAcceleration = record.maxAcceleration)` + line 233 PEAK G tile 上下文 + V2 visual tokens 决定渲染策略
- [x] 4.2 修改 `PerformanceResultScreen.kt`：新增 `derivePeakG(record, template) → PeakGTile` 二选一渲染：(a) acc → "PEAK ACCEL G" + maxAcceleration；(b) brake + maxD>0 → "PEAK BRAKE G" + maxDeceleration；(c) brake + maxD=0（V1）→ "—" unit=null；MetricRow 与 GForceCurveCard Y 轴共用 `peakG.gForceChartMaxG`：按 `record.testTemplateId` 二选一传递：(a) `acc_0_100` → 用 `record.maxAcceleration`，文案 "PEAK ACCEL G"；(b) `brake_100_0` AND `maxDeceleration > 0` → 用 `record.maxDeceleration`，文案 "PEAK BRAKE G"；(c) `brake_100_0` AND `maxDeceleration == 0.0`（V1 存量 abs 污染记录）→ **显式渲染 "—" + 副标 "V1 record"**，MUST NOT fallback 到 `record.maxAcceleration`（避免 V1 abs 污染语义错位）
- [x] 4.3 V1 `TestResultScreen.kt` 仍被 TestFlowNavigation.Result 路由使用，按同样 testTemplateId 二选一改造：GForceChart maxG 参数 + "最大G值" → "最大加速G值"/"最大制动G值" + V1 "—" 降级（V1 旧屏）：grep `maxAcceleration` 命中数；若 ≤ 0 直接跳过此 task；若 > 0 同 4.2 改造
- [x] 4.4 验证 `feature/test/.../ui/tracktech/RecordsHomeScreen.kt`：grep `record.maxAcceleration` / `result.maxAcceleration` / `summary.maxAcceleration` 命中 0 ✅ 无消费点，无需改造：grep `record.maxAcceleration` / `result.maxAcceleration` 命中数；done condition：命中数 = 0（确认无消费点，无需改造）；如 > 0 则补改造
- [ ] 4.5 真机自查 done condition（替代旧版"不崩溃"模糊判定）：(a) 0-100 旧记录 PerformanceResultScreen PEAK G tile 显示 `> 0.0G` 数字（V1 maxAcceleration 非 0）；(b) 100-0 旧记录 PEAK G tile 显示 "—" 副标 "V1 record"；(c) 应用日志无 NumberFormatException / NullPointerException；(d) 截屏 PNG 入 `evidence/v1-record-degradation/` 子目录留底

## 5. 实时路径修速度源（GpsDataFilter）

- [x] 5.1 修改 `core/domain/src/main/.../usecase/GpsDataFilter.kt`：(a) 新增字段 `private var previousOutputSpeed: Double? = null`；(b) `process()` 内在确定 outputSpeed 之后、计算 acceleration 之前，把"前一帧的 previousOutputSpeed"读出作为 dv 的左操作数，本帧 outputSpeed 作为右操作数；(c) `calculateAcceleration` 内部从读 `previousRaw.speed` 改为读 `previousOutputSpeed`，从用 `current.speed` 改为用本帧 outputSpeed（注意：dt 仍用 `current.timestamp - previousRaw.timestamp`，时间戳来源不变）
- [x] 5.2 实现 invariants（与 A12/A13/A14 baseline 对称，spec.md Requirement 3 已锁定）：
  - A12 `dt > 200ms` 重置 → 同时 `previousOutputSpeed = null`
  - A13 异常帧（isAnomaly OR isPositionAnomaly）→ MUST NOT 把本帧 outputSpeed 写入 `previousOutputSpeed`（与 `if (!isAnomaly && !isPositionAnomaly) previousRaw = raw` 同分支）
  - `reset()` 显式清空 `previousOutputSpeed`
- [x] 5.3 同步注释更新 + 代码内引用 spec scenario 路径（`spec.md Requirement: GpsDataFilter 实时加速度 MUST 基于 outputSpeed` + 4 条 invariants）
- [x] 5.4 跟进 `core/domain/src/test/.../usecase/GpsDataFilterTest.kt` 原有 acceleration 测试：从 raw 路径调整为 outputSpeed 路径
- [x] 5.5 新增单测：连续 9 帧匀加速 GpsData 输入 → `frames: List<FilteredGpsData>` → 断言 `frames[8].acceleration ≈ (frames[8].speed - frames[7].speed) / 3.6 / ((frames[8].timestamp - frames[7].timestamp) / 1000.0)`，偏差 < 1e-6 m/s²
- [x] 5.6 新增单测：dt > 200ms 重置 → 5 帧匀速 + 1 帧 timestamp 突跨 300ms + 该帧 acceleration == 0.0；之后再连续 9 帧后 acceleration 非 0
- [x] 5.7 新增单测：异常帧 invariant → 5 帧匀速 50 km/h + 1 帧 isAnomaly = true（速度跳变 100 km/h）+ 5 帧匀速 50 km/h；断言异常帧后第一帧（即第 7 帧）的 acceleration 接近 0 m/s²（dv 跨越异常帧时段，仍是 50→50 的差）
- [x] 5.8 全工程 build + 跑 `core/domain` 与 `feature/test` test 套件，确认无回归

## 6. 装机验证（**简化版** — user 决策 2026-05-03：暂无路测条件，仅做编译 + 启动验证）

- [x] 6.1 通知用户："准备装机 round `smooth-perftest-acceleration-curve` 到 8KE0219522008434（仅启动验证，不路测），等用户授权"
- [x] 6.2 用户授权后 `./gradlew :app:installDebug -PdeviceSerial=8KE0219522008434`
- [x] 6.3 启动 app 不崩溃（首屏渲染正常）
- [x] 6.4 v4→v5 Room destructive fallback 验证：`adb shell run-as com.blazepush sqlite3 databases/race_chrono_database "PRAGMA user_version"` = 5；`PRAGMA table_info(test_records)` 含 `maxDeceleration REAL` 列；存量 test_records 行数 = 0（destructive 路径下重建表，user 已接受）
- [x] 6.5 PerformanceResultScreen / TestResultScreen 空记录态渲染不崩溃（无 NumberFormatException / NullPointerException）
- [x] 6.6 logcat 抓 60 秒 app 运行日志，无新增 stacktrace / 异常
- [x] 6.7 **不路测**：路测验证（G 值曲线无高频尖刺、maxAcc/maxDec 体感一致、live G 体感观察、旧记录回看）属 follow-up，**条件具备时回来补**。登记到 §8.10 backlog
- [x] 6.8 取证阶段对比图（evidence/*.png）作为本 round 闭环依据：算法效果已经在离线脚本 + 单测 RMSE 降幅 ≥ 70% 验证，路测是补强不是阻塞

## 7. Codex review 收尾

- [ ] 7.1 通知用户触发 Codex review（`/code-review:code-review`）
- [ ] 7.2 阅读 review 报告，分类：
  - 局部修复（1-3 行）→ 直接补丁，更新对应 task 完成度
  - 设计级问题 → 建新 OpenSpec change（提示用户）
- [ ] 7.3 review 修复完成后 + 真机验证全通过 + 所有 tasks 勾选完毕，提示用户 `/opsx:archive` 归档本 round
- [ ] 7.4 **[需用户确认]** push 到 `feature_ctg_20260405_laptime_mainline` 或当前活跃 feature 分支（push 前列出 commit 清单等用户拍板）

## 8. follow-up backlog

- [ ] 8.1 `recompute-historical-perftest-stats`（延期立项）：从存量 binary 重读 + 用 AccelerationSmoother 重算 maxAcceleration / maxDeceleration 写回 Room，修复历史 abs 污染（消除 100-0 brake V1 记录 PEAK G "—" 降级）。设计 memo 路径 `docs/design/recompute-historical-perftest-stats-deferred.md`（本 round 闭环时写）
- [ ] 8.2 `add-realtime-acceleration-ema`（延期立项，明确 trigger）：实时路径 (`GpsDataFilter.calculateAcceleration`) 在 outputSpeed 之上叠加 EMA(α=0.3) 平滑加速度。**触发条件**：task 6.8 真机抓取的 60 秒 live G 序列单帧跳变 > 0.3G 出现 ≥ 1 次（用 FileLogger 抓取的 csv 量化）。若 6.8 无超阈值跳变则该 backlog 不必立项
- [ ] 8.3 `add-imu-sensor-fusion`（远期延期立项）：手机加速度计 + GPS 融合，提供横向 G（lateral G）与高频纵向 G。设计 memo 待真实需求出现再写
- [ ] 8.4 评估 `GpsDataFilter` 的 9 点 median 是否应对加速度信号也做（窗口长度 / 边界处理需独立调研，非本 round 范围）
- [ ] 8.5 `improve-gps-filter-startup-warmup`（延期立项，L1 review 揭示）：解决 GpsDataFilter 在静止 → 起步 warmup 阶段几乎裸过 filter 的问题（前 8 帧 + previousRaw=null 早退 + speedWindow 未填满）。这是 acc_10.68s 邻居偏差 34.1% 的真正 root cause。本 round 离线 SG 不会消除该跳变（避免双 median）。设计 memo 路径 `docs/design/gps-filter-startup-warmup-deferred.md`（本 round 闭环时写，需要 L1 review acc_10.68s outlier 物理解释作为现状证据章）
- [ ] 8.6 `evaluate-sg-with-extended-samples`（延期立项）：本 round 数据证据基于 3 条同车型同用户记录。扩展样本到 ≥ 5 条独立样本（混合 ≥ 2 测试模板 + ≥ 2 驾驶场景，最好不同车型），重跑 `decode_and_compare.py` 验证汇总指标在更大样本下仍达标。**触发条件**：积累到 5 条以上不同条件的真机测试记录后立项；本 round 闭环不阻塞此 backlog
- [ ] 8.7 `room-test-infrastructure`（延期立项）：引入 `androidx.room:room-testing` + `MigrationTestHelper` + 必要的 Robolectric / Context 隔离设施，对历史所有 migration（v1→v2/v2→v3/v3→v4/v4→v5）跑实际 schema 验证单测（替代当前的 SQL 字符串断言）。设计 memo 路径 `docs/design/room-test-infrastructure-deferred.md`
- [ ] 8.10 `road-test-smooth-perftest-acceleration`（**条件触发**，user 决策 2026-05-03）：本 round 因暂无路测条件，§6 真机验证简化为编译+启动，未做路测。条件具备后跑 2 次 0-100 + 2 次 100-0 真机测试，验证：(a) G 值曲线肉眼无高频尖刺；(b) maxAcceleration / maxDeceleration 数字与体感一致；(c) 100-0 旧记录 PEAK G tile 显示 "—" + 副标 "V1 record" 不崩溃；(d) FileLogger 抓 60 秒 live G 序列，单帧跳变 > 0.3G 触发 §8.2 立项条件验证。截图入 `evidence/post-roadtest/`，对比图入 design.md §「数据证据」附录"实施后真机截图"子节
- [ ] 8.9 `restore-strict-migrations-pre-release`（**上线前必须做**，2026-05-03 user 决策）：本 round v4→v5 走 destructive fallback（debug 期决策），上线前必须补回严格 migration 函数 + 单测，参照 v3→v4 既有 `migration3To4` / `migration3To4Sql` pattern；同时把 `AppModule.kt` 的 `fallbackToDestructiveMigrationFrom(1, 2, 4)` 收回为 `(1, 2)`。覆盖范围：所有走 destructive 的 round（其它并行 round 也按此策略，需汇总），不仅本 round 一处
- [ ] 8.8 `consolidate-domain-median-util`（延期立项，§1 code review P2-4 揭示）：当前 `core/domain/usecase/AccelerationSmoother.kt:medianDt`（DoubleArray 私有）与 `GpsDataFilter.kt:300 List<Double>.median()` 私有扩展两份近似等价的中位数实现。重构为共享 utility（如 `core/domain/math/MedianUtil.kt`），同时支持 `List<Double>` 与 `DoubleArray`，去掉两处重复。本 round 不做（属 baseline 改造）
