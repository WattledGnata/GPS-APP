# 实施任务（依赖顺序）

工作按 4 个 Requirement 组织，严格执行顺序：

1. **R1 A19 白名单**、**R2 A21 入口 ts 守卫**、**R3 A21 filter 严格语义** 均改同一文件
   `LapTimingEngine.kt` 的 `processSample` / `handleStartFinishCrossing`，合并成**一组** engine 改动
2. **R4 A38 + A34** 改 `TestSessionViewModel.bridgeGpsToLapTiming`，独立于 engine
3. 两组改动无顺序依赖，但建议 R1~R3 先闭环（engine 契约自洽），R4 再跟（bridge 层依赖 engine 契约）

合流门槛集中在第 5 节。

---

## 1. R1 A19：engine 入口 `LapSessionStatus` 白名单守卫

- [ ] 1.1 **代码改动**：`feature/test/src/main/java/com/blazepush/feature/test/usecase/LapTimingEngine.kt` 的 `processSample` 方法第一行（当前 `val updatedSamples = session.samples + currentSample` 之前）插入：
    ```kotlin
    // A19 入口守卫：白名单语义，只放行 Ready / Recording
    // 未来新增 LapSessionStatus 枚举值默认被拦，除非显式决定接受
    if (session.status !in setOf(LapSessionStatus.Ready, LapSessionStatus.Recording)) {
        return session
    }
    ```
- [ ] 1.2 **import 补齐**：文件头 `import com.blazepush.feature.test.model.LapSessionStatus`（已存在则跳过）。
- [ ] 1.3 **新增测试** `LapTimingEngineTest.processSample_onFinishedSession_returnsUnchanged`：
    - 构造 `session = LapSession(..., status = Finished, samples = [...100 帧...])`
    - 调用 `engine.processSample(session, track, prevSample, currentSample)`
    - 断言 `返回.status == Finished`、`返回.samples.size == 100`（currentSample 未追加）
    - 断言 `返回.completedLaps / activeLap / crossingEvents / startedAtMillis` 全字段不变
- [ ] 1.4 **新增测试** `LapTimingEngineTest.processSample_onCancelledSession_returnsUnchanged`：对称覆盖 Cancelled。
- [ ] 1.5 **新增测试** `LapTimingEngineTest.processSample_onIdleSession_returnsUnchanged`：对称覆盖 Idle。
- [ ] 1.6 **新增测试** `LapTimingEngineTest.processSample_onReadySession_acceptsSampleAndStartsLap`：
    - 构造 `session = LapSession(..., status = Ready, activeLap = null, samples = emptyList())`
    - 构造 `(previousSample, currentSample)` 在 `track.startFinishGate` 上构成 accepted 的过线
    - 断言 `返回.status == Recording`、`返回.activeLap.lapIndex == 1`、`返回.samples.size == 1`
- [ ] 1.7 **新增测试** `LapTimingEngineTest.processSample_onRecordingSession_acceptsSampleAndAdvances`：
    - 构造 Recording + activeLap(lapIndex=1) + 有效 sector 过线
    - 断言 `返回.samples.size` 增长、`crossingEvents / activeLap.sectorEntries` 正常推进

## 2. R2 A21：engine 入口 ts 单调守卫

- [ ] 2.1 **代码改动**：`LapTimingEngine.processSample` 在 R1 A19 守卫之后、`val updatedSamples = session.samples + currentSample` 之前插入：
    ```kotlin
    // A21 深度防御：bridge 层若被绕过或重构，engine 兜底拦 ts 回跳
    // 对比基准用 previousSample（方法参数永远非空），与 A38 语义对称
    if (currentSample.timestampMillis < previousSample.timestampMillis) {
        FileLogger.d(TAG, "processSample: ts regression, drop prevTs=${previousSample.timestampMillis} curTs=${currentSample.timestampMillis}")
        return session
    }
    ```
- [ ] 2.2 **新增测试** `LapTimingEngineTest.processSample_timestampRegressionSample_returnsUnchanged`：
    - 构造 `previousSample.timestampMillis = 500L`，`currentSample.timestampMillis = 400L`
    - `session.status = Recording`（通过 A19 白名单）
    - 断言 `返回.samples.size` 不增长、`返回.crossingEvents.size` 不增长
- [ ] 2.3 **新增测试** `LapTimingEngineTest.processSample_firstSampleOnEmptySession_noRegressionCheckApplies`：
    - 构造 `session.samples = emptyList()`、`session.status = Ready`
    - `previousSample.timestampMillis = 100L`、`currentSample.timestampMillis = 140L`
    - 断言守卫不误拦（`140 < 100` 为 false），`返回.samples.size == 1`

## 3. R3 A21：`crossingEvents` 闭圈裁剪改 `filter` 严格语义

- [ ] 3.1 **代码改动**：`LapTimingEngine.handleStartFinishCrossing` 第 136 行将
    ```kotlin
    crossingEvents = updatedEvents.dropWhile { it.timestampMillis < activeLap.startedAtMillis }
    ```
    改为
    ```kotlin
    crossingEvents = updatedEvents.filter { it.timestampMillis >= activeLap.startedAtMillis }
    ```
- [ ] 3.2 **新增测试** `LapTimingEngineTest.handleStartFinishCrossing_monotonicCrossingEvents_filterRetainsAllAboveStartedAt`（对应 R3 Scenario 1 正向语义）：
    - 构造 session.crossingEvents 单调序列 `[ts=100, ts=200, ts=300, ts=400]` + `activeLap.startedAtMillis = 200L`
    - 触发闭圈（第二次起终点过线）
    - 断言 `LapRecord.crossingEvents.map { it.timestampMillis } == [200L, 300L, 400L]`（边界 ts=200 保留，锁定 `>=` 语义非 `>`）
- [ ] 3.3 **新增测试** `LapTimingEngineTest.handleStartFinishCrossing_outOfOrderHistoricalEventHardDistinguishesFilterVsDropWhile`（对应 R3 Scenario 2 硬区分 v1/v2）：
    - 构造 session.crossingEvents 非单调序列 `[ts=100, ts=250, ts=150, ts=400]` + `activeLap.startedAtMillis = 200L`
    - 触发闭圈
    - 断言 `LapRecord.crossingEvents.map { it.timestampMillis } == [250L, 400L]`（ts=150 被 filter 拒收）
    - **硬区分对照**：注释说明 v1 `dropWhile` 语义下会输出 `[250L, 150L, 400L]`（ts=150 漏拦），v2 `filter` 输出 `[250L, 400L]`，断言能硬区分
- [ ] 3.4 **新增测试** `LapTimingEngineTest.handleStartFinishCrossing_monotonicSequence_filterOutputEqualsDropWhileOutput`（对应 R3 Scenario 3 防退化回归保护）：
    - 构造 session.crossingEvents 单调序列 `[ts=100, ts=200, ts=300, ts=400]` + `activeLap.startedAtMillis = 150L`
    - 触发闭圈
    - 断言 `LapRecord.crossingEvents.map { it.timestampMillis } == [200L, 300L, 400L]`（filter 输出）
    - **防退化断言**：同一输入在测试内用局部变量算一次 `updatedEvents.dropWhile { it.timestampMillis < 150L }.map { it.timestampMillis }` 作对比
    - 断言 filter 输出与 dropWhile 输出逐元素相等 —— 防御"未来把 `>=` 误写为 `>`"或"误把 filter 退回 dropWhile"导致单调场景偏离契约
- [ ] 3.5 **回归保护**：跑现有 `LapTimingEngineTest` 全部用例 + `EndToEndLapTimingContractTest` 6 条契约，确认正常路径零行为回归（与 3.2/3.3/3.4 独立断言互补，非替代）。

## 4. R4 A38 + A34：`bridgeGpsToLapTiming` 三段式 + 顺手清理死码

- [ ] 4.1 **代码改动**：`feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt` 的 `bridgeGpsToLapTiming` 方法（当前第 340-344 行，从 `lastLapGpsSample = currentSample` 开始到 `engine.processSample` 调用之间）重构为三段式：
    ```kotlin
    BEFORE (当前):
    lastLapGpsSample = currentSample                          // (A) 无条件先赋值
    if (previousSample == null || currentSample.timestampMillis <= 0L) {
        _lapSession.value = currentSession                    // (C) 死码 = A34
        return
    }
    val updatedSession = lapTimingEngine.processSample(...)
    ...

    AFTER (三段式):
    // 段 1 — 首样本分支：early return 但仍赋 lastLapGpsSample
    //        顺手清理 A34：删除 _lapSession.value = currentSession 死码
    if (previousSample == null || currentSample.timestampMillis <= 0L) {
        lastLapGpsSample = currentSample
        return
    }

    // 段 2 — A38 ts 单调守卫：回跳帧整帧丢弃 + 不更新 lastLapGpsSample
    if (currentSample.timestampMillis < previousSample.timestampMillis) {
        FileLogger.d(TAG, "bridgeGpsToLapTiming: ts regression, drop sample prevTs=${previousSample.timestampMillis} curTs=${currentSample.timestampMillis}")
        return
    }

    // 段 3 — 正常推进：赋 lastLapGpsSample + 喂 engine
    lastLapGpsSample = currentSample
    val updatedSession = lapTimingEngine.processSample(...)
    ...
    ```
- [ ] 4.2 **新增测试** `TestSessionViewModelTrackLapTest.bridgeGpsToLapTiming_firstSample_updatesLastLapGpsSampleForNextFrame`：
    - 预设 `_lapSession.value.status = Ready`，调用 `updateFilteredData` / 等效途径模拟喂入首帧 `gpsData(ts=100, isTimeSynced=true)`
    - 断言 `lastLapGpsSample` 反映为首帧（ts=100）—— 通过"下一帧能起圈"间接验证
    - 再喂入下一帧 `gpsData(ts=140, isTimeSynced=true)` 与首帧构成起终点过线
    - 断言 `_lapSession.value.status == Recording`、`activeLap.lapIndex == 1`
- [ ] 4.3 **新增测试** `TestSessionViewModelTrackLapTest.bridgeGpsToLapTiming_dropsSamplesWithRegressingTimestamp`：
    - 预设 `lastLapGpsSample` 为 ts=500 的帧，session.status=Recording
    - 喂入一帧 `gpsData(ts=400, isTimeSynced=true)`（ts 回跳）
    - 断言：`_lapSession.value.samples.size` 不增长；`lastLapGpsSample` 仍是 ts=500（未被覆盖）
- [ ] 4.4 **新增测试** `TestSessionViewModelTrackLapTest.bridgeGpsToLapTiming_afterRegressionDropped_nextForwardSampleIsProcessedAgainstPreviousFrame`：
    - 延续 4.3 状态：回跳帧已被丢弃，`lastLapGpsSample` 仍是 ts=500
    - 喂入下一帧 `gpsData(ts=520, isTimeSynced=true)`（ts 前进）
    - 断言 `engine.processSample` 被以 `previousSample.ts=500` 为基准调用（间接验证：如果它以 ts=400 为基准，session 推进语义会错位）
    - 回跳帧永不出现在 engine 调用链路
- [ ] 4.5 **回归保护**：跑 `TestSessionViewModelTrackLapTest` 全部用例（含战役 A 的未同步帧守卫 `processFilteredData_runningPhase_ignoresUnsyncedFrames` 等）+ `EndToEndLapTimingContractTest` 6 条契约，确认正常路径零行为回归。

## 5. Spec 校验 + 合流门槛

全部打钩后才能走 `/opsx:archive` / 归档 & 存档流程。

- [ ] 5.1 `$(npm config get prefix)/bin/openspec-chinese validate fix-lap-timing-engine-entry-hardening --strict` 通过。
- [ ] 5.2 `./gradlew :feature:test:testDebugUnitTest --tests "*LapTimingEngineTest*"` 全绿（覆盖 R1 五条 + R2 两条 + R3 三条 = 10 条新用例 + 现有用例）。
- [ ] 5.3 `./gradlew :feature:test:testDebugUnitTest --tests "*TestSessionViewModelTrackLapTest*"` 全绿（覆盖 R4 三条新用例 + 现有用例，含战役 A 未同步帧守卫）。
- [ ] 5.4 `./gradlew :feature:test:testDebugUnitTest --tests "*EndToEndLapTimingContractTest*"` 全绿（战役 A 契约不被破坏）。
- [ ] 5.5 `./gradlew :core:domain:test :core:bluetooth:testDebugUnitTest` 全绿（确认 filter / parser 零下游回归）。
- [ ] 5.6 更新 `docs/superpowers/reviews/attack-backlog.md`：A19 / A21 / A34 / A38 四条状态迁到 🟢 `pending_review`，附 commit hash；A33 保持 🔴（Non-goals 已声明）。

## 6. Commit 策略

按"每独立功能点一 commit"拆分：

1. **commit 1**：engine 入口守卫（R1 + R2 + R3）—— 同一文件 `LapTimingEngine.kt`，改 `processSample` + `handleStartFinishCrossing`，测试新增 8 条。
   - 建议消息：`fix(laptiming): 战役 C engine 入口夯实（A19/A21）白名单 + ts 单调 + filter 严格语义`
2. **commit 2**：bridge 三段式 + A34 顺手清理（R4）—— 改 `TestSessionViewModel.bridgeGpsToLapTiming`，测试新增 3 条。
   - 建议消息：`fix(laptiming): 战役 C bridge 入口夯实（A38/A34）三段式 ts 单调守卫 + 首样本死码清理`

评审方核销后再做 backlog 迁档 commit（不在本 change 代码 commit 内，保持 review artifact 独立）。
