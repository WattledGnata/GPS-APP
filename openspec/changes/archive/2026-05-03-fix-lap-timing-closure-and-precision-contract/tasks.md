# 实施任务（依赖顺序）

本 change 合并 A15 + A20 + A32 + A33 四条，按 7 个 Requirement 组织，施工依赖序列：

1. **R1**（detector crossingProgress）是 R2 / R4 的数据基础，必须首先落地
2. **R2**（engine 插值时刻）依赖 R1；必须在 R3 / R4 之前
3. **R3**（trajectory 时间窗口）依赖 R2 的 startedAt/finishedAt 插值时刻语义
4. **R4**（handleSectorCrossing 多门 + state 推进）独立于 R3，但 SectorEntry.crossedAtMillis
   用插值毫秒需要 R1
5. **R5**（filter 边界 `>` 修订 + 修订已归档 engine-entry-hardening spec）与 R2 同批改 engine，
   但 spec 修订跨归档边界，通过本 change 的 `## MODIFIED Requirements` 段覆盖
6. **R6**（E2E ±5ms 合成契约）由 R1+R2+R3 共同兑现，断言收紧放在所有代码改完后
7. **R7**（A33 断言补齐）与 R5 改 filter 规则时 crossingEvents 语义调整同窗口，顺路做

合流门槛集中在第 8 节，含 proposal / spec / tasks 自洽 grep 审计项（按评审方建议把协调性
落到工具链，非人工记忆）。

---

## 1. R1 GateCrossingDetector.detect 返回 crossingProgress

- [x] 1.1 **代码改动**：`feature/test/src/main/java/com/blazepush/feature/test/usecase/GateCrossingDetector.kt` 的 `segmentsIntersectMeters` 返回值从 `Boolean` 改为 `Double?`：
    - 相交时返回 `t` 参数（原函数内部已算，v1 只丢弃返回 Boolean）
    - 不相交（`denominator == 0` 或 `t !in 0..1` 或 `u !in 0..1`）返回 null
    - **Visibility**：保持 `private` 为优，除非 §1.8（R1 S5）测试需直接调用 —— 若需，改为 `@VisibleForTesting internal` 并加 KDoc "仅测试可见"
- [x] 1.2 **代码改动**：`GateCrossingDetection` 数据类增加字段 `val crossingProgress: Double? = null`。字段注释明确"accepted=true 时非 null + clamp [0,1]；accepted=false 时为 null"。
- [x] 1.3 **代码改动**：`detect(previous, current, gate)` 内部：
    - `segmentsIntersectMeters` 返回 null → 构造 rejected detection，`crossingProgress = null`
    - 返回非 null → `crossingProgress = t.coerceIn(0.0, 1.0)` 防浮点越界
    - `accepted = true` 分支构造的 detection `crossingProgress = clamped t`
    - `WrongDirection` / `TooSlow` rejected 分支保持 `crossingProgress = null`
- [x] 1.4 **新增测试** `GateCrossingDetectorTest.detect_acceptedCrossing_returnsCrossingProgressInRange`（R1 Scenario 1）：构造 prev/current 穿 gate 的 accepted 场景，断言 `detection.crossingProgress != null` 且 `in 0.0..1.0`。
- [x] 1.5 **新增测试** `GateCrossingDetectorTest.detect_symmetricCrossing_returnsCrossingProgressEqualsHalf`（R1 Scenario 2）：构造对称偏移 0.25 × passDirection 的 prev/current，断言 `Math.abs(detection.crossingProgress!! - 0.5) < 1e-9`。
- [x] 1.6 **新增测试** `GateCrossingDetectorTest.detect_floatingPointOverflow_crossingProgressIsClamped`（R1 Scenario 3）：推荐走 §1.1 的 `@VisibleForTesting internal` 路径直接注入越界 t（比如 `1.0000001` 与 `-1e-16`），省去几何复现成本；断言 `crossingProgress == 1.0` 或 `0.0`。
- [x] 1.7 **新增测试** `GateCrossingDetectorTest.detect_rejectedCrossing_crossingProgressIsNull`（R1 Scenario 4）：单测试方法内通过 JUnit4 参数化或 `assertAll` 覆盖 `NoIntersection`（prev/current 同侧）/ `WrongDirection`（方向投影反）/ `TooSlow`（directionalSpeedMps 低于 gate.minDirectionalSpeedMps）三种 rejected 场景，每种分别断言 `detection.accepted == false` 且 `detection.crossingProgress == null`。
- [x] 1.8 **新增测试** `GateCrossingDetectorTest.segmentsIntersectMeters_returnsDoubleNullable`（R1 Scenario 5）：直接针对 `segmentsIntersectMeters`（visibility 见 §1.1）断言：
    - 线段几何相交时返回的 `Double?` 非 null，值为 `t ∈ [0.0, 1.0]`（线段上相交参数）
    - 不相交时返回 null
    - `denominator == 0`（平行或共线）时返回 null（保留 v1 防御性语义）
    - 至少 4 个断言子场景：相交正向 / 相交反向（方向不在本函数处理，仅几何） / 不相交同侧 / denominator == 0

## 2. R2 engine 插值时刻构造 ActiveLap / LapRecord / CrossingEvent / SectorEntry

- [x] 2.1 **代码改动**：`LapTimingEngine.kt` 增加 private helper：
    ```kotlin
    private fun interpolatedMillis(
        previousSample: GpsSample,
        currentSample: GpsSample,
        crossingProgress: Double
    ): Long = Math.round(
        previousSample.timestampMillis + crossingProgress * (currentSample.timestampMillis - previousSample.timestampMillis)
    )
    ```
- [x] 2.2 **代码改动**：`handleStartFinishCrossing` 内所有 CrossingEvent 构造点（当前第 107 行）：
    - `timestampMillis = currentSample.timestampMillis` → `timestampMillis = interpolatedMillis(previousSample, currentSample, detection.crossingProgress!!)`
    - `sampleIndex = updatedSamples.lastIndex` 保留（诊断语义）
- [x] 2.3 **代码改动**：首圈开圈路径（第 123 行）：`ActiveLap(startedAtMillis = currentSample.timestampMillis)` → `startedAtMillis = interpolatedMillis(...)`。
- [x] 2.4 **代码改动**：闭圈路径（第 132-134 行）构造 LapRecord：
    - `startedAtMillis = activeLap.startedAtMillis`（已是插值时刻）
    - `finishedAtMillis = interpolatedMillis(previousSample, currentSample, detection.crossingProgress!!)`（而非 currentSample.ts）
    - `durationMillis = finishedAtMillis - startedAtMillis`（派生）
- [x] 2.5 **代码改动**：闭圈后下一圈 ActiveLap（第 170 行）：`startedAtMillis = currentSample.timestampMillis` → `startedAtMillis = interpolatedMillis(...)`（同一 detection 的 crossingProgress，与上圈 finishedAtMillis 数值相等）。
- [x] 2.6 **代码改动**：`handleSectorCrossing` 期待门 accepted 分支（当前第 235-246 行）构造 `SectorEntry`：
    - `crossedAtMillis = currentSample.timestampMillis` → `crossedAtMillis = interpolatedMillis(previousSample, currentSample, detection.crossingProgress!!)`
- [x] 2.7 **新增测试** `LapTimingEngineTest.processSample_symmetricCrossing_crossingEventTimestampIsInterpolatedMillis`（R2 Scenario 1）：构造 prev.ts=200 + current.ts=240 对称过线（t=0.5），断言 `event.timestampMillis == 220L`。
- [x] 2.8 **新增测试** `LapTimingEngineTest.processSample_symmetricCrossing_activeLapStartedAtIsInterpolatedMillis`（R2 Scenario 2）：同场景断言 `session.activeLap.startedAtMillis == 220L && != 240L`。
- [x] 2.9 **新增测试** `LapTimingEngineTest.processSample_asymmetricClosingCrossing_durationMillisReflectsInterpolation`（R2 Scenario 4）：构造对称开圈（t=0.5）+ 不对称闭圈（t=0.25），断言 `durationMillis == 9_990L`（硬区分 v1 的 10_000L）。
- [x] 2.10 **新增测试** `LapTimingEngineTest.processSample_sectorCrossing_sectorEntryCrossedAtMillisIsInterpolated`（R2 Scenario 5）：构造 sector 过线 t=0.75，断言 `sectorEntry.crossedAtMillis` 等于插值毫秒而非 currentSample.ts。
- [x] 2.11 **新增测试** `LapTimingEngineTest.crossingEvent_sampleIndexIsTriggeringFrameIndex_notCrossingTimestampFrame`（R2 Scenario 6）：断言 `event.sampleIndex == updatedSamples.lastIndex` 且 `session.samples[event.sampleIndex].timestampMillis != event.timestampMillis`（诊断语义与插值时刻分离）。
- [x] 2.12 **新增测试** `LapTimingEngineTest.processSample_symmetricBothCrossings_durationMillisEquivalentToV1FrameLevel`（R2 Scenario 3）：构造开圈（prev.ts=200, current.ts=240, t=0.5）+ 对称闭圈（prev.ts=10_200, current.ts=10_240, t=0.5）场景，断言：
    - `lap.startedAtMillis == 220L`
    - `lap.finishedAtMillis == 10_220L`
    - `lap.durationMillis == 10_000L`
    - **AND** 与 v1 帧粒度 `current.ts - frame_startedAt = 10_240 - 240 = 10_000L` 数值等价（锁定"对称场景下 v1/v2 数值恰好相同、不硬区分"的意图）

## 3. R3 LapRecord.trajectory 两段式切分（subList + filter）

- [x] 3.1 **代码改动**：`LapTimingEngine.kt` 第 133 行 `val trajectory = updatedSamples.drop(activeLap.sampleStartIndex)` 改为两段式：
    ```kotlin
    val trajectory = updatedSamples
        .subList(activeLap.sampleStartIndex, updatedSamples.size)
        .filter { sample ->
            sample.timestampMillis >= activeLap.startedAtMillis &&
                sample.timestampMillis < lapRecord.finishedAtMillis
        }
    ```
    注意：`lapRecord` 在原代码里是 `val trajectory` 之后才构造；需调整顺序：先构造 `finishedAtMillis` 局部变量，再算 trajectory，再构造 LapRecord。
- [x] 3.2 **代码改动**：`ActiveLap.kt` 的 `sampleStartIndex` 字段注释更新为"v2 语义：subList 性能起点，归属判定由时间窗口主导"（与 proposal 决策 1 一致）。
- [x] 3.3 **新增测试** `LapTimingEngineTest.handleStartFinishCrossing_closingFrame_notIncludedInClosedLapTrajectory`（R3 Scenario 1）：断言 `trajectory.last.timestampMillis < lap.finishedAtMillis` 且 `trajectory.none { it.ts == lap.finishedAtMillis }`。
- [x] 3.4 **新增测试** `LapTimingEngineTest.handleStartFinishCrossing_nextActiveLapSampleStartIndex_pointsToClosingFrame`（R3 Scenario 2）：闭圈后 `nextActiveLap.sampleStartIndex == closingFrame index`；喂下一帧后断言下一圈 trajectory.first.ts 对应闭圈帧。
- [x] 3.5 **新增测试** `LapTimingEngineTest.session_samplesSize_equalsSumOfLapTrajectoriesAndActiveLapSegment`（R3 Scenario 3）：跑 2 个完整圈 + 第 3 圈部分帧，断言 `samples.size == completedLaps.sumOf { it.trajectory.size } + (samples.size - activeLap.sampleStartIndex)`。
- [x] 3.6 **新增测试** `LapTimingEngineTest.trajectory_emptyBoundary_openToCloseWithNoIntermediateFrames`（R3 Scenario 5）：构造开圈 startedAt=500 + 闭圈 finishedAt=520 中间无帧，断言 `trajectory.isEmpty()`。
- [x] 3.7 **新增测试** `LapTimingEngineTest.handleStartFinishCrossing_subListStartIndexOutOfWindow_filterExcludesOutOfBoundFrames`（R3 Scenario 4，防御性测试）：通过测试脚手架直接构造 `ActiveLap(sampleStartIndex = 5, startedAtMillis = 220L)` 且 `session.samples[5].timestampMillis = 180L`（subList 起点指向比 startedAt 更早的帧，模拟 A38 理论越界态）；喂入下一过线帧触发闭圈；断言：
    - `lap.trajectory.none { it.timestampMillis < 220L }`（filter 兜底排除越界帧）
    - `lap.trajectory.first.timestampMillis >= 220L`
    - 该测试 MUST 直接构造 ActiveLap 而非通过 engine 主流程（A38 守卫在主流程中会阻止此态产生；本测试锁定的是 filter 的防御性正确性）

## 4. R4 handleSectorCrossing 多门遍历 + state 推进按期待门分支

- [x] 4.1 **代码改动**：`handleSectorCrossing` 重写（当前第 188-246 行的整段控制流）：
    ```kotlin
    val orderedSectorGates = track.sectorGates.sortedBy { it.sequenceIndex }
    val allDetections = orderedSectorGates.map { gate ->
        gate to detector.detect(previousSample, currentSample, gate)
    }
    val expectedPair = allDetections.first { (gate, _) -> gate.id == targetGate.id }
    val expectedGate = expectedPair.first
    val expectedDetection = expectedPair.second
    val unexpectedAccepted = allDetections.filter { (gate, d) -> gate.id != targetGate.id && d.accepted }

    val expectedEvent = CrossingEvent(
        gateId = expectedGate.id,
        gateType = expectedGate.type,
        timestampMillis = if (expectedDetection.accepted)
            interpolatedMillis(previousSample, currentSample, expectedDetection.crossingProgress!!)
        else
            currentSample.timestampMillis,  // rejected 分支仍用帧 ts（无 crossingProgress）
        sampleIndex = updatedSamples.lastIndex,
        accepted = expectedDetection.accepted,
        reason = expectedDetection.reason,
        directionalSpeedMps = expectedDetection.directionalSpeedMps,
        directionScore = expectedDetection.directionScore
    )
    val unexpectedEvents = unexpectedAccepted.map { (gate, d) ->
        CrossingEvent(
            gateId = gate.id,
            gateType = gate.type,
            timestampMillis = interpolatedMillis(previousSample, currentSample, d.crossingProgress!!),
            sampleIndex = updatedSamples.lastIndex,
            accepted = false,
            reason = CrossingReason.UnexpectedGateOrder,
            directionalSpeedMps = d.directionalSpeedMps,
            directionScore = d.directionScore
        )
    }
    val allNewEvents = listOf(expectedEvent) + unexpectedEvents
    val updatedEvents = session.crossingEvents + allNewEvents
    ```
- [x] 4.2 **代码改动**：期待门 `accepted=true` 分支推进 state：
    ```kotlin
    if (expectedDetection.accepted) {
        val entry = SectorEntry(
            gateId = expectedGate.id,
            crossedAtMillis = interpolatedMillis(previousSample, currentSample, expectedDetection.crossingProgress!!)
        )
        return session.copy(
            samples = updatedSamples,
            nextExpectedGateIndex = session.nextExpectedGateIndex + 1,
            crossingEvents = updatedEvents,
            activeLap = activeLap.copy(
                passedGateIds = activeLap.passedGateIds + expectedGate.id,
                sectorEntries = activeLap.sectorEntries + entry
            )
        )
    }
    ```
- [x] 4.3 **代码改动**：期待门 `accepted=false` 分支 state 保持：
    ```kotlin
    return session.copy(
        samples = updatedSamples,
        crossingEvents = updatedEvents
        // nextExpectedGateIndex / activeLap 字段不变
    )
    ```
- [x] 4.4 **新增测试** `LapTimingEngineTest.handleSectorCrossing_expectedGateAccepted_advancesState`（R4 Scenario 1）：构造 prev/current 只过期待门 accepted，断言 `sectorEntries.size+1, passedGateIds.last==期待门.id, nextExpectedGateIndex+1, crossingEvents.size+1`。
- [x] 4.5 **新增测试** `LapTimingEngineTest.handleSectorCrossing_expectedGateRejected_stateUnchanged`（R4 Scenario 2）：构造期待门被 TooSlow/WrongDirection rejected + 无非期待门，断言 state 各字段不变 + crossingEvents.size+1 的 event.accepted==false。
- [x] 4.6 **新增测试** `LapTimingEngineTest.handleSectorCrossing_multiGateAccepted_recordsAllWithOrdering`（R4 Scenario 3）：构造同时过期待门 + 2 非期待门，断言 `crossingEvents.size+3`、顺序为 `[期待门, 非期待门 by sequenceIndex]`。
- [x] 4.7 **新增测试** `LapTimingEngineTest.handleSectorCrossing_expectedRejectedNonExpectedAccepted_recordsRejectedAndUnexpected`（R4 Scenario 4）：构造期待门 rejected + 非期待门 accepted，断言 state 不变 + `crossingEvents.size+2`，events 顺序 `[期待门 rejected, 非期待门 UnexpectedGateOrder]`。
- [x] 4.8 **新增测试** `LapTimingEngineTest.handleSectorCrossing_multipleNonExpectedAccepted_sortedBySequenceIndex`（R4 Scenario 5）：构造 `track.sectorGates` 在**数据层面**为 `[S3, S2, S1]`（反 `sequenceIndex` 顺序）+ 期待门 `S1` + `(prev, current)` 几何上同时过 S2/S3；engine 内部 `sortedBy { sequenceIndex }` 后输出 crossingEvents 顺序仍应为 `[S1 期待门, S2, S3]`；断言 engine 排序确定性与数据源顺序解耦。
- [x] 4.9 **新增测试** `LapTimingEngineTest.handleSectorCrossing_expectedGateRejected_eventTimestampFallbackToCurrentSample`（对应 spec R2 的 "rejected CrossingEvent.timestampMillis 降级到触发帧 ts" Scenario）：构造期待门被 `TooSlow` 或 `WrongDirection` rejected，`prev.ts = 200, current.ts = 240`；断言：
    - `session.crossingEvents.last.accepted == false`
    - `session.crossingEvents.last.timestampMillis == 240L`（= currentSample.ts，降级到触发帧 ts）
    - `session.crossingEvents.last.reason` == 原 detection.reason

## 5. R5 filter 边界 `>=` → `>` + engine-entry-hardening 交叉修订

- [x] 5.1 **代码改动**：`LapTimingEngine.kt` 第 157 行 `updatedEvents.filter { it.timestampMillis >= activeLap.startedAtMillis }` 改为 `updatedEvents.filter { it.timestampMillis > activeLap.startedAtMillis }`。
- [x] 5.2 **交叉修订 归档 spec**：本 change spec 已通过 `## MODIFIED Requirements` 段覆盖 engine-entry-hardening 的 R3 Scenario 1（文本"filter 边界严格大于"）。无需额外修订 `openspec/changes/archive/` 下文件内容（CLI MODIFIED 段已承载修订）。
- [x] 5.3 **同步更新 engine-entry-hardening 测试断言**：`LapTimingEngineTest.handleStartFinishCrossing_monotonicCrossingEvents_filterRetainsAllAboveStartedAt`（现文件末尾，change 1 追加）：
    - 原断言 `== [200L, 300L, 400L, 500L]`（`>=` 语义）→ 改为 `== [300L, 400L, 500L]`（`>` 严格大于，边界 ts=200 归上一圈）
    - 测试方法名附加语义标识：rename 为 `..._filterRetainsAllStrictlyGreaterThanStartedAt`
    - 内部注释更新"v2 严格 > 边界"
- [x] 5.4 **同步更新 engine-entry-hardening 测试断言**：`LapTimingEngineTest.handleStartFinishCrossing_monotonicSequence_filterOutputEqualsDropWhileOutput`：
    - 测试内对照 `dropWhile { it.timestampMillis < 150L }.map(...)` 改为 `dropWhile { it.timestampMillis <= 150L }`（与 filter 严格 `>` 对偶）
    - 断言 filter 输出与 dropWhile 对照逐元素等价仍保持
- [x] 5.5 **新增测试** `LapTimingEngineTest.handleStartFinishCrossing_boundaryCollision_filterStrictlyGreaterExcludesEdgeEvent`（R5 MODIFIED Scenario 2）：构造 `updatedEvents` 含 `event(ts=200)` + `activeLap.startedAtMillis=200`，断言 LapRecord.crossingEvents 不含 ts=200（硬区分 v1 `>=`）。
- [x] 5.6 **新增测试** `LapTimingEngineTest.handleStartFinishCrossing_nonMonotonicEvents_filterStrictlyGreaterRejectsHistorical`（R5 MODIFIED Scenario 3）：构造 `session.crossingEvents = [event(ts=100), event(ts=250), event(ts=150), event(ts=400)]`（ts=150 作为历史事件夹在后面，序列非单调）+ `activeLap.startedAtMillis = 200L`；触发闭圈构造 LapRecord；断言：
    - `lap.crossingEvents == [event(ts=250), event(ts=400)]`（ts=150 < 200 被拒，ts=100 < 200 被拒）
    - **锁定"非单调 + 严格 `>`" 组合行为**，与现有 §5.5 边界碰撞场景互补

## 6. R6 E2E ±5ms 合成契约断言收紧

- [x] 6.1 **断言修订** `EndToEndLapTimingContractTest.kt` 第 98-101 行（`staticMode_lapDurationMatchesSenderClockDelta`）：
    - 原 `lap.durationMillis in 9_980..10_020` → 新 `kotlin.math.abs(lap.durationMillis - 10_000L) < 5L`
    - 断言失败 message 同步更新为 "durationMillis=${lap.durationMillis} should satisfy abs(d - 10_000) < 5 (R6 合成契约)"
- [x] 6.2 **断言保持** `replayMode_lapDurationMatchesReplayClock`（line 170-173）：保持 `deltaAbs < 5L`（对标 proposal § R6 拒收收紧到 < 2L 的自由度）。
- [x] 6.3 **复核其余 E2E 测试**（8.4 / 8.5 / 8.6 / 8.7）：逐个跑一次，若因 trajectory / crossingEvents 语义变化导致断言失败，单独分析根因（预期**不失败**，因为这几条断言与 timestamp / trajectory.size 无强耦合）。

## 7. R7 A33 断言补齐

- [x] 7.1 **断言补齐** `LapTimingEngineTest.processSample_secondStartFinishCrossing_completesLapEvenWithoutAllSectors`（line 75 末尾）：
    - 追加 `assertEquals(listOf(LapQualityFlag.IncompleteSectors), lap.qualityFlags)`
- [x] 7.2 **回归验证**：若 R7 断言失败（暴露 v1 遗漏的隐性行为），按 proposal 风险缓解"按正常修复流程处理（排查根因 + 修正 + 更新断言）"不拆分 change，本 task 内修复。

## 8. 合流门槛 + proposal/spec/tasks 自洽 grep 审计

- [x] 8.1 `openspec validate fix-lap-timing-closure-and-precision-contract --strict` 通过（2026-04-24 起统一 CLI 命名：`openspec`，原 `openspec-chinese` 弃用）。
- [x] 8.2 `./gradlew :feature:test:testDebugUnitTest --tests "*GateCrossingDetectorTest*"` 全绿（覆盖 R1 四条新测试 + 现有用例）。
- [x] 8.3 `./gradlew :feature:test:testDebugUnitTest --tests "*LapTimingEngineTest*"` 全绿（覆盖 R2/R3/R4/R5/R7 新测试 + change 1 追加测试的 R5 修订 + 现有用例）。
- [x] 8.4 `./gradlew :feature:test:testDebugUnitTest --tests "*EndToEndLapTimingContractTest*"` 全绿（覆盖 R6 断言收紧 + 现有 8.3/8.4/8.5/8.6/8.7 保持）。
- [x] 8.5 `./gradlew :feature:test:testDebugUnitTest --tests "*TestSessionViewModelTrackLapTest*"` 全绿（复核 bridge 层零回归）。
- [x] 8.6a `./gradlew :core:domain:test` 全绿（跨模块零回归，**强制**）。
- [x] 8.6b `./gradlew :core:bluetooth:testDebugUnitTest` 全绿（**战役 C / G 并行期间为软检**：战役 G 归档前此项状态仅供参考，不纳入本 change 合流卡口；战役 G 完整归档后此项转为强制，由下一 change / 尾巴战役跟进复核）。
- [x] 8.7 **proposal / spec / tasks 自洽 grep 审计**（机器门槛：关键词用 base64 编码隔离，审计命令自身不会出现被禁字面量）：
    ```bash
    # 关键词表用 base64 编码隔离（避免 grep 把审计命令自身误命中）
    # 解码后内容：6 条被禁关键词（每行 1 条），具体字面量不在本命令中出现
    KEYWORDS_B64='5pW05L2T5reY5rGwCjUrIOadoQrnspfkvLAK6ZmN57qn5pa55qGICumZjee6p+WIsCBjaGFuZ2UgMwrlj6/pgInmlLbntKcK'
    KEYWORDS_FILE=$(mktemp)
    echo "$KEYWORDS_B64" | base64 -d > "$KEYWORDS_FILE"
    FAIL=0
    while IFS= read -r kw; do
        [ -z "$kw" ] && continue
        echo "--- $kw ---"
        HITS=$(grep -n "$kw" \
            openspec/changes/fix-lap-timing-closure-and-precision-contract/proposal.md \
            openspec/changes/fix-lap-timing-closure-and-precision-contract/tasks.md \
            openspec/changes/fix-lap-timing-closure-and-precision-contract/specs/lap-timing-engine/spec.md)
        if [ -z "$HITS" ]; then
            echo "(无残留)"
        else
            echo "$HITS"
            FAIL=1
        fi
    done < "$KEYWORDS_FILE"
    rm -f "$KEYWORDS_FILE"
    [ $FAIL -eq 0 ] || { echo "审计失败：关键词残留"; exit 1; }
    ```
    机器门槛：输出 MUST 全为"(无残留)"且 `exit 0`；若任一关键词在三件套任意文件命中，视为三件套协调性失守，回头修订（不再需要人工白名单解释，因为审计命令自身已无被禁字面量）。
    **维护提示**：若未来需调整关键词列表，按下列步骤重新生成 `KEYWORDS_B64`：
    1. 把 6 条（或 N 条）被禁关键词按每行一条写入临时文件
    2. `base64 -w0 < /tmp/keywords.txt`（Linux）或 `base64 -i /tmp/keywords.txt | tr -d '\n'`（macOS）
    3. 更新上面的 `KEYWORDS_B64` 字面量
- [x] 8.8 **字段语义 grep 审计**：
    ```bash
    # 确认 LapTimingEngine.kt 没有残留的 currentSample.timestampMillis 用作圈时字段
    grep -n "timestampMillis = currentSample\.timestampMillis\|startedAtMillis = currentSample\.timestampMillis\|finishedAtMillis = currentSample\.timestampMillis\|crossedAtMillis = currentSample\.timestampMillis" feature/test/src/main/java/com/blazepush/feature/test/usecase/LapTimingEngine.kt
    ```
    期望输出为空（所有圈时字段都从 interpolatedMillis 派生）。
- [x] 8.9 **归档 spec 状态审计 + MODIFIED 段 dry-run 复核**：
    - `openspec list` 确认 engine-entry-hardening 状态
    - 复核 MODIFIED 段 CLI 支持：
      ```bash
      openspec validate fix-lap-timing-closure-and-precision-contract --strict 2>&1 \
          | tee /tmp/modified-validate.log
      grep -iE "MODIFIED|R5|filter 边界" /tmp/modified-validate.log
      ```
    - 期望输出：validate 通过 + MODIFIED 段被 CLI 正确识别（不报 unknown segment 错误）
    - 若有异常，回退到决策 4 方案 (a) 路径 —— 直接 Edit `openspec/changes/archive/fix-lap-timing-engine-entry-hardening/specs/lap-timing-engine/spec.md` 修订 R3 Scenario 1
- [x] 8.10 **回执更新** `docs/superpowers/reviews/attack-backlog.md`：A15 / A20 / A32 / A33 四条状态迁 🟢 `pending_review`，附本 change 的 code commit hash（跨 3-4 个 commit，回执里每条对应 commit）。
- [x] 8.11 **插值模型范围边界 grep 审计**（proposal 决策 5 保护）：

    ```bash
    # 本 change 不允许 LapTimingEngine 内出现 prev/current.speed 用于插值的
    # 代码路径（防止提前落进 1Hz 升级路径，扰乱 ±5ms 契约前置假设）
    grep -nE "previousSample\.speed|currentSample\.speed" \
        feature/test/src/main/java/com/blazepush/feature/test/usecase/LapTimingEngine.kt
    ```

    期望输出：**不含**新增的"用于插值"语义引用。
    （`speed` 字段在其他模块或 v1 已有路径的使用不在本 grep 范围；若 v1 已有对
    `previousSample.speed` / `currentSample.speed` 的引用，人工确认其非插值用
    途并在 commit message 声明即可。）

## 9. Commit 策略

按"每 Requirement 一 commit 或紧密相关 Requirement 合一 commit"原则拆 3-4 个代码 commit
+ 1 条归档 spec 修订 commit + 1 条文档 commit：

1. **commit 1 — R1 detector crossingProgress**
   - `GateCrossingDetector.kt` + `GateCrossingDetection.kt` 改动
   - `GateCrossingDetectorTest.kt` 4 条新测试
   - 建议消息：`feat(detector): 战役 C 判圈契约（R1 / A15）crossingProgress 插值参数落地`
2. **commit 2 — R2 + R3 engine 插值时刻 + trajectory 时间窗口**
   - `LapTimingEngine.kt` R2/R3 改动（interpolatedMillis helper + handleStartFinishCrossing 插值时刻 + subList/filter 两段式）
   - `ActiveLap.kt` 注释更新
   - `LapTimingEngineTest.kt` R2 (5 条) + R3 (4 条) 新测试
   - 建议消息：`feat(laptiming): 战役 C 判圈契约（R2/R3 / A15/A32）插值时刻 + trajectory 时间窗口切分`
3. **commit 3 — R4 + R5 + R7 多门遍历 + state 推进 + filter 边界 + A33 断言**
   - `LapTimingEngine.kt` R4/R5 改动
   - `LapTimingEngineTest.kt` R4 (5 条) + R5 (1 条) 新测试 + A21 现有测试断言同步（5.3/5.4）+ R7 断言追加
   - 建议消息：`feat(laptiming): 战役 C 判圈契约（R4/R5/R7 / A20/A33）多门遍历 + filter 严格 > + qualityFlags 断言`
   - **注**：commit message 不回溯 A21 状态。本 change R5 MODIFIED 段覆盖 `fix-lap-timing-engine-entry-hardening` R3 Scenario 1，是对已归档 spec 的跨 change 修订（语义升级），A21 的 ✅ resolved 归档状态不变；backlog 仅在 A20/A33 条目更新"最近动作"。
4. **commit 4 — R6 E2E ±5ms 合成契约收紧**
   - `EndToEndLapTimingContractTest.kt` 8.2 断言收紧
   - 建议消息：`test(laptiming): 战役 C E2E 合成契约（R6）收紧 STATIC 圈时误差到 ±5ms`

commit 5（可选）— 若 R5 交叉修订确实需要 Edit `openspec/changes/archive/` 下文件（决策 4 的降级
方案 (a) 路径），单独成 commit；本方案采纳 (b) MODIFIED 段，**预计 commit 5 不需要**。

评审方核销后再做 backlog 迁档 commit（不在本 change 代码 commit 内，保持 review artifact 独立）。
