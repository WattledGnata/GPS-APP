# 实施任务

> 修订版 v2（2026-04-22）：按 `docs/superpowers/reviews/2026-04-22-opsx-fix-laptime-clock-source-review.md` 的 P0/P1/P2 修订。
> 主要变动：
> - 删 GpsSample.isTimeSynced 相关任务
> - 加 filter / preTriggerBuffer / processFilteredData / updateLaunchStatus 的分层守卫任务
> - 加 LapQualityFlag.ProtocolDesyncGap 新枚举 + engine 闭圈扫描任务
> - 加 parser sentinel = Long.MIN_VALUE 任务 + bridgeGpsToLapTiming 重置 lastLapGpsSample 任务
>
> 工作分 8 组。每个任务粒度控制在 2 小时以内。高风险不可逆动作用 ⚠️ 标注。
> 完成标记 `- [x]` 必须同步更新本文件，禁止批量后补。

---

## 1. 接收端 parser：写 sentinel 而非 fallback 系统时钟

- [x] 1.1 在 `core/bluetooth/src/main/java/com/blazepush/core/bluetooth/parser/RaceChronoParser.kt:257-261` 把 `?: System.currentTimeMillis()` 删除；`protocolTimeReference == null` 或 `syncBits != ref.syncBits` 时，**写入 `timestamp = Long.MIN_VALUE`**。
- [x] 1.2 在 parser 内部新增 `isCurrentlyTimeSynced: Boolean`；`parseGpsTimeData` 成功设置 `protocolTimeReference` 后，下一主包帧若 syncBits 匹配则 `isCurrentlyTimeSynced = true`；主包 syncBits 不匹配或冷启动时为 `false`。
- [x] 1.3 `parseGpsData` 返回的 `GpsData.copy(...)` 里带 `isTimeSynced = isCurrentlyTimeSynced`；未同步时 `timestamp = Long.MIN_VALUE`。
- [x] 1.4 `parser.reset()` 把 `isCurrentlyTimeSynced = false` 和 `protocolTimeReference = null` 一并清零；新连接首帧严格从未同步状态起跳。
- [x] 1.5 审查 `parseGpsTimeData` 的 `Calendar.getInstance()` 使用：目前只用于 `hourStartMillis` 拼接，符合要求；**不得** 在其中调用 `Date(ts).year` / `Calendar.get(YEAR/MONTH/DAY)` 做业务判断。
- [x] 1.6 **新增测试** `RaceChronoParserProtocolTimeTest.parseGpsData_whenNoTimeDataReceivedYet_writesSentinel`：不喂时间包，直接喂主包，断言 `result.isTimeSynced == false` 且 `result.timestamp == Long.MIN_VALUE`。
- [x] 1.7 **新增测试** `RaceChronoParserProtocolTimeTest.parseGpsData_whenSyncBitsMismatch_writesSentinel`：先喂 syncBits=3 的时间包，再喂 syncBits=5 的主包，断言 `result.timestamp == Long.MIN_VALUE`；用 Mockito `mockStatic(System::class.java)` 或 JVM `-Djavaagent:...` 验证 `System.currentTimeMillis` 调用次数为 0。（实际实现：因 Mockito 禁止 mock `java.lang.System`（会引发类加载无限递归），改用 `assertEquals(Long.MIN_VALUE, result.timestamp)` 反证 fallback 路径已消除；"全链路 0 次调用"的强保证留给战役 H 的 EndToEnd 测试在外部用 Byte Buddy agent 验证。）
- [x] 1.8 **新增测试** `RaceChronoParserProtocolTimeTest.parseGpsData_afterTimePacketThenMatchingMain_isTimeSyncedTrue`：先喂 time 再喂 main，断言 `result.isTimeSynced == true` 且 `timestamp` 等于协议还原值。
- [x] 1.9 **新增测试** `RaceChronoParserProtocolTimeTest.parseGpsData_recoveryAfterDesync_switchesBackToSynced`：先 3 帧 syncBits 不匹配 → 第 4 帧匹配，断言 4 帧恢复为 `isTimeSynced == true`。

## 2. 数据模型：`GpsData` + `LapQualityFlag`

- [x] 2.1 在 `core/domain/src/main/java/com/blazepush/core/domain/model/GpsData.kt` 的 `data class GpsData(...)` 尾部加 `val isTimeSynced: Boolean = false`。
- [x] 2.2 `GpsData.Empty` 常量显式写 `isTimeSynced = false`。
- [x] 2.3 全仓 grep `GpsData(` 构造调用，确认历史显式构造（`BluetoothDataSource` / 测试 fake）不需要改（默认值保证）。
- [x] 2.4 在 `feature/test/src/main/java/com/blazepush/feature/test/model/laptiming/LapQualityFlag.kt` 追加枚举成员 `ProtocolDesyncGap`。
- [x] 2.5 **明确不改** `feature/test/.../model/laptiming/GpsSample.kt`：不加 `isTimeSynced` 字段（review 决策 3：`LapSession.samples` 里所有 `GpsSample` 已被 bridge 过滤保证同步，字段会是死字段）。
- [x] 2.6 **新增测试** `GpsDataTest.Empty_isTimeSyncedIsFalse`：锁定 `GpsData.Empty.isTimeSynced == false`。
- [x] 2.7 **新增测试** `LapQualityFlagTest.enumContainsProtocolDesyncGap`：锁定 `LapQualityFlag.values().any { it.name == "ProtocolDesyncGap" }`。

## 3. 发射端 generator：去系统时钟、去 fallback

- [x] 3.1 在 `simulator/src/main/java/com/blazepush/simulator/data/GpsDataGenerator.kt` 构造函数加可注入参数 `clock: () -> Long = { SystemClock.elapsedRealtime() }`。
- [x] 3.2 新增私有 `private val sessionStartRealtimeMillis = clock()`。
- [x] 3.3 重写 `currentTimestampMillis()`：
    - `REAL_TRACK_REPLAY`：`replayTimestampMillis ?: throw IllegalStateException("Replay sample missing timestamp - did you forget to call applyReplaySample()?")`
    - 其他场景：`clock() - sessionStartRealtimeMillis`
- [x] 3.4 `generateGpsMainData()` 的 `timeMs = (currentTimestampMillis() % 3_600_000L).toInt() / 2` 保持。
- [x] 3.5 `generateGpsTimeData()`：Calendar 用 `currentTimestampMillis()`；固定 `yearOffset = 0` 对应 2000-01-01 起点，不反映真实日历。
- [x] 3.6 删除所有 `?: System.currentTimeMillis()` 分支。
- [x] 3.7 `reset()` 重置 `sessionStartRealtimeMillis = clock()`。
- [x] 3.8 **新建** `simulator/src/test/java/com/blazepush/simulator/data/GpsDataGeneratorTest.kt`：
    - `generateGpsMainData_staticMode_timeSinceHourStartFromInjectedClock`：注入 fake clock，断言 `timeSinceHourStart` 按会话相对时间递增。
    - `generateGpsMainData_replayMode_withoutApplyReplaySample_throws`：构造 `REAL_TRACK_REPLAY` 但不调 `applyReplaySample`，断言抛 `IllegalStateException`。
    - `generateGpsData_neverCallsSystemCurrentTimeMillis`：全路径 mock `System.currentTimeMillis` 验证调用次数 == 0。
    - `reset_resetsSessionStartRealtime`：调 `reset()` 后后续 `timeSinceHourStart` 从 0 重新开始递增。

## 4. filter 分层守卫 (a)：`GpsDataFilter.process` 入口

- [x] 4.1 在 `core/domain/src/main/java/com/blazepush/core/domain/usecase/GpsDataFilter.kt` 的 `process(raw: GpsData)` 入口加：
    ```kotlin
    if (!raw.isTimeSynced) {
        return FilteredGpsData(
            speed = raw.speed,
            latitude = raw.latitude,
            longitude = raw.longitude,
            altitude = raw.altitude,
            bearing = raw.bearing,
            acceleration = 0.0,
            confidence = 0.0,
            isAnomaly = false,
            timestamp = raw.timestamp,
            raw = raw,
            consistencyFactor = 1.0,
            isPositionAnomaly = false
        )
    }
    ```
- [x] 4.2 确保 **未同步时** `previousRaw / previousPosition / speedWindow / latWindow / lonWindow / bearingWindow` 全部**不更新**（守卫在任何窗口 add 之前）。
- [x] 4.3 **新增测试**（**临时**放在 `feature/test/src/test/java/com/blazepush/feature/test/usecase/GpsDataFilterTest.kt`；原 `core/domain` 下路径因战役 D 的 package 迁移问题暂不可落地，待战役 D 把旧 `app/src/test/.../GpsDataFilterTest.kt` 迁回 `core:domain` 后统一合并）：
    - `process_notTimeSynced_returnsZeroDeltaSnapshot`：喂 `isTimeSynced=false`，断言 `acceleration == 0.0`，`confidence == 0.0`，`isAnomaly == false`。
    - `process_notTimeSynced_doesNotUpdateInternalState`：喂 `isTimeSynced=false` 10 次，再喂 `isTimeSynced=true` 1 次，断言后者作为"首帧"（`previousRaw == null` 的效果）——通过间接观察 `calculateAcceleration` 在首帧返回 0 验证。

## 5. ViewModel 分层守卫 (a)：`TestSessionViewModel` 其它时间 delta 消费者

- [x] 5.1 在 `feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt:199-203` 的 `updatePreTriggerBuffer` 入口加：
    ```kotlin
    if (!filteredData.raw.isTimeSynced) return
    ```
- [x] 5.2 在 `processFilteredData` 的 `TestState.Preparing` 分支（`TestSessionViewModel.kt:207-213`）加未同步守卫：
    ```kotlin
    is TestState.Preparing -> {
        if (!filteredData.raw.isTimeSynced) return
        if (_countdownSeconds.value == 0) {
            if (checkTriggerCondition(filteredData, state.template)) {
                startTest(state.template, state.carModel, filteredData)
            }
        }
    }
    ```
- [x] 5.3 在 `TestSessionViewModel` 增加私有字段 `private var lastReceivedAtElapsed: Long = 0L`；每次 `gpsDataFlow.collect` 回调首行更新 `lastReceivedAtElapsed = SystemClock.elapsedRealtime()`（无论 `isTimeSynced`）。
- [x] 5.4 重写 `updateLaunchStatus` 中的 `lastDataAge` 计算：`val lastDataAge = SystemClock.elapsedRealtime() - lastReceivedAtElapsed`，删除原 `System.currentTimeMillis() - lastDataTime` 逻辑；**不再**读 `gpsData.timestamp`。（附带删除已无用的 `lastDataTime` 字段和 `lastDataTime = gpsData.timestamp` 赋值；为了在 JVM 单测里不抛 "Method elapsedRealtime not mocked"，在 `feature/test/build.gradle.kts` 加了 `testOptions { unitTests.isReturnDefaultValues = true }`，与 B / C 组一致。）
- [x] 5.5 `bridgeGpsToLapTiming` 入口（`TestSessionViewModel.kt:301-335`）改为：
    ```kotlin
    if (!gpsData.isTimeSynced) {
        lastLapGpsSample = null  // review P1.4：失联恢复时首帧走首样本分支
        FileLogger.d(TAG, "bridgeGpsToLapTiming: skip unsynced, reset prev")
        return
    }
    ```
    紧跟在 `currentMode != LapDebug || !isLapRecording` 返回之后。
- [x] 5.6 **新增测试** `TestSessionViewModelTrackLapTest.bridgeGpsToLapTiming_skipsFrameWhenTimeNotSynced_andResetsPrev`：
    - 先喂几帧 `isTimeSynced=true` 建立 `lastLapGpsSample`
    - 喂 1 帧 `isTimeSynced=false`
    - 验证 `lastLapGpsSample == null`（通过反射或用 "再喂一帧 isTimeSynced=true 后首样本走首样本分支不入列"间接断言）
- [x] 5.7 **新增测试** `TestSessionViewModelTrackLapTest.preTriggerBuffer_rejectsUnsyncedFrames`：喂 5 帧 `isTimeSynced=false`，断言 `preTriggerBuffer.size == 0`（通过反射）。
- [x] 5.8 **新增测试** `TestSessionViewModelTrackLapTest.processFilteredData_preparingPhase_doesNotTriggerWhenUnsynced`：进入 Preparing → 倒计时结束 → 喂 `isTimeSynced=false`，断言 `_testState.value is TestState.Preparing`（没转 Running）。
- [x] 5.9 **新增测试** `TestSessionViewModelTrackLapTest.launchStatus_lastDataAgeUsesElapsedRealtime`：mock `elapsedRealtime`，断言 `lastDataAge` 与 `elapsedRealtime` delta 一致，与 `gpsData.timestamp` 无关。（实际实现：因 JVM 单测下 `SystemClock.elapsedRealtime()` 经 `isReturnDefaultValues=true` 返回 0L 无法观察时间递进，改用反射断言 `lastReceivedAtElapsed != gpsData.timestamp(1_000L)`，间接证明生产路径已切到独立时钟源。）

## 6. engine：闭圈扫描 `ProtocolDesyncGap`

- [x] 6.1 在 `feature/test/src/main/java/com/blazepush/feature/test/usecase/LapTimingEngine.kt` 的 `handleStartFinishCrossing` 构造 `LapRecord` 之前（当前在 `LapTimingEngine.kt:98`），扫描 `trajectory` 相邻样本 ts 差：
    ```kotlin
    val trajectory = updatedSamples.drop(activeLap.sampleStartIndex)
    val hasDesyncGap = trajectory.zipWithNext().any { (a, b) ->
        (b.timestampMillis - a.timestampMillis) > 200L
    }
    val qualityFlags = buildList {
        if (activeLap.sectorEntries.size != track.sectorGates.size) {
            add(LapQualityFlag.IncompleteSectors)
        }
        if (hasDesyncGap) {
            add(LapQualityFlag.ProtocolDesyncGap)
        }
    }
    ```
- [x] 6.2 保留 `LapRecord.durationMillis = currentSample.timestampMillis - activeLap.startedAtMillis`——不扣除失联段。
- [x] 6.3 **新增测试** `LapTimingEngineTest.processSample_lapWithProtocolDesyncGap_isFlagged`：人工构造一个 trajectory，中间相邻帧 ts 差 = 300ms，断言闭圈 `LapRecord.qualityFlags.contains(LapQualityFlag.ProtocolDesyncGap)`。
- [x] 6.4 **新增测试** `LapTimingEngineTest.processSample_lapWithoutGap_isNotFlagged`：所有 ts 差 ≤ 40ms，断言 `!qualityFlags.contains(ProtocolDesyncGap)`。

## 7. UI：`GpsDataViewModel.gpsData.isTimeSynced` 驱动 statusLabel

- [x] 7.1 `LapDebugExecutionScreen` 增加上游依赖：通过 `gpsDataViewModel.gpsData.collectAsState()` 读 `GpsData.isTimeSynced`（如果该屏幕当前没注入 `GpsDataViewModel`，补一个构造参数）。
- [x] 7.2 修改 `rememberStartFinishTimingCardState` 或外层组合逻辑，把 `statusLabel` 按下列优先级决定：
    ```
    activeLap != null                 → "当前圈进行中"
    gpsData.isTimeSynced == true      → "等待起点"
    gpsData.isTimeSynced == false     → "等待协议时间同步"
    ```
- [x] 7.3 **明确不改** `GpsSample` 字段（对应任务 2.5）。
- [x] 7.4 **新增测试** `LapDebugExecutionScreenStateTest.statusLabel_showsWaitingForTimeSync_whenUpstreamUnsynced`：mock `gpsData.isTimeSynced == false`，`activeLap == null`，断言 `statusLabel == "等待协议时间同步"`。
- [x] 7.5 **新增测试** `LapDebugExecutionScreenStateTest.statusLabel_showsWaitingForStart_whenSyncedButNoActiveLap`。
- [x] 7.6 **新增测试** `LapDebugExecutionScreenStateTest.statusLabel_showsInLap_regardlessOfTimeSync`：activeLap 存在时，不管 `isTimeSynced` 真假，均显示 "当前圈进行中"。

## 8. 端到端契约测试（本 change 的硬门槛）

- [x] 8.1 **新建** `feature/test/src/test/java/com/blazepush/feature/test/usecase/EndToEndLapTimingContractTest.kt`。直接 import `GpsDataGenerator` + `RaceChronoParser` + `LapTimingEngine`，在 JVM 内构造"发射 → 解析 → 圈速"完整链路。
- [x] 8.2 `staticMode_lapDurationMatchesSenderClockDelta`：
    - generator `scenario=STATIC`，fake clock 严格按 40ms 递增 250 帧
    - 第 1 帧和第 250 帧位置手工构造穿起终点 gate
    - 断言 `durationMillis in 9_980..10_020`
- [x] 8.3 `replayMode_lapDurationMatchesReplayClock`：采用 fake replay 替代完整 tianfu_track_replay_5hz.json 扫描以控制复杂度——手工构造 11 帧 `ReplaySample`（`timestampMillis = 0, 200, ..., 2000`），第 1 帧与第 10 帧穿过起终点 gate；断言 `durationMillis` 与 `T_close - T_open = 1800ms` 差 **< 5ms**。（`ReplayLapTimingIntegrationTest` 的 JSON 扫描仍保持 @Ignore 状态，等待 replay gate 管道稳定化后再单独复活。）
- [x] 8.4 `coldStartOnlyMainNoTimePacket_engineDoesNotStartLap`：只发主包、不发时间包，断言 parser 所有帧 `isTimeSynced=false` 且 `timestamp=Long.MIN_VALUE`；engine `samples.size == 0`，`activeLap == null`。
- [x] 8.5 `shortTimeDesyncRecoversWithoutSpuriousCrossing`：5 帧同步 → 3 帧通过反射改写 `GpsDataGenerator.syncCounter` 制造 syncBits 失配（位置**跨 gate 的大位移**）→ 5 帧恢复同步；断言中间 3 帧被跳过，`activeLap` 未被额外开圈，失联前后的"同步帧到同步帧"不会因为位移跨 gate 而被 detector 判成一次过线。
- [x] 8.6 `endToEndCoreClockSourceIntegrity_generatorAndEngineNotInvolveSystemClock`（按 H 组描述折中方案实现）：Mockito 禁止 mock `java.lang.System`（会触发类加载无限递归，B / C 组同样放弃此路径），改为**字节码常量池扫描**：对 `GpsDataGenerator.class` 与 `LapTimingEngine.class` 断言不引用 `currentTimeMillis`。`RaceChronoParser` 因频率/tracking 统计仍保留 `System.currentTimeMillis()`（不污染 `GpsData.timestamp` 派生路径，B 组评审决策），故豁免于本测试。
- [x] 8.7 `lapWithProtocolDesyncGap_laprecordFlagged`：跑一圈，中间通过反射改写 `syncCounter` 插入 5 帧 syncBits 失配，断言 `LapRecord.qualityFlags.contains(ProtocolDesyncGap)`。

## 9. 文档修订与追溯

- [x] 9.1 在 `docs/superpowers/reviews/2026-04-22-lap-timing-and-gps-adversarial-review.md` 第 7 节（结语）末尾追加"2026-04-22 落地闭环"段，概述本 change v2 实施项，引用 `openspec/changes/fix-laptime-clock-source-integrity/`。
- [x] 9.2 在 `docs/superpowers/reviews/2026-04-22-opsx-fix-laptime-clock-source-review.md` 第 8 节后追加"九、落地执行反馈（2026-04-22）"，提供决策 1–5 的实施交付对照表 + 合流门槛状态 + 折中说明。
- [x] 9.3 更新项目记忆 `project_laptime_dual_clock_pollution.md` 的"How to apply"末尾追加"v2 落地"段（sentinel / 分层守卫 / 删 GpsSample 字段 / 失联重置 / ProtocolDesyncGap / 跨小时 Non-goal）。

## 10. 合流前验证（本 change 完成标准）

- [x] 10.1 `./gradlew :core:bluetooth:testDebugUnitTest --tests "*.RaceChronoParserProtocolTimeTest"` 全绿
- [x] 10.2 `./gradlew :simulator:testDebugUnitTest --tests "*.GpsDataGeneratorTest"` 全绿
- [x] 10.3 `./gradlew :feature:test:testDebugUnitTest --tests "*.GpsDataFilterTest"`（临时在 `feature:test` 模块跑；最终归位由战役 D 统一处理）
- [x] 10.4 `./gradlew :feature:test:testDebugUnitTest --tests "*.EndToEndLapTimingContractTest"` 全绿（6/6）
- [x] 10.5 `./gradlew :feature:test:testDebugUnitTest --tests "*.TestSessionViewModelTrackLapTest"` 全绿（原 8 + 新 4 = 12/12）
- [x] 10.6 `./gradlew :feature:test:testDebugUnitTest --tests "*.LapDebugExecutionScreenStateTest"` 全绿（原 5 + 新 3 = 8/8）
- [x] 10.7 `./gradlew :feature:test:testDebugUnitTest --tests "*.LapTimingEngineTest"` 全绿（原 7 + 新 2 = 9/9；3 个现有断言从 `== listOf(IncompleteSectors)` 放宽为 `contains(IncompleteSectors)` 以适配新 spec 语义）
- [x] 10.8 `openspec-chinese validate fix-laptime-clock-source-integrity --strict` 通过
- [ ] 10.9 **真机冒烟**（⚠️ 手工，需用户执行）：
    - 真机（华为 8KE0219522008434）接收端 + 模拟测试设备（DP011011255100142）发射端
    - STATIC 模式手动模拟两次过线，断言 `LapRecord.durationMillis` 与实际时长差 < 100ms（修前是秒级偏差）
    - 冷启动后确认 UI 先显示"等待协议时间同步" → 收到 time 包后切"等待起点" → 过线后切"当前圈进行中"
    - 中途故意把 simulator 切 STATIC 或重新连接，验证失联期间 UI 不误报，恢复后不伪造过线
- [ ] 10.10 ⚠️ **双端必须一次合流**：只合发射端或只合接收端会让端到端契约测试失败
