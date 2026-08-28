# 测试进攻 Backlog（attack-backlog）

> **协作契约**：
>
> - 本文档由**评审方**写（新增攻击点、核销结论）；**实施方**读（领任务）、改（更新状态、附 commit）。评审方核销通过后**标记成功**。
> - 评审方永不直接改代码；实施方永不自行核销。
> - 每条攻击点都附 `证据（file:line）/ 攻击点 / 核销条件 / 状态`。核销条件是"什么情况下评审方会盖章 ✅"的明确断言，写清楚可让实施方自测。
> - 已核销条目保留在"第五节 ✅ 已核销存档"作索引，不删除。

---

## 零、状态机与操作协议

### 状态值

| 标识 | 含义 | 谁可改 |
|---|---|---|
| 🔴 `pending` | 评审方新增，待实施方认领 | 评审方创建；实施方改 → `in_progress` |
| 🟡 `in_progress` | 实施方认领，施工中 | 实施方改（附 commit hash）→ `pending_review` |
| 🟢 `pending_review` | 实施方完成，待评审方核销 | 评审方核销 → `resolved` 或 `rejected` |
| ✅ `resolved` | 评审方核销通过，已成功 | 迁入第五节存档 |
| ❌ `rejected` | 评审方核销不通过，附理由退回 | 实施方改 → `in_progress` 重攻 |
| 🟣 `proposal-needed` | 功能规划 / 需求级条目，需先起 proposal 再认领 | 实施方起 proposal 后改 → `pending` |

### 状态转换

```
[ 🟣 proposal-needed ] --立 proposal + 核销 spec--> [ 🔴 pending ]
                                                            |
[ 🔴 pending ] --认领--> [ 🟡 in_progress ] --完成 + commit--> [ 🟢 pending_review ]
                                ^                                    |
                                |                                    |
                                +-- ❌ rejected (附理由) <--核销失败--+
                                                                     |
                                                                     +--✅ resolved (迁第五节) <--核销成功
```

### 字段模板

每条攻击点按以下格式写：

```
### A{N}: 一句话标题 （战役标签）

- **来源**：`xxx-review.md § X.Y` / `BC.N` / `v2 C.M` / 直接发现
- **证据**：`path/to/file.kt:行号` — 一句话现象
- **攻击点**：1~3 句为什么这是问题（对抗式视角）
- **核销条件**：
  - (1) 可验证断言 1
  - (2) 可验证断言 2
  - ...
- **状态**：🔴 `pending`
```

状态变更时在 `**状态**` 行追加一条变更记录，**不覆盖历史**：

```
- **状态**：🟢 `pending_review`（@impl, commit acc9192, 2026-04-22）
  - 🔴 → 🟡：@impl 认领（2026-04-22）
  - 🟡 → 🟢：commit acc9192，自测通过（2026-04-22）
```

---

### 核销闭环原则（Non-negotiable）

> **核销 = 闭环**。评审方在核销时发现新问题 —— 必须 ❌ `rejected`，不得盖 ✅。

1. **新发现的问题 = 拒绝核销**。无论新问题是：
   - bug（如 v2 C.1 Running 分支漏守卫）
   - 测试断言松（如 BC.1 注释反了）
   - 代码异味（如 v2 C.4 字段冗余）
   - 未审代码（评审方只读 commit message 没读 diff —— 不能盖章）
   —— 都要 ❌ 退回，原条目留在 🟢 或回到 🟡，直到 **新问题要么在本次战役内修完、要么评审方显式豁免**。

2. **评审方豁免的前提**：
   - 显式在条目里写明 "**本次核销豁免**：新发现 X 拆为独立条目 Y，不阻塞本次闭环"
   - 豁免仅限 P2 级别（代码异味、文档修订、注释级），不适用 P0/P1
   - 若评审方事后发现豁免错误（豁免项引出更多问题），可以**事后撤销**把条目拉回 🟢

3. **施工方权益**：
   - 实施方对"新发现"可以 pushback（技术上不认可 / 超出 change scope）
   - 评审方和实施方必须就"是否属于本次闭环"达成一致才能继续
   - 不一致时默认严格 —— 不盖 ✅

4. **违反这条原则的后果**：
   - 已盖章但未闭环的条目 = **🟡 技术债**，将在下次复审时被拉回 🟢
   - 评审方的 review 文档结论（例如 "🔴 暂不合流"）与 backlog 状态（例如 ✅）冲突时，**以 review 文档为准**，backlog 状态错了改 backlog

本文档 2026-04-22 初始化时违反了此原则：A2/A3/A4/A5/A31 被盖了 ✅ 但对应 v2 code review 明确 "🔴 暂不合流"。已按本条款第 4 项降级为 🟢 `pending_review`，并在第三节附拒绝理由。

---

### 文档分层原则

本 backlog 是跨战役大看板，**只写攻击点状态**（🔴/🟡/🟢/✅/❌）与 **"最近动作"索引**，不复制单轮 review 文档的 P2/P3 清单与推导过程。

| 信息类型 | 落盘位置 |
|---|---|
| 攻击点最新状态、最近动作索引、未闭合项清单 | 本文档（attack-backlog.md） |
| 单轮 review 的 P2/P3 清单、推导过程、量级分析 | `docs/superpowers/reviews/YYYY-MM-DD-*-review.md` 日期前缀独立文件 |
| OpenSpec 契约本身（MUST / Scenario） | `openspec/changes/*/specs/` |
| 决策背景（为什么选 A 不选 B） | `openspec/changes/*/proposal.md` 决策 N 段落 |
| 未来战役预留占位（量级驱动，尚未开战） | 本文档第七节 "未来战役预留" + 对应决策 review 文档 |

**原则**：

1. 本文档条目只写 "是什么状态" + "最近一次动作在哪（带独立 review 文档链接）"，不复制 review 文档的推导过程
2. 单轮 review 写完即冻结，不回改；新一轮 review 生成新的日期前缀文件
3. 攻击点状态与独立 review 文档**双向同步**：状态变化时 backlog 条目附 review 文档路径；评审方结论（例如 "🔴 暂不合流"）与 backlog 状态冲突时**以 review 文档为准**

---

## 一、🔴 `pending` — 新待认领





### A35：UI `currentLap` 显示 `+1` 与 Ready 状态不符 （战役 I / UI 一致性）

- **来源**：`2026-04-22-lap-timing-and-gps-adversarial-review.md § 1.11`
- **证据**：`feature/test/src/main/java/com/blazepush/feature/test/ui/screen/LapDebugExecutionScreen.kt:42` — `currentLap = (lapSession?.currentLapIndex ?: 0) + 1`
- **攻击点**：Ready 状态 `currentLapIndex = 0` → UI 显示 "Lap 1"，但此时还没开圈。同时 `statusLabel` 显示 "等待起点"。用户看到"Lap 1 + 等待起点"语义冲突。
- **核销条件**：
  - (1) `activeLap == null` 时显示 "未开圈 / 等待起点"（与 `StartFinishTimingCardState.statusLabel` 保持一致）
  - (2) `activeLap != null` 时显示 "Lap N"（N = `activeLap.lapIndex`）
  - (3) `LapDebugExecutionScreenStateTest` 新增 `currentLap_whenReady_displaysWaitingForStart`
- **状态**：🔴 `pending`

---


（A54/A55 已核销迁入第五节存档；A26/A41 已核销迁入第五节存档）

---

## 二、🟡 `in_progress` — 实施方施工中

（暂无）

---

## 三、🟢 `pending_review` — 待评审方核销

（暂无）

## 四、❌ `rejected` — 核销失败已退回

（暂无）

---

## 五、✅ 已核销存档

> 存档只保留"ID + 一句话 + 核销 commit / 战役 + 显式豁免声明"，方便溯源。详细证据回原 review 文档查。

### ✅ A17 / A30：战役 D 尾巴 + H 清理 Round 4 DI fallback 边界 + anomaly 孤岛删除（`fix-di-fallback-and-anomaly-island-cleanup`）

- **核销**：commit `fcc61cc`，2026-04-26
- **A17 成果**：
  - `single<TrackCatalog>` 从 `runCatching { ... }.getOrElse { PresetTrackCatalog() }` 改为 `try/catch` + `findInCauseChain<MissingAndroidContextException>()` cause-chain 检查。
  - 复用 Koin Android 自带 `org.koin.android.error.MissingAndroidContextException` 作为标记类型；`single<ReplayTrackSource>` 保持零改动，不新建项目内同名 wrapper。
  - scope 明确限定为 **DI provider 创建期异常边界**：cause chain 命中 MissingAndroidContextException 才 fallback；fake provider 直接抛 IOException 等非 Missing 异常走 `throw e` 上抛。
  - **显式豁免 / 后续边界**：A37 `ReplayAlignedTrackCatalog.ensureReplayTrackLoaded()` 内 `runCatching { ... }.getOrNull()` asset read / parse fallback 容错契约保留；`getAllTracks()` 内 asset read IOException 不在 A17 round 处理范围。若未来要 asset read 失败上抛崩溃上报，另起独立 round。
- **A30 成果**：
  - 删除 `core/domain/src/main/java/com/blazepush/core/domain/usecase/AnomalyDetector.kt`、`DataInterpolator.kt`、`DataSmoothing.kt` 三个孤岛类。
  - `AppModule.kt` 删除 3 import + 3 factory，`GpsDataViewModel` 注入参数 4 → 3；`GpsDataViewModel.kt` 删除 `DataSmoothing` 构造参数与 reset 调用；测试调用方同步迁移。
- **验证**：
  - `openspec validate fix-di-fallback-and-anomaly-island-cleanup --strict`：PASS
  - `./gradlew :feature:test:testDebugUnitTest --tests "*DomainModuleKoinTest*"`：BUILD SUCCESSFUL
  - 实施方回报 `:feature:test` 全测、下游 `:core:bluetooth` / `:core:domain` / `:app`、E2E 契约均通过
  - A30 grep：`\bAnomalyDetector\b|\bDataInterpolator\b|\bDataSmoothing\b` 在 `core/feature/app/simulator` Kotlin 源码零命中
- **review 修订闭环**：
  - code review P2：原 spec/tasks 误要求 `loadReplayJson()` / `getAllTracks()` IOException 上抛；v3 已统一为 B 方案，proposal/spec/design/tasks 全部对齐 DI provider 创建期 scope
  - P3 豁免：`tasks.md` §10.2 补 import 仍列出未使用的 `runBlocking` / `assertEquals`，实际代码未引入；不影响核销
- **review 文档依据**：`2026-04-25-opsx-fix-di-fallback-and-anomaly-island-cleanup-ff-review.md` + `2026-04-25-opsx-fix-di-fallback-and-anomaly-island-cleanup-code-review.md`
- **存档日期**：2026-04-26

---

### ✅ A22：战役 F Round 3 ActiveLap distance engine 增量累积 + UI O(1) 读取（`fix-active-lap-distance-accumulator`）

- **核销**：commit `0321190`（主体实现）+ `a8e2377`（code review P2 path(d) no-target 真覆盖修补），2026-04-25
- **成果**：
  - **model**：`ActiveLap.distanceMetersSinceStart: Double = 0.0` 新增为第 6 字段，表示 active lap 生命周期内从开圈点累计到当前帧的米数
  - **engine**：`LapTimingEngine.processSample` 在 samples append 后集中构造 `activeLapWithDistance`，增量来源固定为 `session.samples.lastOrNull()` 与 `currentSample` 的 haversine；5 路径 (a)-(f) 均携带或重置正确，sector accepted 分支用 `activeLapWithDistance!!.copy(...)` 派生
  - **UI**：`LapDebugExecutionScreen.rememberStartFinishTimingCardState` 改为只读 `lapSession?.activeLap?.distanceMetersSinceStart ?: 0.0`；删除 `calculateDistanceSince`、UI 私有 `haversineDistanceMeters` 与孤立 `GpsSample` import
  - **GeoMath**：新增 `feature/test/.../usecase/GeoMath.kt`，把原 UI 私有 haversine 公式迁为 feature/test 模块 internal 工具
  - **A56 边界**：`distanceMetersSinceStart` 明确为运行期 active-lap 派生状态，不修改 `LapRecord` schema，不引入 telemetry 持久化 / Room / DAO / chunk 写入路径
- **review 修订闭环**：
  - `a8e2377` 关闭 code review P2：原 path(d) 测试实际退化到 sector rejected；新测试 `processSample_whenNextExpectedGateIndexExceedsSectorCount_routesThroughNoTargetGatePath` 通过 `nextExpectedGateIndex = sectorGates.size + 1` 真正触发 `expectedGate(...) == null`，并断言 distance 增量、events 不变、index 不推进、samples append
- **验证**：
  - `openspec validate fix-active-lap-distance-accumulator --strict`：PASS（实施方回报）
  - `./gradlew :feature:test:testDebugUnitTest --tests "*LapTimingEngineTest.processSample_whenNextExpectedGateIndexExceedsSectorCount_routesThroughNoTargetGatePath" --tests "*LapDebugExecutionScreenStateTest*"`：BUILD SUCCESSFUL
  - 实施方回报 `:feature:test:testDebugUnitTest` 全测、下游 `:core:bluetooth` / `:core:domain` / `:app`、E2E 契约、A56 diff 新增行 grep 均通过
- **核销条件修订留痕**：proposal V2 已批准 backlog A22 (3)/(5) 修订：engine 是唯一 producer / UI 只读，判圈几何与距离累计不强求统一；Robolectric/benchmark 改为源码零残留 + 7500-sample unit smoke `<16ms`
- **review 文档依据**：`2026-04-25-opsx-fix-active-lap-distance-accumulator-proposal-review.md` + `...-design-review.md` + `...-spec-review.md` + `...-tasks-review.md` + `...-code-review.md`
- **存档日期**：2026-04-25

---

### ✅ A28 / A37：战役 F Round 2 GPS stats 口径 + TrackCatalog 冷启动非阻塞（`fix-gps-stats-and-lazy-catalog-hot-start`）

- **核销**：commit `ebaf394`（主体实现）+ `fabb285`（code review P2 注释残留修补），2026-04-25
- **成果**：
  - **A28**：`GpsDataViewModel.updateDataStats` 删除会话级累计平均，`DataQuality.frequency` 透传 `GpsData.frequency`（parser 1 秒滑窗）；`packetLoss` 抽为 `computePacketLossRate(dataAge, frequency)`，从 `data.frequency` 反推期望采样周期，支持 10Hz / 25Hz / 50Hz；`GpsDataViewModel.init` 自订阅 `connectionState.filter{DISCONNECTED}` 并 `resetStats()` 回到 `DataQuality.Empty`
  - **A37**：`TrackCatalog.getAllTracks()` suspend 化；`PresetTrackCatalog` 内存直返；`ReplayAlignedTrackCatalog.getAllTracks` 顶层 `withContext(Dispatchers.IO)`；去除 `ReplayAlignedTrackCatalog` 原惰性属性，改 `@Volatile + synchronized` 显式缓存；同步 `getTrack(TFIC)` 冷缓存降级 fallback 不触 asset IO，热缓存返回 replay-aligned
  - **TestSessionViewModel**：`_availableTracks` 构造期初始化为空列表，`viewModelScope.launch { trackCatalog.getAllTracks() }` 异步加载且不指定 `Dispatchers.IO`
- **review 修订闭环**：
  - `fabb285` 关闭 code review P2：`resetStats()` KDoc 中已删除 `dataCount / dataCountStartTime` 禁字段字面量，A28 机器 grep 契约可核销
- **验证**：
  - `openspec validate fix-gps-stats-and-lazy-catalog-hot-start --strict`：PASS
  - `./gradlew :feature:test:testDebugUnitTest --tests "*GpsDataViewModelTest*"`：BUILD SUCCESSFUL
  - `./gradlew :feature:test:testDebugUnitTest --tests "*ReplayAlignedTrackCatalogTest*"`：BUILD SUCCESSFUL（单独重跑；评审方并行跑同一 Gradle test task 曾撞 binary results 输出目录，非测试失败）
  - 禁字段 grep：`dataCount|dataCountStartTime|expectedInterval = 100|_dataStats|val dataStats|StateFlow<DataStats>|EXPECTED_SAMPLE_INTERVAL_MS` 在本 change 约束目录零命中
- **review 文档依据**：`2026-04-25-opsx-fix-gps-stats-and-lazy-catalog-hot-start-proposal-review.md` + `...-spec-review.md` + `...-tasks-review.md` + `...-code-review.md`
- **存档日期**：2026-04-25

---

### ✅ A18 / A39：战役 F Round 1 FileLogger 异步化 + 坐标精度降级（`fix-file-logger-and-engine-coord-hygiene`）

- **核销**：commit `da3f537`（主体实现）+ `d3e2496`（v1 review 修补 P1 SimpleDateFormat → ThreadLocal / P2 降级契约 / P2 drop-0 假断言）+ `ac1bdc8`（v2 review 修补 P1 并发 smoke 降压至 16×32<1024 / P3 头注释），2026-04-24
- **成果**：
  - **A18**：`FileLogger.d/v/e` 从主线程同步 `FileWriter.use` 改为 `Channel<LogCommand>(capacity=1024, DROP_OLDEST)` + IO 协程批量 flush（FLUSH_BATCH_SIZE=64 / FLUSH_INTERVAL_MS=200ms）；IOException 外层 try/catch + Log.e 降级业务永不感知；MAX_FILE_BYTES = 5 * 1024 * 1024 写后 `checkAndRotate`（debug_log.txt ↔ .1）
  - **A39**：新增 `LogLevel`（VERBOSE < DEBUG < INFO < WARN < ERROR）默认 DEBUG；与 A18 合并——高频 3 call site（engine:70 detector / VM:335 bridge GPS / VM:373 bridge 结果）`d() → v()` + 坐标 `"%.3f".format(lat/lon)`（~110m 精度）+ `if (FileLogger.isVerboseEnabled)` 守卫；非高频 9 条 call site 保持 `d()` + 原精度
  - **v5 graceful handoff**：实施期发现 `cancelAndJoin` 与 `Channel.receive` 存在交付竞态（实测偶现漏 msg-21 / msg-30 等中间项），改为 `LogCommand.Shutdown` FIFO 排空方案 + `isActive` 守卫避免污染下一个 flushJob
  - **ThreadLocal formatter**：`dateFormat` 从共享单例 `SimpleDateFormat` 改为 `ThreadLocal<SimpleDateFormat>`，每线程独享实例避免 Calendar / NumberFormat 并发串扰
  - **full-buffer 降级契约**：channel 满时 send(Flush/Shutdown) 按 DROP_OLDEST 挤掉最老 1 条 Line，控制命令自身不丢（控制面 > 数据面）；独立控制 channel 方案被显式拒收（复杂度与诊断收益不匹配）
- **review 修订闭环**：
  - `d3e2496` 关闭 v1 的 3 条 finding（P1 SimpleDateFormat 并发 / P2 full-buffer 契约未文档化 / P2 drop_oldest 假断言 `content.contains("drop-0 ")` → `lines.any { it.endsWith("drop-0") }`）
  - `ac1bdc8` 关闭 v2 的 1 条 P1 + 1 条 P3（并发 smoke 1600 条违反刚拍板的降级契约 → 降至 16×32=512 < capacity 1024；文件头注释 14→16 Scenario / R1×5→×7）
- **测试**：
  - `FileLoggerTest` 16 Scenario：R1×7（业务非阻塞 1000 次 <100ms 硬区分 v1/v2 + IOException 吞 + DROP_OLDEST endsWith 精确匹配 + graceful handoff + flushForTest deterministic drain + ThreadLocal 并发 smoke + full-buffer 降级契约）/ R2×2 rotate / R3×3 级别 / R4×4 高频分级
  - 3 轮 `:feature:test:testDebugUnitTest --tests "*FileLoggerTest*"` 稳定全绿
- **验证**：
  - `openspec validate fix-file-logger-and-engine-coord-hygiene --strict`：通过
  - 下游 `:core:bluetooth:testDebugUnitTest :core:domain:test :app:compileDebugKotlin` 零回归（stash 非本 scope 的 RaceChronoParser working-tree 改动后验证）
- **kt-check 豁免**：`FileLogger.kt` / `LapTimingEngine.kt` / `TestSessionViewModel.kt` / `FileLoggerTest.kt` 按战役 G B 方案加 `// @IgnoreFormatCheck` 头（legacy pre-existing 违规 + 测试文件 JUnit 惯用命名，评审方 2026-04-24 批准避免 scope 漂移 refactor）
- **review 文档依据**：`2026-04-24-opsx-fix-file-logger-and-engine-coord-hygiene-spec-tasks-review*.md`（v1-v4 spec/tasks 4 轮 review）+ `2026-04-24-opsx-fix-file-logger-and-engine-coord-hygiene-code-review*.md`（code-review v1 / v2 / v3 三轮代码核销）
- **存档日期**：2026-04-24

---

### ✅ A36 / A43 / A44：战役 C 三期尾巴清理（`fix-lap-timing-campaign-c-tail-cleanup`）

- **核销**：commit `7ee9122`（A36/A43/A44 主体）+ `48837a4`（A43 大小写 / 中文残留修订）+ `9605258`（A43 豁免说明禁词残留修订），2026-04-24
- **成果**：
  - A36：`Track.orderedSectorGates by lazy` 成为 sector gate 排序单点真理；`LapTimingEngine` 两处 + `LapDebugExecutionScreen` 一处消费方收敛；新增 `TrackTest` 三条排序 / lazy 缓存 / data class equality 契约测试
  - A43：`GpsDataFilter.circularMean` 命名与实现语义对齐；KDoc 明确其为单位向量均值、对长尾不鲁棒；调用点、测试名、中文注释、格式豁免说明全部完成命名纠偏
  - A44：新增 `wrappedDeltaLon(currentLon, prevLon)` 处理 ±180° 经度绕回；`checkPositionVelocityConsistency` 经度差改用 helper；新增 antimeridian 物理自洽 hard-fail 测试 + 非跨边界回归测试
- **review 修订闭环**：
  - `48837a4` 关闭大小写残留与正文"循环中位数"残留
  - `9605258` 关闭 `@IgnoreFormatCheck` 豁免说明自身引入禁词残留
- **验证**：
  - `./gradlew :core:domain:test --tests "*GpsDataFilterTest*"`：BUILD SUCCESSFUL
  - `rg -n -i "circularmedian|循环中位数" core/domain/src feature/test/src core/bluetooth/src`：无残留
  - 实施方已回报 `openspec validate --strict`、`TrackTest`、`LapTimingEngineTest`、`EndToEndLapTimingContractTest`、`TestSessionViewModelTrackLapTest` 全绿
- **review 文档依据**：`2026-04-24-opsx-fix-lap-timing-campaign-c-tail-cleanup-spec-tasks-review.md` + `2026-04-24-opsx-fix-lap-timing-campaign-c-tail-cleanup-code-review.md`
- **存档日期**：2026-04-24

---

### ✅ A16a：lat/lon signed int32 解码 bug（RP16 / RP19）（`fix-parser-signed-int-decoding`）

- **核销**：commit `f097478`，2026-04-24
- **成果**：
  - `RaceChronoParser.kt` line 178 / 185 删除 `.toLong() and 0xFFFFFFFFL` unsigned mask，改为 `latInt / 10_000_000.0` + `lonInt / 10_000_000.0`，按 signed int32 保留南纬 / 西经符号
  - `RP16_parseLatitude_negative` + `RP19_parseLongitude_negative` 去 `@Ignore` 并参与测试
  - 新增布宜诺斯艾利斯双负坐标、接近南极 / 反子午线极端负值两条边界测试
- **验证**：
  - `openspec validate fix-parser-signed-int-decoding --strict`：PASS
  - `grep -c "^[[:space:]]*@Ignore\\b" RaceChronoParserTest.kt`：`1`（仅剩 RP22）
  - `./gradlew :core:bluetooth:testDebugUnitTest --tests "*RaceChronoParserTest*"`：BUILD SUCCESSFUL
- **显式留痕**：A16b altitude 四方契约不一致仍保持 🔴 `pending`，绑定独立 change `fix-altitude-encoding-contract-alignment`
- **review 文档依据**：`2026-04-24-opsx-fix-parser-signed-int-decoding-spec-tasks-review.md`
- **存档日期**：2026-04-24

---

### ✅ A16b：altitude 编解码四方契约对齐 ino（`fix-altitude-encoding-contract-alignment`）

- **核销**：commit `19e5b75`（R1/R3 parser + 协议文档）+ `428a113`（R2 simulator）+ `29ab58e`（P2 注释残留清理），2026-04-24
- **成果**：
  - `RaceChronoParser.kt` altitude 解码两分支改为与 ino `RaceChrono_ESP32_M9N.ino:294-298` 对称：bit15=0 `(raw & 0x7FFF) / 10.0 - 500.0`（精度 0.1m）；bit15=1 `(raw & 0x7FFF).toDouble() - 500.0`（精度 1m）
  - `GpsDataGenerator.kt` 发送端判定由 `raw <= 32767` 改为 `alt < 6053.5`，bit15=1 公式不乘 10，与 ino 字节级一致
  - `RaceChronoParserTest.kt` `createValidGpsData20` helper 编码按 ino 重写，RP22 去 `@Ignore` 且测试数据重构；新增 RP22b（6054m bit15=1 最小边界）/ RP22c（10000m 典型高海拔）/ RP22d（4000m `[2776.7m, 6053.5m]` 截断区间 Non-goal 锚点）
  - `GpsDataGeneratorTest#generatesBytes_altitudeWithInoCompatibleEncoding` 字节级锁定 100m→`0x1770` / 10000m→`0xA904` / 4000m→`0x2FC8`，其中 10000m 与 v1 公式 `0x9A28` 硬区分
  - `docs/RaceChrono_BLE_Protocol.md` § 3.4 altitude 公式正文与 Kotlin 示例代码块同步修订
  - `[2776.7m, 6053.5m]` 区间 ino `& 0x7FFF` 截断 bug 决策：Non-goal 契约，simulator 与 ino 行为一致，parser 不报错（audit § 6.4）
  - 下游消费者审计：仅 replay/UI 透传与展示，不参与判圈判定（audit § 10.2）
- **验证**：
  - `openspec validate fix-altitude-encoding-contract-alignment --strict`：PASS
  - `./gradlew :core:bluetooth:testDebugUnitTest --tests "*RaceChronoParserTest*" :simulator:testDebugUnitTest --tests "*GpsDataGeneratorTest*"`：BUILD SUCCESSFUL
  - `rg` v1 公式关键词在 altitude scope 零命中（仅剩 speed 侧匹配，不在 A16b scope）
  - `git show --check 29ab58e`：无格式违规
- **review 文档依据**：`2026-04-24-opsx-fix-altitude-encoding-contract-alignment-code-review.md`（P2 注释残留于 commit `29ab58e` 闭合后核销转 ✅）
- **存档日期**：2026-04-24

---

### ✅ A26 / A41：战役 H 一期 parser 内部状态污染清理（`refactor-parser-internal-state-cleanup`）

- **核销**：commit `9335ce0`（R1/A26 parseGpsTimeData 不写 isTestReady）+ `3051253`（R2/A41 删除 5 死字段 + tracking 块 + 孤儿 import），2026-04-24
- **成果**：
  - A26 (R1)：`RaceChronoParser.kt:111-115` 删除时间包成功路径的 `isTestReady` 写入，合并 if/else 为单一 `copy(errorMessage = null)` 分支；`isTestReady` 唯一写入源收敛为主包 `parseGpsData` 的 `satellites >= 6 && hdop < 2.0` 判定；冷启动 4 帧序列硬区分 v1（v2 输出 `[false, false, false, true]`，v1 会 `[true, false, true, true]` 闪烁）
  - A41 (R2)：`RaceChronoParser.kt:36-41` 删除 `startTime / totalDistance / lastLatitude / lastLongitude / hasStartedTracking` 5 个内部死字段；`reset()` 对应 5 行删除；`parseGpsData` 内 38 行 "Tracking Calculation (Non-Critical)" 整块删除（消除 25Hz × `Location.distanceBetween` JNI 开销 + parser 内 `System.currentTimeMillis()` 的错误暗示）；孤儿 `import android.location.Location` 删除；class KDoc 同步更新
  - 新增独立测试文件 `RaceChronoParserTestReadyStateTest`（4 条 R1 契约）+ `RaceChronoParserInternalStateTest`（3 条 R2 反射断言），均严守 kt-format 规则不带 `@IgnoreFormatCheck` 豁免
  - 同步修订 `RaceChronoParserTest.parseGpsTimeData_successPathExplicitlyClearsErrorMessage_sourceAssertion` 源码断言（"≥ 2 次 errorMessage=null" → "≥ 1 次 + 不含 v1 残留"）+ `RP34_parseGpsTimeData_validData` 旧 v1 断言（实施期发现的同类 v1 残留）
  - 不动 `protocolTimeReference` 写入（A8 契约）+ A25 errorMessage 清理 + frequency 计算块 + altitude 编解码（A16b 已核销不得回改）
  - `lastFrequencyUpdateTime` reset 缺失留待 A28 评估，本 change 显式不顺手修
- **验证**：
  - `openspec validate refactor-parser-internal-state-cleanup --strict`：PASS
  - `./gradlew :core:bluetooth:testDebugUnitTest --tests "*RaceChronoParser*" :feature:test:testDebugUnitTest --tests "*EndToEndLapTimingContractTest*" --tests "*LapTimingEngineTest*" --tests "*TestSessionViewModelTrackLapTest*"`：BUILD SUCCESSFUL
  - `rg "totalDistance|hasStartedTracking|startTime|lastLatitude|lastLongitude" core/bluetooth/.../RaceChronoParser.kt`：零命中
  - `rg "isTestReady\s*=\s*true" core/bluetooth/.../RaceChronoParser.kt`：零命中
  - 两个新测试文件 `@IgnoreFormatCheck` grep：零命中
  - A8 既有 `RaceChronoParserProtocolTimeTest` 零回归
- **review 文档依据**：`2026-04-24-opsx-refactor-parser-internal-state-cleanup-code-review.md`
- **存档日期**：2026-04-24

---

### ✅ A15 / A20 / A32 / A33：战役 C 二期判圈契约闭环（`fix-lap-timing-closure-and-precision-contract`）

- **核销**：commit `715a268`（R1/A15）+ `ddfc42a`（R2/R3/A15/A32）+ `1f2e3c3`（R4/R5/R7/A20/A33）+ `b059335`（R6）+ `79c4323`（code review 修订），2026-04-24
- **成果**：
  - A15：`GateCrossingDetection.crossingProgress` 落地；start/finish、sector、lap duration、E2E 合成契约改用过线插值毫秒；STATIC 10 秒圈误差收紧到 `< 5ms`
  - A20：`handleSectorCrossing` 遍历所有 sector 门，期待门优先，非期待门按 `sequenceIndex` 记录 `UnexpectedGateOrder`；3-sector 同帧多门测试硬区分 v1
  - A32：`LapRecord.trajectory` 改为 `subList(sampleStartIndex) + [startedAt, finishedAt)` 时间窗口切分，闭圈帧归下圈；empty trajectory 边界已硬断言
  - A33：`processSample_secondStartFinishCrossing_completesLapEvenWithoutAllSectors` 补 `qualityFlags == [IncompleteSectors]`
- **review 修订闭环**：`79c4323` 关闭 code review 4 条必修：
  - R1 clamp 可达性：`segmentsIntersectMeters` 增加 `FLOAT_BOUNDARY_TOLERANCE = 1e-9`，容差内返回原始 `t`，`detect` 做最终 `coerceIn`
  - R4 多门同帧 / 反序数据源：测试专用 3-sector track 覆盖 `[S1,S2,S3]` 与 `[S3,S2,S1]`
  - R3 empty trajectory：手动构造 `[500,520)` 空窗口，断言 `trajectory.isEmpty()` + `durationMillis == 20L`
- **验证**：
  - `openspec validate fix-lap-timing-closure-and-precision-contract --strict`：PASS
  - `./gradlew :feature:test:testDebugUnitTest --tests "*GateCrossingDetectorTest*" --tests "*LapTimingEngineTest*" --tests "*EndToEndLapTimingContractTest*" --tests "*TestSessionViewModelTrackLapTest*"`：BUILD SUCCESSFUL
  - `./gradlew :core:domain:test`：BUILD SUCCESSFUL
  - tasks §8.7 / §8.8 / §8.11 grep 审计均 PASS
- **显式留痕**：`openspec status` 仍显示 tasks `0/59`，因为 `tasks.md` checkbox 未回填；这属于归档前文档回填事项，不影响本次代码核销结论
- **review 文档依据**：`2026-04-24-opsx-fix-lap-timing-closure-and-precision-contract-spec-tasks-review.md` + `2026-04-24-opsx-fix-lap-timing-closure-and-precision-contract-code-review.md`
- **存档日期**：2026-04-24

---

### ✅ A23 / A24 / A25 / A27 / A29 / A40 / A42 / A45 / A46：战役 G BLE 连接生命周期闭环（`fix-ble-connection-lifecycle`）

- **核销**：commit `027bfb7`（A23/A42）+ `03d8556`（A24/A40）+ `fc2c303`（A25/A27）+ `cff7e30`（A29/A45/A46），2026-04-24
- **成果**：
  - A23 / A42：`ConnectionManager.kt` 整文件删除，空 collect 随死代码消失；`BluetoothGatt` 字段唯一所有者收敛到 `BleConnection`
  - A24 / A40：数据超时加 `ensureActive()`，超时只触发 `disconnect()`，统一在 `STATE_DISCONNECTED` 回调内 `close()` + `null`
  - A25 / A27：两个 parser 路径短包 / catch 对称写 `errorMessage`，成功路径显式清 `errorMessage`；`BluetoothDataSource` 失败帧显式 `isConnected=false`，成功帧才恢复 `true`；新连接前先清旧 job / 旧 GATT
  - A29：`autoReconnectLastDevice` 的 `lastDeviceAddress == null` 分支 fallback 到 `startScan()`
  - A45 / A46：原 review § 11.5 / § 11.6 本地文档修订完成；`.md` 被 `.git/info/exclude` 排除，不进入 git commit
- **验证**：`openspec validate fix-ble-connection-lifecycle --strict` valid；`:core:bluetooth:testDebugUnitTest` / `:core:bluetooth:assembleDebug` / `:core:domain:test :feature:test:testDebugUnitTest :app:compileDebugKotlin` 均 BUILD SUCCESSFUL
- **核销条件修订记录（A23）**：原"真机 15 秒自动重连"已在 2026-04-24 第二轮 proposal review 批准移交 `fix-ble-reconnection-layer`；本战役 G 以 `DISCONNECTED` 状态翻转、超时释放 log、`STATE_DISCONNECTED` 回调释放 GATT 的生命周期 Correctness 条款核销
- **本次核销豁免（P3，不阻塞）**：`BleConnection.kt` 顶部说明注释仍出现 "`ConnectionManager 已删除`" 字样；这不是 class/import/DI/实例化/调用残留，只会影响朴素字面 grep，不影响 A23/A42 的死代码删除闭环
- **review 文档依据**：本次会话 "战役 G final commit review（2026-04-24）" + backlog pending_review 迁档记录
- **存档日期**：2026-04-24

---

### ✅ A1：detector 量纲错位修复（对抗 review 1.1 + 1.7）

- **核销**：commit `acc9192`（战役 B），2026-04-22
- **成果**：米空间投影 + passDirection 归一化，`directionalSpeedMps` 真实 m/s；3 条回归测试 `GateCrossingDetectorTest` 锁定
- **本次核销豁免（按"核销闭环原则"第 2 项）**：
  - 核销时发现 A9（测试注释角度反）/ A10（`METERS_PER_DEGREE_LAT` 契约注释）/ A11（`lonScale` 投影原点契约注释）三条新问题
  - 三条均为**注释级 / P2 代码异味**，不影响量纲错位修复的正确性与闭环
  - 评审方显式声明：A9/A10/A11 拆为独立 🔴 条目不阻塞本次闭环，但 3 条必须在下次 B 战役相关 change 中随手清理
- **review 文档依据**：本次会话 "B 战役 acc9192 code review § BC.1-BC.3" + 当前 backlog A9/A10/A11
- **存档日期**：2026-04-22

---

### ✅ A2 / A3 / A4 / A5：战役 A（`fix-laptime-clock-source-integrity`）主体 4 条一并核销

- **核销**：commit `d71371b`（接收端）+ `3ec0ad7`（发射端）+ `416f6e3`（A6/A7/A8 尾巴一并闭环解锁），2026-04-23
- **成果**：
  - **A2** 双端时间戳污染主链路：parser 未同步写 `Long.MIN_VALUE` sentinel；simulator 走 `SystemClock.elapsedRealtime()`；分层守卫贯穿 filter / preTriggerBuffer / processFilteredData / bridge / updateLaunchStatus
  - **A3** sentinel `Long.MIN_VALUE`：parser 未同步永不 fallback `System.currentTimeMillis()`
  - **A4** 失联恢复重置 `lastLapGpsSample`：`bridgeGpsToLapTiming` 在 `!isTimeSynced` 时重置前驱
  - **A5** `ProtocolDesyncGap` 枚举 + engine 扫描：闭圈时扫描相邻 ts gap 打质量标签
  - `EndToEndLapTimingContractTest` 6 条 E2E 契约全绿锁定
- **曾被拒绝过（闭环原则教学）**：
  - 2026-04-22 曾被评审方错误盖 ✅ → 依"核销闭环原则"降级为 🟢 `pending_review`，因为 A6 阻塞 + A7/A8 未显式豁免
  - 2026-04-23 对方提交 commit `416f6e3` 实现 A6/A7/A8 → 评审方完整审后盖 ✅，捆绑解锁 A2-A5
- **review 文档依据**：`2026-04-22-opsx-fix-laptime-clock-source-code-review.md` + `2026-04-23` 本次 A6/A7/A8 代码复审
- **存档日期**：2026-04-23（初次盖章 2026-04-22，因闭环不全降级；本次重新核销）

---

### ✅ A6：`processFilteredData` Running 分支 `isTimeSynced` 守卫（战役 A 尾巴）

- **核销**：commit `416f6e3`，2026-04-23
- **成果**：
  - `TestSessionViewModel.kt:225-233` Running 分支开头加 `if (!filteredData.raw.isTimeSynced) return`
  - spec Scenario 3.5.3 拆 Preparing / Running 对称；Requirement 3.5 (a) 描述同步修订
  - 新测试 `processFilteredData_runningPhase_ignoresUnsyncedFrames`：前置 3 静止 + 5 加速同步帧把状态推到 Running → 喂 5 帧 sentinel → 断言 `session.dataPoints` 不增长 + `_testState` 仍 Running
  - 核销条件 (1)(2)(3) 全满足
- **存档日期**：2026-04-23

---

### ✅ A7：`ProtocolDesyncGap` 阈值参数化（战役 A 尾巴）

- **核销**：commit `416f6e3`，2026-04-23（选方案 A = 参数化）
- **成果**：
  - `LapTimingEngine` 构造加 `expectedIntervalMillis: Long = 40L` + `DEFAULT_EXPECTED_INTERVAL_MILLIS = 40L` / `DESYNC_GAP_FACTOR = 5L`
  - 阈值 = `interval × 5`：25Hz 默认 200ms（与 v1 完全一致）；5Hz 自动 1000ms 不假阳性
  - 新测试 `processSample_lapWithCustomInterval5Hz_doesNotFlagDesyncAt200ms`：构造 5Hz engine + 6 帧 `[200, 201, 199, 200, 201, 200]` 间隔 + 闭圈 → 断言 `qualityFlags` 不含 `ProtocolDesyncGap`
  - spec Requirement 3 修订 + 2 条 Scenario（25Hz 默认 + 5Hz 回归）
  - `AppModule` / E2E / Track 相关测试仍用默认 40L，零行为回归
- **存档日期**：2026-04-23

---

### ✅ A8：`RaceChronoParser.isCurrentlyTimeSynced` 字段删除（战役 A 尾巴）

- **核销**：commit `416f6e3`，2026-04-23
- **成果**：
  - 删除 `private var isCurrentlyTimeSynced` 字段
  - `reset()` 删对它的清零（注释说明单源派生自 `protocolTimeReference`）
  - `parseGpsData` 改局部 `val syncedNow = reference != null && reference.syncBits == syncBits`，直接赋给 `GpsData.copy(isTimeSynced = syncedNow)`
  - 全仓代码层面无残留（grep 干净）
  - 核销条件 (1)(2)(3)(4) 全满足
- **本次核销豁免（按"核销闭环原则"第 2 项）**：
  - 核销时发现 **N1**：`RaceChronoParserTest.kt:576` 测试注释里还引用 "`isCurrentlyTimeSynced`"（D 战役 `f869f27` 引入，**非本 commit 引入**）
  - 属于 **P3 文档级浮尘**，不影响生产 / 测试行为
  - 评审方显式声明：N1 拆为独立条目 **A54** 🔴 持续追踪，不阻塞本次 A8 核销
- **存档日期**：2026-04-23

---

### ✅ A9 / A10 / A11：战役 B 尾巴 3 条注释契约清理

- **核销**：commit `50939d2`，2026-04-23
- **成果**：
  - **A9** `GateCrossingDetectorTest.detect_nearParallelCrossing_acceptsStably`：注释改为 "位移与 passDirection 夹角 85°（即与 gate 线夹角 ~5°，接近平行于 gate 线）"；断言文案同步修订；功能代码 / 旋转逻辑不动
  - **A10** `METERS_PER_DEGREE_LAT` 三段精度契约：accept/reject 可忽略 / m/s 读数 ±0.5% @ ±45° 纬度 / <0.1% 需 WGS84 椭球
  - **A11** `lonScale` 投影原点隐式假设：声明 "gate 线 <1km 且 prev/curr 在 gate 附近 <1km 半径"；超出半径 >0.01% 形变度量
  - `GateCrossingDetectorTest` 8/8 全绿；零代码行为变更
  - A1 核销时的"显式豁免"条款（"必须在下次 B 战役相关 change 中随手清理"）兑现
- **存档日期**：2026-04-23

---

### ✅ A12 / A13 / A14：战役 C filter 夯实三连闭环（`fix-gps-data-filter-signal-loss-and-anomaly-hygiene`）

- **核销**：commit `52c1850`，2026-04-23
- **成果**：
  - **A12** 信号丢失重置前置：`process()` 顺序改为"重置 → 三个判定"；失联首帧 `previousRaw=null`，`calculateAcceleration / isPhysicalConstraintViolation / checkPositionVelocityConsistency` 全部走早退（0.0 / false / (1.0, false)），把本帧作为新基准
  - **A13** 异常帧不更新 previousRaw：`if (!isAnomaly && !isPositionAnomaly) { previousRaw = raw; previousPosition = ... }`；依赖 A12 的 dt>200ms 兜底防连续异常锁死
  - **A14** 简化分支 + 异常帧不入窗口：`outputSpeed = if (size >= 3) median else raw` 单分支；speed/lat/lon window 按异常类型条件 append；bearing 不受影响（circularMedian 向量均值鲁棒性够用）；选方案 a（简化不引入新状态）
- **A12 / A13 测试强度评估**：
  - `A12_process_signalLossLongerThanThreshold_...` 和 `A12_process_signalLossThenLargeSpeedJump_...`：**断言硬区分 v1/v2**（v1 下 `maxDelta=90×0.5=45` 压制 300km/h 跳变；v2 下 previousRaw=null 早退判 false）
  - `A13_process_singleSpikeThenRecovery_...`：恢复帧 speed=32, dt=40ms, maxDelta=3.6 — v1 `previousRaw.speed=200`→dv=168→isAnomaly；v2 `previousRaw.speed=30`→dv=2→normal。**能硬区分**
  - `A13_process_continuousAnomaly_previousRawEventuallyResetsByA12Guard`：通过 ts=500ms 跨 A12 阈值强制重置，验证 A12×A13 联动 ✓
- **本次核销豁免（按"核销闭环原则"第 2 项）**：
  - **N2 → 独立条目 A55** 🔴 追踪：A14 的两条新测试断言无法硬区分"A14 生效"vs"A14 未生效"（中位数对单个离群天然鲁棒，单 spike 拉不动 median）。A14 代码层面真的做了（diff 清楚看到条件 append），但测试不能回归保护该行为。P2 测试强度问题，独立追踪不阻塞本次核销
  - **N3 → 顺手清理**：`TestSessionViewModelTrackLapTest.processFilteredData_runningPhase_ignoresUnsyncedFrames` 断言注释 "前 8 帧应让状态转入 Running" 未同步更新为 "前 9 帧"（3 静止 + 6 加速）。P3 文档浮尘，建议下次该测试被改动时顺手清理，不独立立项
  - **N4 → 以后表述准一点**：commit message "A12 重置消耗 1 触发计数" 解释不严格 —— 实际 `checkAccelerationTrigger` 的 counter++ 条件是 `isAccelerating || isMoving`，speed=10>1 的加速首帧仍会 counter++。加速帧 +1 是防御性富余，不是严格必要。建议对方今后 commit message 的根因解释与代码行为精准对齐
- **review 文档依据**：本次会话 "战役 C filter 夯实 commit 52c1850 评审意见（2026-04-23）"
- **存档日期**：2026-04-23

---

### ✅ A54：`RaceChronoParserTest.kt:576` 测试注释残留清理（战役 A 尾巴 N1 浮尘）

- **核销**：commit `9744576`，2026-04-23 预批 + 2026-04-24 正式核销
- **流程**：2026-04-23 评审方基于工作区 diff 预批；2026-04-24 评审方复核 commit 与预批 diff 一致（`git show 9744576`：1 文件 +1/-1，注释修订语义完全一致），正式核销
- **成果**：
  - `RaceChronoParserTest.kt:576` 注释里"`和 isCurrentlyTimeSynced`"去掉，改为"`protocolTimeReference`（单源派生 `isTimeSynced`）"，与 A8 代码删除字段后的实际状态对齐
  - `./gradlew :core:bluetooth:testDebugUnitTest --tests RaceChronoParserTest.RP36_reset_clearsState` BUILD SUCCESSFUL
  - 核销条件 (1)(2) 全满足
- **存档日期**：2026-04-23

---

### ✅ A55：A14 测试强度强化（战役 C 尾巴 N2 测试硬区分）

- **核销**：commit `5e53f0f`，2026-04-23 预批 + 2026-04-24 正式核销
- **流程**：2026-04-23 评审方基于工作区 diff 做数值边界逐帧验算后预批；2026-04-24 评审方复核 commit 与预批 diff 一致（`git show 5e53f0f`：硬区分断言 60/300、1e-5 阈值、ts=230 dt=190ms、两条测试方法名完整保留），正式核销
- **成果**：
  - 选方案 (b) —— 新增 2 条"2 正常 + 4 连 spike + 1 恢复"用例，硬区分"spike 入窗口"vs"spike 不入窗口"两种实现：
    - `A14_process_multipleAnomalyFrames_medianHardDistinguishesWindowExclusion`（speedWindow 版）：A14 生效 median=60，A14 失效 median=300
    - `A14_process_multiplePositionAnomalyFrames_latLonMedianHardDistinguishesWindowExclusion`（latWindow/lonWindow 版）：A14 生效偏离 ≈1e-6，A14 失效偏离 ≈1e-4，阈值 1e-5 硬区分
  - 原两条单 spike 用例注释更新为"中位数对单 spike 的鲁棒性验证"并指向硬区分用例，保留作为鲁棒性语义的正向断言
  - 依赖保证：4 连 spike 相对 `previousRaw(ts=40)` dt 为 40/80/120/160ms 均 <200ms（A12 不重置），dv=240 恒 >> maxDelta=90×dt（4 帧均 isAnomaly=true）
  - `./gradlew :core:domain:test` BUILD SUCCESSFUL，原有 35 条 + 新增 2 条全部通过
  - 与 A14 本体无代码回滚，仅强化回归保护
- **存档日期**：2026-04-23

---

### ✅ A31：测试回迁 + DI JVM fallback + parser 40 用例挖回（战役 D）

- **核销**：commit `f869f27`（战役 D），2026-04-23（评审方于 2026-04-23 完整读 diff 后盖章）
- **成果**：
  - `GpsDataFilterTest` 29 用例（27 原 + 2 条战役 A 分层守卫用例合并）迁到 `core.domain`
  - `TestSessionViewModelTest` TS01-TS08 迁到 `feature.test`，`createGpsData` 添加 `fixQuality = 1` + `isTimeSynced = true` 适配战役 A 守卫（断言体无改动）
  - `RaceChronoParserTest` 40 用例回挖（37 pass + 3 @Ignore），RP33/RP36 从 `timestamp > 0` 改为字段级断言（`satellites == 10` / `fixQuality == 1`），适配战役 A sentinel，语义更严格
  - `DomainModuleKoinTest.providesTrackCatalog` 通过 DI JVM fallback 修复
  - `app/src/test/java` 目录清空，3 个文件通过 git R90/R85 rename 迁移到正确新位置，无漏迁
- **本次核销豁免（按"核销闭环原则"第 2 项）**：
  - **A16**（parser 3 个 @Ignore 暴露 signed int bug + altitude overflow 协议解读差异）：
    - 评审方已核实：RP16/RP19 是 `latInt.toLong() and 0xFFFFFFFFL` 把 signed int32 抹成 unsigned 的真 bug（如 `-33.8688° → 395.63°`），RP22 是协议 spec 解读差异
    - **不属于本战役 scope**（本战役是测试迁移，不是 parser 实现修复）
    - @Ignore 标注 + 详细注释 + 独立条目 A16 跟踪 = 正确处理方式
    - 评审方显式声明：A16 保持 🔴 独立追踪（P0/P1 级 bug，独立战役必修），但不阻塞本次核销
  - **A17**（DI fallback runCatching catch 范围过宽）：
    - 评审方已核实：`runCatching` 仅包裹 `ReplayAlignedTrackCatalog(...)` 构造函数，不涉及 IO；真机环境构造几乎不抛异常，`runCatching` else 分支实际不触发
    - 风险：若未来有人在 `AssetReplayTrackSource` 构造里加 IO 预加载，catch 会静默吞真机异常
    - 评审方显式声明：A17 保持 🔴 独立追踪（P2 代码异味，建议把 `runCatching` 收紧到 Koin 特定异常类型），不阻塞本次核销
- **review 文档依据**：本次会话 "D 战役 f869f27 code review（2026-04-23）" + 当前 backlog A16/A17
- **存档日期**：2026-04-23

---

### ✅ A19 / A21 / A34 / A38：战役 C engine 入口夯实四条一并核销（`fix-lap-timing-engine-entry-hardening`）

- **核销**：commit `a2c9bae`（engine 组 R1+R2+R3）+ `32d65c5`（bridge 组 R4 + A34 顺手清理），2026-04-24
- **成果**：
  - **A19** engine 入口 `LapSessionStatus` **白名单**守卫：`status !in setOf(Ready, Recording)` → return session。提案评审阶段采纳白名单（而非黑名单），防御"开放默认不安全"反模式 —— 未来新增枚举值默认被拦
  - **A21** engine 入口 ts 单调守卫：`currentSample.ts < previousSample.ts` → return session。对比基准用**方法参数** `previousSample.timestampMillis`（永远非空）而非 `session.samples.lastOrNull()`，与 A38 bridge 层对称；绕过 bridge 直接调 engine 时兜底
  - **A21 裁剪层** `dropWhile → filter`：`crossingEvents.filter { ts >= startedAtMillis }` 逐元素判定，拒绝"只检查前缀首个"的单调假设
  - **A38** bridge 三段式：首样本（赋 `lastLapGpsSample` 为下一帧准备基准）/ ts 回跳（**不**赋 `lastLapGpsSample`，截断污染源）/ 正常推进（赋 + 喂 engine）。与 A13 "异常帧不更新 previousRaw" 模式一致
  - **A34 顺手清理**：在 A38 改造同一首样本分支删除 `_lapSession.value = currentSession` 死码（StateFlow 相同引用不 emit，留着未来换 SharedFlow 会引爆）
  - 合流门槛 6 条全绿：`openspec-chinese validate --strict` ✓ / `LapTimingEngineTest` 10 → 20 ✓ / `TestSessionViewModelTrackLapTest` 13 → 16 ✓ / `EndToEndLapTimingContractTest` 6/6 零回归 ✓ / `core.domain + core.bluetooth` 零下游回归 ✓
- **测试强度**：
  - R1 × 5（Finished/Cancelled/Idle 拦下 + Ready/Recording 放行）硬区分枚举矩阵
  - R2 × 2（ts 回跳返回不变 + 首次起圈空 session 不误拦）
  - R3 × 3（filter 单调正向语义含边界 ≥ / 非单调硬区分 v1 dropWhile / **单调防退化**：测试内同时跑 filter 与 dropWhile 对照锁定等价）
  - R4 × 3（首样本段 1 赋值 / ts 回跳段 2 丢弃 / **回跳后恢复硬区分**：探测帧 ts=1_020 介于回跳帧 900 与前帧 1_040 之间，samples.size 硬区分 lastLapGpsSample 是否被污染）
- **评审纪律落实**：
  - Proposal 两轮 review（建议 2 🔴 + 3 🟡 全盘采纳）
  - Spec + tasks 三轮 review（R3 Scenario 从 2 拆 3 + Scenario 3 防退化设计超预期；R3 测试数量同步到 10 条）
  - 代码 commit 后评审方完整审 diff 对齐 spec，**0 个新问题**
- **核销条件修订记录（A21）**：
  - 原 backlog A21 核销条件 (2) 写 `processSample` 入口 ts 守卫对比基准用 `session.samples.lastOrNull()?.timestampMillis ?: Long.MIN_VALUE`
  - 评审方在 Proposal review 第二轮 🟡 问题 3 建议改为 `previousSample.timestampMillis`（方法参数永远非空，与 engine 契约"对比 previous 与 current"直接对应，与 A38 bridge 层语义对称）
  - 实施方采纳修订方案；最终 commit `a2c9bae` 的守卫基准是 `previousSample.timestampMillis`
  - 按核销闭环原则第 4 项（程序透明），此修订在存档层面显式留痕，防未来回看 backlog 时发现"实施与原核销条件字面不符"产生困惑
- **scope 边界声明（A21 裁剪层）**：
  - 本战役 `dropWhile → filter` 的替换**仅限** `LapTimingEngine.handleStartFinishCrossing` 内 `LapRecord.crossingEvents` 的闭圈裁剪
  - 全仓 grep 确认：当前 engine 代码里已无实际 `dropWhile` 调用（仅注释里作反面对照引用）
  - **未来若在 `LapTimingEngine` / `GateCrossingDetector` 或相关调用链新增 `dropWhile`，本战役的"filter 严格语义"契约不自动扩展**，需独立 change 重审
- **相关 Non-goals 履行 + 后续战役指向**：
  - **A33**（`LapTimingEngineTest.processSample_secondStartFinishCrossing_completesLapEvenWithoutAllSectors` 未断言 `qualityFlags = listOf(IncompleteSectors)`）按 Proposal Non-goals 保持 🔴
  - 承诺下家：**判圈契约战役**（候选 change 名 `fix-lap-timing-closure-contract`），捆绑 **A20**（多门同帧丢失）+ **A32**（闭圈帧重叠契约）+ **A33**（测试断言补齐），三条互相依赖的判圈算法级修复一次拍板
  - 判圈契约战役必须先写 proposal 拍契约再动代码（engine-entry-hardening Alternatives § C 已声明）
- **review 文档依据**：本次会话 "fix-lap-timing-engine-entry-hardening" 的 proposal review（2026-04-23）+ spec/tasks review（2026-04-23）+ mini review（2026-04-23）+ commit diff 第四轮 review（2026-04-24）+ 评审方自查挑刺（2026-04-24 A 方案补充）
- **存档日期**：2026-04-24

## 六、🟣 `proposal-needed` — 功能规划（需先起 proposal 再进 pending）

> 本节条目**非 bug 修复**，属于数据模型 / 功能能力扩展。需要实施方先起 `openspec/changes/...` proposal，评审方核销 proposal + spec 后再转 🔴 `pending` 进入施工。

### A47：`SectorSegment` 数据模型升级（Phase 1 / 功能规划）

- **来源**：功能设计讨论（2026-04-22）
- **当前模型**：`LapRecord.sectorTimes: List<Long>`，只有时长，无 sector 内部统计
- **需求攻击点**：
  - 无法回答"为什么这段慢"（没 sector 内 max/min/avg speed）
  - `trajectory` 未按 sector 切片，每次分析 O(N) 重算
  - `SectorEntry` 无 sectorIndex 快速索引
- **proposal 要定义**：
  - 新 `SectorSegment(sectorIndex, entryGateId, exitGateId, startedAtMillis, endedAtMillis, durationMillis, sampleIndexRange, maxSpeedKmh, minSpeedKmh, avgSpeedKmh)` 数据类
  - `LapRecord.sectors: List<SectorSegment>` 替代 `sectorTimes`
  - 派生属性 `LapRecord.sectorTimes` 保留向后兼容（由 `sectors.map { it.durationMillis }` 计算）
- **状态**：🟢 `pending_review`（代码 review 已通过；等待 §10.1-10.3 真机 manual gates + commit hash 后存档）

---

### A48：`LapRecord` 加 `maxSpeedKmh` / `minSpeedKmh` / `maxSpeedAtMillis` （Phase 1 / 功能规划）

- **来源**：功能设计讨论（2026-04-22）
- **当前缺口**：UI 每次展示"最高速度"都要遍历 trajectory，O(N) 每次访问
- **需求攻击点**：圈级统计字段缺失，UI 和分析都要重算；图表高亮需要 `maxSpeedAtMillis` 定位
- **proposal 要定义**：
  - `LapRecord.maxSpeedKmh: Double`
  - `LapRecord.maxSpeedAtMillis: Long`（时刻，供图表高亮）
  - `LapRecord.maxSpeedLocation: GeoPoint`（地图标记点）
  - `minSpeedKmh` 同理（注意不是"最低湾速"，是整圈最低；"最低湾速"由 A50 单独处理）
  - engine 闭圈构造 `LapRecord` 时一次性遍历 `trajectory` 计算所有字段
- **状态**：🟣 `proposal-needed`

---

### A49：`Track.corners` + `Corner` 模型 （Phase 2 / 功能规划）

- **来源**：功能设计讨论（2026-04-22）
- **当前缺口**：`Track` 只有 `referencePath` + `startFinishGate` + `sectorGates`，没有弯道概念
- **需求攻击点**：要实现"最低湾速"必须先定义"弯道是什么"（entry / apex / exit）
- **proposal 要定义**：
  - `Corner(id, name, apex: GeoPoint, referenceSpeedKmh: Double? = null)`
  - `Track.corners: List<Corner> = emptyList()`
  - TFIC preset 手工标注 apex 位置（从 referencePath 挑几个点）
  - 识别策略三选一：(a) 完全数据驱动（bearing 变化率阈值）/ (b) 完全预置（手工标 apex）/ (c) 混合（apex 预置，entry/exit 数据驱动）
- **状态**：🟣 `proposal-needed`

---

### A50：`CornerSpeedRecord` 最低湾速 （Phase 2 / 功能规划，依赖 A49）

- **来源**：功能设计讨论（2026-04-22）
- **依赖**：A49（Corner 模型必须先定义）
- **需求攻击点**：最低湾速 ≠ 整圈最低速度（不能被起跑低速污染），必须限定在弯道内
- **proposal 要定义**：
  - `CornerSpeedRecord(cornerId, minSpeedKmh, minSpeedAtMillis, apexSampleIndex, entrySpeedKmh, exitSpeedKmh)`
  - `LapRecord.cornerSpeeds: List<CornerSpeedRecord>`
  - engine 闭圈时遍历 trajectory，对每个 corner 找几何距离最近的 sample 作 apex，提取 ±N 帧窗口求 min/entry/exit
- **状态**：🟣 `proposal-needed`（依赖 A49）

---

### A51：`GpsSample.progressMeters` + `LapRecord.progressTimeline` （Phase 2 / 功能规划基础设施）

- **来源**：功能设计讨论（2026-04-22）
- **需求攻击点**：实时 delta 的基础 —— "沿参考轨迹的累计距离"索引
- **proposal 要定义**：
  - `ProgressCheckpoint(progressMeters: Double, elapsedMillisSinceStart: Long, speedKmh: Double, location: GeoPoint)`
  - `LapRecord.progressTimeline: List<ProgressCheckpoint>`（与 trajectory 同长，预计算）
  - "投影到参考轨迹"算法：最近邻 + 线段投影，二分查找 O(log N)
  - 与 A22（UI haversine 增量累积）合并：engine 层维护 `progressMeters`，UI 只读
- **状态**：🟣 `proposal-needed`（支撑 A52）

---

### A52：`LapSession.referenceLap` + `currentDeltaMillis` 实时秒差 （Phase 2 / 功能规划核心，依赖 A51）

- **来源**：功能设计讨论（2026-04-22）—— 最高价值实时功能
- **依赖**：A51（progressTimeline 基础设施）
- **需求攻击点**：业界顶级功能（VBOX Touch / RaceChrono Pro），当前数据结构完全不支持
- **proposal 要定义**：
  - `ReferenceLapStrategy` 枚举：`PersonalBest / PreviousLap / UserPinned / TheoreticalBest`
  - `LapSession.referenceLap: LapRecord? = null`
  - `LapSession.referenceStrategy: ReferenceLapStrategy = PersonalBest`
  - `LapSession.currentDeltaMillis: Long? = null` + `currentProgressMeters: Double? = null`
  - 实时查表：`delta = current.elapsed - ref.elapsedAt(current.progress)`，25Hz × O(log N) 无压力
- **状态**：🟣 `proposal-needed`（依赖 A51）

---

### A53：`LapSession.predictedLapTimeMillis` 预测圈速 （Phase 3 / 功能规划，依赖 A52）

- **来源**：功能设计讨论（2026-04-22）
- **依赖**：A52（delta 基础设施完备后零额外基础设施）
- **需求攻击点**：顶级用户体验 —— 跑过中途就能预估本圈终点时间
- **proposal 要定义**：
  - `LapSession.predictedLapTimeMillis: Long? = null`（派生字段）
  - 基础公式：`predicted = current.elapsed + (ref.totalTime - ref.elapsedAt(current.progress))`
  - 可选进阶：最近 N 秒 delta 趋势外推
  - 仅需 ViewModel 层派生，无持久化新结构
- **状态**：🟣 `proposal-needed`（依赖 A52）

---

### A56：密集 GPS 点阵持久化架构（Room metadata + chunked telemetry storage）（Phase 0 / 架构规划）

- **来源**：架构设计讨论（2026-04-25）
- **背景量级**：25Hz × 25min ≈ 37,500 点；25Hz × 1h ≈ 90,000 点。若以 Kotlin `GpsSample` / `GpsData` 对象长期常驻内存，多圈 / 多 session 会迅速膨胀；若一条 sample 一行写 Room，也会带来写放大、查询分页和迁移成本。
- **依赖关系**：作为 A47-A53 的上游数据层约束；不阻塞 A22，但 A22 proposal 必须声明"本轮不固化长期轨迹存储模型"。
- **需求攻击点**：
  - `LapRecord.trajectory: List<GpsSample>` 适合运行期 / 短期 UI，不适合作为长期持久化真相源
  - 后续 A47 sector stats、A51 progressTimeline、A52 reference delta、A53 prediction 都需要可分页 / 可索引 / 可派生的轨迹存储
  - 需要在"数据库索引"与"密集点阵存储"之间划清职责，避免把高频 telemetry 直接对象化常驻内存
- **proposal 要定义**：
  - Room / SQLite 只存 metadata：`SessionEntity` / `LapEntity` / summary / chunk index / schema version / checksum
  - 密集 telemetry 用 chunked storage：二进制文件或 Room BLOB chunk（二者需对比拍板），例如 5-10s 一个 chunk，含 start/end ts、sampleCount、min/max lat/lon/speed 等索引
  - sample 编码：timestamp delta、lat/lon int32（deg × 1e7）、speed/bearing 定点数、flags 小整数；拒收 JSON 作为长期高频点阵格式
  - 运行时内存策略：active lap 只保留工作窗口 / 当前增量状态；完成圈 flush chunk；历史 UI 按 viewport / lap / time range 懒加载
  - 派生数据策略：sector stats、downsampled chart、progressTimeline 可作为 summary/chunk 派生物，不每次从全量点阵重算
  - 兼容 / 迁移：版本化 chunk header + Room migration 策略；损坏 chunk 的 fallback 行为
- **Non-goal**：
  - 不在 A22 中实现持久化
  - 不把 `LapRecord.trajectory` 立即删除；先定义长期替代方案，再分阶段迁移
  - 不提前实现 A52/A53 的 reference lap / prediction，只提供数据层基础约束
- **状态**：🟣 `proposal-needed`
- **最近动作**：
  - 2026-04-29 `unify-gps-telemetry-persistence` ff artifacts review 暂不放行 `/opsx:apply`：旧 `TestDataFileStorage` 消费点未完整迁移、binary header 更新与 append-only/no-seek 设计冲突、`flush()` / `close()` 缺少确定性 ack、`CrossingEventEntity` 字段裁剪过度。详见 [2026-04-29-opsx-unify-gps-telemetry-persistence-ff-review.md](./2026-04-29-opsx-unify-gps-telemetry-persistence-ff-review.md)。
  - 2026-04-29 Round 2 mini review 仍暂不放行：旧消费者迁移 / ack actor / CrossingEvent 字段已基本收敛，但 footer 方案写成 flush/close 多次追加 footer，会把 footer 插入 sample 流导致后续 17-byte sample 对齐污染；且 header size 10 bytes 与恢复公式 `22 + N×17` 残留冲突。需重新拍板 footer 只在 close 写、sidecar/Room metadata、或 framed format。详见同 review 文档 §4。
  - 2026-04-29 Round 3 mini review 通过：footer 方案已废弃，改为固定 22-byte header + N×17 samples，无 footer；flush/close 通过 `RandomAccessFile` / `FileChannel` seek 回写 `sampleCount/endTs`；崩溃恢复按 `actualCount = floor((fileSize - 22)/17)` 与 `validCount = min(header.sampleCount, actualCount)`；测试任务覆盖多次 flush 无污染、半条 sample 截断、header count > actual 截断。允许进入 `/opsx:apply`，代码落地后按 A56 核销。
  - 2026-04-29 code review 暂不核销：`feature:test` 单测编译失败（`TestSessionViewModel` 新增 `telemetryRepository` 依赖未迁完）；ViewModel 多路 `launch` 写 telemetry 与 `startSession/endSession` 存在无序竞态，会静默丢帧；CrossingEvent 闭圈事件 `lapIndex` 可能写成下一圈；删除 test result 不清理 binary telemetry 文件。详见 [2026-04-29-opsx-unify-gps-telemetry-persistence-code-review.md](./2026-04-29-opsx-unify-gps-telemetry-persistence-code-review.md)。
  - 2026-04-29 code review v2 仍暂不核销：编译阻断与删除孤儿文件方向已修；但 `startSession()` 仍在后台 `launch`，`active*StartTs` 先置非空，首批帧仍可能在 writer 未 ready 时被 `writeSample()` 静默吞掉；`lapIndex` 统一改用 `currentSession.currentLapIndex` 后，首次开圈 crossing 会写成 0。详见同 code review 文档 §V2。
  - 2026-04-29 code review v3 通过：性能测试 `startSession()` + pre-trigger 写入 inline，圈速 session 在首个正常推进帧 inline 懒启动 writer；`lapIndexForCrossing()` 覆盖开圈 / 闭圈 / sector 语义；`feature:test` 全测、`core:data` 全测、OpenSpec validate、旧 JSON 路径 grep 均通过。剩余 §10.1-10.3 真机 manual gates 与 commit hash 回填后可迁 ✅ 存档。

---

## 附录：攻击点编号总览

| ID | 主题 | 状态 | 战役归属 |
|---|---|---|---|
| A1 | detector 量纲错位 | ✅（A9/A10/A11 显式豁免，已随 50939d2 兑现） | B |
| A2 | 双端时间戳污染主链路 | ✅（捆绑 A6/A7/A8 闭环） | A |
| A3 | sentinel `Long.MIN_VALUE` | ✅（捆绑 A6/A7/A8 闭环） | A |
| A4 | 失联恢复重置前驱 | ✅（捆绑 A6/A7/A8 闭环） | A |
| A5 | ProtocolDesyncGap 枚举 | ✅（捆绑 A7 阈值参数化闭环） | A |
| A6 | processFilteredData Running 守卫 | ✅（commit 416f6e3） | A 尾巴 |
| A7 | ProtocolDesyncGap 200ms 阈值 | ✅（commit 416f6e3，方案 A 参数化） | A 尾巴 |
| A8 | isCurrentlyTimeSynced 字段冗余 | ✅（commit 416f6e3，A54 显式豁免） | A 尾巴 |
| A9 | detector 测试注释角度反 | ✅（commit 50939d2） | B 尾巴 |
| A10 | METERS_PER_DEGREE_LAT 契约 | ✅（commit 50939d2） | B 尾巴 |
| A11 | lonScale 投影原点契约 | ✅（commit 50939d2） | B 尾巴 |
| A12 | filter 信号丢失重置顺序 | ✅（commit 52c1850） | C (filter) |
| A13 | filter 异常帧污染 previousRaw | ✅（commit 52c1850） | C (filter) |
| A14 | filter isAnomaly 分支冗余 | ✅（commit 52c1850，A55 显式豁免） | C (filter) |
| A15 | 穿线时刻线性插值 | ✅（commit 715a268 + ddfc42a + b059335 + 79c4323，战役 C 二期） | C 二期 |
| A16a | parser lat/lon signed int32（RP16/RP19） | ✅（commit f097478，战役 D 尾巴） | D 尾巴（fix-parser-signed-int-decoding） |
| A16b | altitude 四方契约不一致（RP22 + ino 截断） | ✅（commit 19e5b75 + 428a113 + 29ab58e，战役 D 尾巴） | D 尾巴（fix-altitude-encoding-contract-alignment） |
| A17 | DI fallback 真机静默降级 | ✅（commit fcc61cc，Session 2 Round 4 `fix-di-fallback-and-anomaly-island-cleanup`，与 A30 合并） | D 尾巴 |
| A18 | FileLogger 主线程同步 I/O | ✅（commit da3f537 + d3e2496 + ac1bdc8，`fix-file-logger-and-engine-coord-hygiene`，与 A39 合并） | F (性能) |
| A19 | engine LapSessionStatus 白名单守卫 | ✅（commit a2c9bae） | C (engine) |
| A20 | 多门同帧丢失 | ✅（commit 1f2e3c3 + 79c4323，战役 C 二期） | C (engine) |
| A21 | engine 入口 ts 守卫 + crossingEvents filter 严格语义 | ✅（commit a2c9bae） | C (engine) |
| A22 | UI 全量 haversine 性能 | ✅（Session 2 Round 3 `fix-active-lap-distance-accumulator`，commit 0321190 + a8e2377） | F (性能) |
| A23 | ConnectionManager 死代码 | ✅（commit 027bfb7，战役 G） | G (BLE) |
| A24 | BleConnection 超时不 close + race | ✅（commit 03d8556，战役 G） | G (BLE) |
| A25 | BluetoothDataSource isConnected 污染 | ✅（commit fc2c303，战役 G） | G (BLE) |
| A26 | parseGpsTimeData 写 isTestReady 冲突 | ✅（commit 9335ce0，战役 H 一期 R1 / refactor-parser-internal-state-cleanup） | H (parser) |
| A27 | BluetoothDataSource.connect 不清旧 | ✅（commit fc2c303，战役 G） | G (BLE) |
| A28 | frequency 累积平均退化 | ✅（commit ebaf394 + fabb285，战役 F Round 2，与 A37 合并） | F (性能) |
| A29 | BleDeviceManager else 不扫描 | ✅（commit cff7e30，战役 G） | G (BLE) |
| A30 | AnomalyDetector / DataInterpolator 孤岛 | ✅（commit fcc61cc，Session 2 Round 4 `fix-di-fallback-and-anomaly-island-cleanup`，与 A17 合并） | H (清理) |
| A31 | 测试回迁 + DI fallback + parser 40 用例 | ✅（A16/A17 显式豁免） | D |
| A32 | sampleStartIndex 首样本 / 闭圈帧重叠 | ✅（commit ddfc42a + 79c4323，战役 C 二期） | C (engine) |
| A33 | LapTimingEngineTest 漏断言 qualityFlags | ✅（commit 1f2e3c3，战役 C 二期） | C (测试) |
| A34 | bridgeGpsToLapTiming 冗余赋值死码 | ✅（commit 32d65c5，随 A38 顺手清理） | C (清理) |
| A35 | UI currentLap +1 与 Ready 冲突 | 🔴 | I (UI) |
| A36 | engine 两处重复 sector sort | ✅（commit 7ee9122，战役 C 三期） | C (清理) |
| A37 | ReplayAlignedTrackCatalog lazy 主线程 I/O | ✅（commit ebaf394 + fabb285，战役 F Round 2，与 A28 合并） | F (性能) |
| A38 | bridge 三段式 ts 单调守卫 | ✅（commit 32d65c5） | C (engine) |
| A39 | engine 日志完整坐标 隐私/体量 | ✅（commit da3f537 + d3e2496 + ac1bdc8，`fix-file-logger-and-engine-coord-hygiene`，与 A18 合并） | F (日志) |
| A40 | BleConnection disconnect close 时机 | ✅（commit 03d8556，战役 G） | G (BLE) |
| A41 | parser totalDistance 死状态 | ✅（commit 3051253，战役 H 一期 R2 / refactor-parser-internal-state-cleanup） | H (parser) |
| A42 | ConnectionManager init 空 collect | ✅（commit 027bfb7，依附 A23 自动闭环） | G (依赖 A23) |
| A43 | circularMean 命名纠偏 | ✅（commit 7ee9122 + 48837a4 + 9605258，战役 C 三期） | C (filter) |
| A44 | filter 跨经度不处理 | ✅（commit 7ee9122，战役 C 三期） | C (filter) |
| A45 | 修订 review § 11.5 ConnectionManager 描述 | ✅（commit cff7e30，战役 G 捆绑） | Z (文档) |
| A46 | 修订 review § 11.6 autoReconnect 描述 | ✅（commit cff7e30，战役 G 捆绑） | Z (文档) |
| **A47** | **SectorSegment 数据模型升级** | 🟣 | Phase 1 (规划) |
| **A48** | **LapRecord 加 maxSpeed/minSpeed 字段** | 🟣 | Phase 1 (规划) |
| **A49** | **Track.corners + Corner 模型** | 🟣 | Phase 2 (规划) |
| **A50** | **CornerSpeedRecord 最低湾速** | 🟣 | Phase 2 (规划, 依赖 A49) |
| **A51** | **progressMeters + progressTimeline** | 🟣 | Phase 2 (规划) |
| **A52** | **referenceLap + 实时秒差 delta** | 🟣 | Phase 2 (规划, 依赖 A51) |
| **A53** | **predictedLapTimeMillis 预测圈速** | 🟣 | Phase 3 (规划, 依赖 A52) |
| A54 | RaceChronoParserTest 注释残留 isCurrentlyTimeSynced | ✅（commit 9744576） | A 尾巴 N1 浮尘 |
| A55 | A14 两条测试断言无法硬区分异常帧是否入窗口 | ✅（commit 5e53f0f） | C 尾巴 N2 测试强度 |
| **A56** | **密集 GPS 点阵持久化架构** | 🟢 | Phase 0 (代码 review 通过，待真机 manual gates + commit hash) |

---

## 七、🔮 未来战役预留（量级驱动，尚未开战）

> **与第一节 🔴 `pending` 的区别**：本节条目**不是当前攻击点**，而是根据量级分析提前锁定的、未来条件触发时才启动的战役占位。不计入 55 条攻击面总数。每条必须带：**触发条件 + 预计量级收益 + 升级路径指向**。

### 🔮 fix-gps-position-denoise · GPS 位置噪声抑制

- **触发条件**：真机精度战役启动，或 ±2ms 级真机契约需求提出
- **量级依据**：±1-3m 定位误差在 50 m/s 速度下 → 真机 ±20-60ms 圈时抖动，**40ms 高频设备主矛盾**
- **预计收益**：真机 ±20-60ms → ±5-10ms
- **升级手段候选**：Kalman 滤波 / 位置平滑 / 出入线段几何抗抖动
- **依赖**：无前置战役；是 `fix-laptime-low-freq-device-support` 的前置必要条件
- **留档依据**：`2026-04-24-opsx-fix-lap-timing-closure-and-precision-contract-spec-tasks-review.md` 附录 A/C

---

### 🔮 fix-laptime-skip-frame-precision · 未同步帧 skip 场景精度治理

- **触发条件**：真机回归发现 skip 场景圈时漂移
- **量级依据**：Δt 从 40ms 拉到 200-400ms 时，匀速插值偏差 2-8ms（超出 ±5ms 合成契约但未超数量级）
- **预计收益**：真机偶发 2-8ms → < 1ms（拒收 + qualityFlag 标记）
- **升级手段候选**：`Δt > 阈值`时拒收该次过线事件 / 标记 `qualityFlag = LowSamplingRatePrecision`
- **依赖**：无前置；`fix-laptime-low-freq-device-support` 的阶段 4 与本战役同理可共用阈值逻辑

---

### 🔮 fix-laptime-low-freq-device-support · 1Hz 弱定位设备支持

- **触发条件**：设备矩阵扩展到手机内置 GPS / 入门级外设 / 摩托车载设备（1Hz 及以下采样率）
- **量级依据**：1Hz 下匀速 vs 匀加速偏差 **50-200ms**（Δt² 主导）+ 弦长/弧长偏差秒级，**质变**为主矛盾
- **预计收益**：1Hz 下 50-200ms 插值偏差 → < 10ms
- **升级路径四阶段**（见 change `fix-lap-timing-closure-and-precision-contract` proposal 决策 5）：
  1. 匀加速时间插值（利用 GPS Doppler 速度）
  2. 弦长/弧长区分（圆弧近似）
  3. 基于朝向的二阶几何（Bezier/圆弧拟合）
  4. 超阈值 Δt 拒收（与 `fix-laptime-skip-frame-precision` 共享）
- **依赖**：**前置必须** `fix-gps-position-denoise`（否则 GPS 噪声仍主导，升级无物理意义）
- **留档依据**：`2026-04-24-opsx-fix-lap-timing-closure-and-precision-contract-spec-tasks-review.md` § P2-5 + 附录 B/D

---

**维护说明**：

- 评审方新增攻击点：在"第一节 🔴 `pending`"追加；更新"附录 编号总览"。
- 实施方认领：把条目整体移入"第二节 🟡 `in_progress`"；在状态行追加变更记录。
- 实施方提交核销：把条目整体移入"第三节 🟢 `pending_review`"；附 commit hash。
- 评审方核销成功：把条目整体移入"第五节 ✅ 已核销存档"（可浓缩为一句话 + commit + 战役）；更新附录状态。
- 评审方核销失败：把条目移入"第四节 ❌ `rejected`"；在状态行写驳回理由；实施方重新认领时再移回第二节。
- 未来战役预留条目：在"第七节 🔮"追加，每条**必须**带"触发条件 + 预计量级收益 + 升级路径指向 + 留档依据（review 文档路径）"；触发后起 proposal，条目自然转为第一节 🔴 新攻击点（原第七节条目可注释为"已启动，迁出到 change XXX"）。
