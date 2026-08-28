## ADDED Requirements

### Requirement: PERFORMANCE_TEST 采样时间字段时钟域 hygiene 与 anchor 同源

系统 SHALL 保证 PERFORMANCE_TEST binary telemetry 文件中所有时间字段属于同一时钟域（接收侧真壁钟，即 `System.currentTimeMillis()`），且 sample 的 `ts_delta_ms` anchor SHALL 严格等于 `header.startTs`：

- `header.startTs`：PERFORMANCE_TEST session 开始时刻的接收侧真壁钟（由 `TelemetryRepository.startSession(PERFORMANCE_TEST)` 内部 `System.currentTimeMillis()` 生成）
- `header.endTs`：session 结束时刻的接收侧真壁钟
- `TelemetrySessionEntity.startTs / endTs`：与 `header.startTs / endTs` 同源（同一次 `currentTimeMillis()` 调用结果）
- `sample.ts_delta_ms`：PERFORMANCE_TEST 写入入口（`startTest` preTrigger buffer 回填段 + `processFilteredData` Running 持续写）样本写入瞬间的 `System.currentTimeMillis()` 与 `header.startTs` 之差，**不是与 GPS 协议时间或任何其他 anchor 取的差**

**禁止用法**（任何写入 PERFORMANCE_TEST binary 的入口）：

```
tsDeltaMs = filteredData.timestamp - anchorTs    // 协议时间 - 协议时间，与 header.startTs 跨时钟域，禁止
tsDeltaMs = frame.timestamp - anchorTs           // 同上，禁止
tsDeltaMs = System.currentTimeMillis() - activeTestStartTs  // activeTestStartTs 是协议时间，与 header.startTs 跨时钟域，禁止
```

**正确用法**：

```
val sessionStartTs = telemetryRepository.activeSessionStartTs   // == header.startTs（A round 已暴露）
val tsDeltaMs = System.currentTimeMillis() - sessionStartTs
```

**preTrigger buffer 回填语义边界**：方案 A 接受"preTrigger buffer 回填的 N 帧 absoluteTs 集中在 startTest 内部循环耗时窗口（~毫秒级），不分布在 buffer 实际采集时长（典型 1-2 秒）"。该限制是 spec 接受语义，不视为违约。下游消费方若需要 sample 间精确时间分布，应升级到方案 B（`PreTriggerFrame` 加 `receivedAtWallClockMs` 字段）独立立项。

**错误分支降级语义**：当 `activeSessionStartTs` 为 null（startSession 后 invariant 破坏分支）时，写入入口 SHALL 通过 `FileLogger.e` 记录警告并跳过 telemetry 写入，**MUST NOT** 阻塞 PERFORMANCE_TEST 主流程（`processFilteredData` Running 分支末尾的 `state.session.template.shouldEnd / finishTest` 触发照常执行；`startTest` preTrigger 回填段位于函数末尾，循环跳过后只剩 `FileLogger.d` 调试日志，不阻塞 `_testState = Running` 已设置 + 后续 GPS 帧驱动状态机进入 Running 分支）。**注**：FileLogger API 仅有 `d / v / e` 三档（无 `w`），invariant 破坏走 `e` 级与 A round line 843 同 pattern——理由：error 级合理但不致命，必须配合 skip telemetry + 主流程继续。

#### Scenario: PERFORMANCE_TEST round trip 在 anchor 同源下窗口过滤命中

- **WHEN** 调用 `repository.startSession(PERFORMANCE_TEST)` 后立即拉取 `repository.activeSessionStartTs = T1`，连续以 `tsDeltaMs = System.currentTimeMillis() - T1` 写入 N 帧 sample（间隔 ~40ms 模拟 25Hz），调 `repository.endSession()` 关闭文件
- **THEN** 调用 `readPerformanceSamples(filePath)` 返回 N 帧样本，所有样本 `absoluteTs = header.startTs + tsDeltaMs` 落在 `[entity.startTs, entity.endTs + tolerance]` 范围内（tolerance ≤ 100ms 容许 CI 抖动）；调用 `readLapSamples(filePath, lapStartTs = T1, lapEndTs = T1 + (N-1) × 40 + tolerance)` 同样返回 N 帧样本

#### Scenario: anchor 错位会被 round trip 测试捕获（writer 直接构造，无需 mock System）

- **WHEN** 直接用 `BinaryTelemetryWriter.open(path, type = PERFORMANCE_TEST, startTs = 10000)` 构造文件（模拟 `header.startTs = 10000` = T1），但故意写入"anchor 错位"的 sample：每帧 `tsDeltaMs = (i × 40) + 5000`（额外 +5000 模拟跨时钟域偏移），写入 100 帧后 close
- **THEN** 调用 `readLapSamples(filePath, lapStartTs = 10000, lapEndTs = 10000 + 100 × 40)` 返回 0 帧（所有 absoluteTs = 10000 + tsDeltaMs ≥ 15000 落在窗口外被剔除）；测试 assert `samples.size == 0`
- **反例语义**：本 case B 直接构造的文件等效于"如果生产代码 anchor 仍跨时钟域偏移 5000ms"——case A 路径（happy 写入 + 全帧落窗口）assert `samples.size == N` 在 anchor 错位时会从 N 跌到 0，bug 被捕获。case B 锁死的是 reader 行为：跨窗 absoluteTs 必须被剔除（不是 reader 错把窗外样本算进来）

#### Scenario: preTrigger buffer 回填帧 absoluteTs 集中在 startTest 内部窗口（spec 接受语义）

- **WHEN** 模拟 startTest 流程：构造 N=25 帧的 lockedPreTriggerBuffer（模拟 1 秒采集），调 `startSession(PERFORMANCE_TEST)` 拉取 sessionStartTs，然后在 `for (frame in buffer)` 循环内每帧 `tsDeltaMs = System.currentTimeMillis() - sessionStartTs` 写入，调 `endSession()` 关闭文件
- **THEN** 读取所有 25 帧样本的 absoluteTs，**断言**：
  1. 所有 absoluteTs ∈ `[entity.startTs, entity.startTs + 100ms]`（startTest 内部循环耗时上界，~毫秒级集中）
  2. **不要求** absoluteTs 散布在 buffer 实际采集时长（1 秒）—— 该限制是 spec 接受语义，不构成违约

#### Scenario: processFilteredData 持续写时 absoluteTs 分布真实

- **WHEN** 模拟 PERFORMANCE_TEST Running 期间持续接收 GPS 数据：每 ~40ms（25Hz）调一次 `processFilteredData`，bridge 内部用 `tsDeltaMs = System.currentTimeMillis() - sessionStartTs` 写入 sample；连续写入 100 帧后 endSession
- **THEN** 读取所有 100 帧样本的 absoluteTs：
  1. 相邻样本 absoluteTs 间隔 ≈ 40ms（容差 ±10ms 容许调度抖动）
  2. 全部样本落在 `[entity.startTs, entity.endTs]` 范围内
  3. 与 preTrigger 回填段不同，本场景 absoluteTs 真实分布在采集时长上（~4 秒）

#### Scenario: 时钟域单源 grep 自检（PERFORMANCE_TEST 写入路径）

- **WHEN** 在 `feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt` 内 grep 以下两类禁止模式：
  - `filteredData\.timestamp\s*-\s*(anchorTs|activeTestStartTs)` （processFilteredData 的旧 bug 模式）
  - `frame\.timestamp\s*-\s*anchorTs` （startTest preTrigger 回填的旧 bug 模式）
- **THEN** 上述两类 grep 命中数均为 0（baseline 修复前应各命中 1 次，修复后归零——非 trivially pass 的有效 grep gate）
- **AND** 在同一文件内 grep 正向 pattern `System\.currentTimeMillis\(\)\s*-\s*sessionStartTs` 应在 PERFORMANCE_TEST 写入入口（startTest preTrigger 回填段 + processFilteredData TestState.Running 分支）至少 2 处命中（baseline 修复前应 0 处，修复后应 ≥ 2 处——形态约束有保护价值）

#### Scenario: 跨文件 grep gate 防止 simulator 端漏改

- **WHEN** 在 `feature/test/src/main/java`（解析 root：用 `locateTestSessionViewModelFile().parentFile` 上溯到 `feature/test/src/main/java/com/blazepush/feature/test/`，再向上到 `feature/test/src/main/java`）全子树 grep `tsDeltaMs\s*=\s*[a-zA-Z_]+\.timestamp\s*-`，**MUST 排除**：(a) 路径含 `/src/test/` 的文件 (b) 路径含 `/.worktrees/` 的文件
- **THEN** 满足两条：
  1. 命中数 == 0（无任何文件用 `XXX.timestamp - YYY` 形态计算 PERFORMANCE_TEST 或 LAP_SESSION 的 tsDeltaMs；命中 ≥ 1 表示存在未修复的写入入口，grep gate 视为 fail）
  2. 扫到的 .kt 文件总数 ≥ 30（防扫错路径假性绿——若 helper 路径解析错跳到空目录会导致命中数 0 但扫到 0 文件，对应 v3 高频盲点 #8）

#### Scenario: anchor 缺失时降级而非阻塞（源码 grep gate 验证 fallback 形态对齐）

- **背景**：`processFilteredData` 是 private suspend fun（不可直接测试）+ `activeSessionStartTs` 是 final var private set（mockito-core 无法 mock final property）+ FileLogger 是 object 单例（无 spy 入口）——传统 mock-based 单测路径不可行（v3 高频盲点 #13 dead spec 风险）。本 scenario 改为源码 grep gate 验证 fallback 形态对齐（与 A round case H 同 pattern）
- **WHEN** 在 `feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt` 内 grep 以下三类必须存在的形态：
  - 形态 A（processFilteredData TestState.Running 分支）：连续命中 `val sessionStartTs = telemetryRepository.activeSessionStartTs` + 紧随 `if (sessionStartTs != null)` + 嵌套 `writeSample` + `} else {` + `FileLogger.e(TAG, "processFilteredData: missing activeSessionStartTs...")` + **else block 之后**仍命中 `if (state.session.template.shouldEnd(filteredData.raw))`（防 bare return 早返回 regression）
  - 形态 B（startTest preTrigger 回填）：`val sessionStartTs = telemetryRepository.activeSessionStartTs` + 紧随 `if (sessionStartTs != null)` + 嵌套 `for (frame in lockedPreTriggerBuffer)` + `writeSample` + `} else {` + `FileLogger.e(TAG, "startTest preTrigger backfill: missing activeSessionStartTs...")`
  - 形态 C（本 round scope 内的 FileLogger.e 调用次数）：grep `FileLogger\.e\([^)]*(processFilteredData|startTest preTrigger backfill)[^)]*missing activeSessionStartTs` 在该文件内命中数 == 2（不算入 A round 已加的 `bridgeGpsToLapTiming` 那处 line 845；通过函数前缀 anchor 严格收紧到本 round scope）
- **THEN** 形态 A + B + C 均存在；任一形态缺失（如有人后续 refactor 删了 else 分支或换成 `?: return`）grep gate 视为 fail
- **理由**：架构层 fallback 形态约束比 mock 黑盒断言更稳定——单测断言"writeSample 未被调"需要 spy/mock 机制不可用；grep 断言"源码 has fallback shape"在 IDE/CI 都能跑，且任何 refactor 误删 else 分支会被捕获

#### Scenario: activeTestStartTs 字段语义未被本修复破坏

- **WHEN** 调 `startTest(...)` 后查询 `viewModel.activeTestStartTs`
- **THEN** 该字段值仍为 `lockedPreTriggerBuffer.firstOrNull()?.timestamp ?: filteredData.timestamp`（协议时间），与本修复前语义一致；下游 0-100 计时显示等派生不受本修复影响
