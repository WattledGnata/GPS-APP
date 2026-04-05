# Lap Timing Start/Finish Closure Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让圈速记录严格以两次 `start-finish` 有效穿线闭合整圈，同时只接受顺序 sector，允许缺失 sector 但仍生成不完整圈记录。

**Architecture:** 保持现有 `LapTimingEngine` 为单一圈速状态机入口，但把 gate 处理拆成“起终点闭圈”和“顺序 sector 记录”两条语义路径。`start-finish` 不再被 `nextExpectedGateIndex` 阻塞；sector 继续按顺序推进，缺失或乱序只影响 sector entries 与 quality flag，不阻止整圈在下一次 `start-finish` 闭合。

**Tech Stack:** Kotlin、Android library module、JUnit4 JVM unit tests、现有 `LapSession` / `LapRecord` / `CrossingEvent` 数据模型

---

## File structure

- Modify: `feature/test/src/main/java/com/blazepush/feature/test/usecase/LapTimingEngine.kt`
  - 调整圈速状态机：把 `start-finish` 从“受 nextExpectedGateIndex 驱动的一个 gate”改成“始终可用于开圈/闭圈的闭合门”。
  - 仅顺序接受 sector gate；乱序 sector 不推进状态。
- Modify: `feature/test/src/main/java/com/blazepush/feature/test/model/laptiming/LapQualityFlag.kt`
  - 增加“不完整圈”标记，供闭圈时写入 `LapRecord.qualityFlags`。
- Test: `feature/test/src/test/java/com/blazepush/feature/test/usecase/LapTimingEngineTest.kt`
  - 用 TDD 重写/补充状态机测试，锁定新的闭圈规则、顺序 sector 规则、缺失 sector 规则。

## Task 1: 锁定新的起终点闭圈语义

**Files:**
- Test: `feature/test/src/test/java/com/blazepush/feature/test/usecase/LapTimingEngineTest.kt`

- [ ] **Step 1: 写失败测试，证明第一次 `start-finish` 只开圈，不算完成圈**

```kotlin
@Test
fun processSample_firstStartFinishCrossing_startsLapWithoutCompletingLap() {
    val startedSession = engine.processSample(
        session = newSession(),
        track = track,
        previousSample = sample(timestampMillis = 1773477876490L, latitude = 30.4941093, longitude = 104.4334198),
        currentSample = sample(timestampMillis = 1773477876690L, latitude = 30.4941096, longitude = 104.4334258)
    )

    assertEquals(LapSessionStatus.Recording, startedSession.status)
    assertEquals(1, startedSession.currentLapIndex)
    assertEquals(0, startedSession.completedLaps.size)
    assertNotNull(startedSession.activeLap)
    assertEquals(listOf("start-finish"), startedSession.activeLap!!.passedGateIds)
}
```

- [ ] **Step 2: 跑单测，确认红测失败在旧的 gate 推进语义上**

Run: `cd "/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing" && ./gradlew :feature:test:testDebugUnitTest --tests com.blazepush.feature.test.usecase.LapTimingEngineTest.processSample_firstStartFinishCrossing_startsLapWithoutCompletingLap`
Expected: FAIL（如果当前断言与旧实现不一致，会在 `nextExpectedGateIndex` 或其他旧状态机假设上暴露差异）

- [ ] **Step 3: 写第二个失败测试，证明第二次 `start-finish` 才闭合整圈**

```kotlin
@Test
fun processSample_secondStartFinishCrossing_completesLapEvenWithoutAllSectors() {
    val startedSession = engine.processSample(
        session = newSession(),
        track = track,
        previousSample = sample(timestampMillis = 1773477876490L, latitude = 30.4941093, longitude = 104.4334198),
        currentSample = sample(timestampMillis = 1773477876690L, latitude = 30.4941096, longitude = 104.4334258)
    )

    val finishedSession = engine.processSample(
        session = startedSession,
        track = track,
        previousSample = sample(timestampMillis = 1773478143490L, latitude = 30.4899163, longitude = 104.4336467),
        currentSample = sample(timestampMillis = 1773478143690L, latitude = 30.4899217, longitude = 104.4336851)
    )

    assertEquals(1, finishedSession.completedLaps.size)
    val lap = finishedSession.completedLaps.first()
    assertEquals(1, lap.lapIndex)
    assertEquals(266_000L, lap.durationMillis)
    assertNotNull(finishedSession.activeLap)
    assertEquals(2, finishedSession.currentLapIndex)
}
```

- [ ] **Step 4: 跑第二个红测，确认当前实现还不能正确表达“缺失 sector 仍可闭圈”**

Run: `cd "/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing" && ./gradlew :feature:test:testDebugUnitTest --tests com.blazepush.feature.test.usecase.LapTimingEngineTest.processSample_secondStartFinishCrossing_completesLapEvenWithoutAllSectors`
Expected: FAIL（当前实现会把流程推进到 sector 期待态，而不是允许第二次 `start-finish` 闭圈）

- [ ] **Step 5: 提交测试骨架**

```bash
git add feature/test/src/test/java/com/blazepush/feature/test/usecase/LapTimingEngineTest.kt
git commit -m "test: lock lap closure on second start-finish crossing"
```

## Task 2: 让 `start-finish` 始终负责开圈/闭圈

**Files:**
- Modify: `feature/test/src/main/java/com/blazepush/feature/test/usecase/LapTimingEngine.kt`
- Test: `feature/test/src/test/java/com/blazepush/feature/test/usecase/LapTimingEngineTest.kt`

- [ ] **Step 1: 在 `LapTimingEngineTest.kt` 增加完整顺序圈测试，作为目标行为**

```kotlin
@Test
fun processSample_startFinishThenOrderedSectorsThenStartFinish_completesLapWithSectorTimes() {
    val startedSession = engine.processSample(
        session = newSession(),
        track = track,
        previousSample = sample(timestampMillis = 1773477876490L, latitude = 30.4941093, longitude = 104.4334198),
        currentSample = sample(timestampMillis = 1773477876690L, latitude = 30.4941096, longitude = 104.4334258)
    )

    val sectorOneSession = engine.processSample(
        session = startedSession,
        track = track,
        previousSample = sample(timestampMillis = 1773478127090L, latitude = 30.4900734, longitude = 104.4312922),
        currentSample = sample(timestampMillis = 1773478127290L, latitude = 30.4900505, longitude = 104.4312828)
    )

    val sectorTwoSession = engine.processSample(
        session = sectorOneSession,
        track = track,
        previousSample = sample(timestampMillis = 1773478135290L, latitude = 30.4897091, longitude = 104.4322254),
        currentSample = sample(timestampMillis = 1773478135490L, latitude = 30.4897044, longitude = 104.4322638)
    )

    val finishedSession = engine.processSample(
        session = sectorTwoSession,
        track = track,
        previousSample = sample(timestampMillis = 1773478143490L, latitude = 30.4899163, longitude = 104.4336467),
        currentSample = sample(timestampMillis = 1773478143690L, latitude = 30.4899217, longitude = 104.4336851)
    )

    val lap = finishedSession.completedLaps.first()
    assertEquals(listOf(250_600L, 8_200L), lap.sectorTimes)
    assertEquals(0, lap.qualityFlags.size)
    assertEquals(2, finishedSession.currentLapIndex)
    assertEquals(1, finishedSession.completedLaps.size)
}
```

- [ ] **Step 2: 跑这个测试，确认它在状态机调整前是红的或与旧断言冲突**

Run: `cd "/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing" && ./gradlew :feature:test:testDebugUnitTest --tests com.blazepush.feature.test.usecase.LapTimingEngineTest.processSample_startFinishThenOrderedSectorsThenStartFinish_completesLapWithSectorTimes`
Expected: FAIL（旧状态机对 start/finish 与 sector 的角色耦合不满足新规则）

- [ ] **Step 3: 在 `LapTimingEngine.kt` 做最小实现调整，把 `start-finish` 检测提升为优先分支**

```kotlin
fun processSample(
    session: LapSession,
    track: Track,
    previousSample: GpsSample,
    currentSample: GpsSample
): LapSession {
    val updatedSamples = session.samples + currentSample
    val startFinishDetection = detector.detect(previous = previousSample, current = currentSample, gate = track.startFinishGate)

    if (startFinishDetection.accepted) {
        return handleStartFinishCrossing(
            session = session,
            track = track,
            updatedSamples = updatedSamples,
            currentSample = currentSample,
            detection = startFinishDetection
        )
    }

    return handleSectorCrossing(
        session = session,
        track = track,
        previousSample = previousSample,
        currentSample = currentSample,
        updatedSamples = updatedSamples
    )
}
```
```

- [ ] **Step 4: 在同文件写出最小的 `handleStartFinishCrossing(...)`，区分开圈和闭圈**

```kotlin
private fun handleStartFinishCrossing(
    session: LapSession,
    track: Track,
    updatedSamples: List<GpsSample>,
    currentSample: GpsSample,
    detection: GateCrossingDetection
): LapSession {
    val crossingEvent = CrossingEvent(
        gateId = track.startFinishGate.id,
        gateType = track.startFinishGate.type,
        timestampMillis = currentSample.timestampMillis,
        sampleIndex = updatedSamples.lastIndex,
        accepted = detection.accepted,
        reason = detection.reason,
        directionalSpeedMps = detection.directionalSpeedMps,
        directionScore = detection.directionScore
    )
    val updatedEvents = session.crossingEvents + crossingEvent

    if (session.activeLap == null) {
        return session.copy(
            status = LapSessionStatus.Recording,
            startedAtMillis = session.startedAtMillis ?: currentSample.timestampMillis,
            samples = updatedSamples,
            currentLapIndex = 1,
            nextExpectedGateIndex = 1,
            crossingEvents = updatedEvents,
            activeLap = ActiveLap(
                lapIndex = 1,
                startedAtMillis = currentSample.timestampMillis,
                passedGateIds = listOf(track.startFinishGate.id),
                sampleStartIndex = updatedSamples.lastIndex
            )
        )
    }

    val activeLap = session.activeLap
    val lapRecord = LapRecord(
        recordId = "${session.sessionId}-lap-${activeLap.lapIndex}",
        sessionId = session.sessionId,
        trackId = session.trackId,
        lapIndex = activeLap.lapIndex,
        startedAtMillis = activeLap.startedAtMillis,
        finishedAtMillis = currentSample.timestampMillis,
        durationMillis = currentSample.timestampMillis - activeLap.startedAtMillis,
        sectorTimes = activeLap.sectorEntries.toSectorTimes(activeLap.startedAtMillis),
        trajectory = updatedSamples.drop(activeLap.sampleStartIndex),
        crossingEvents = updatedEvents.dropWhile { it.timestampMillis < activeLap.startedAtMillis },
        qualityFlags = emptyList()
    )
    val nextLapIndex = activeLap.lapIndex + 1
    return session.copy(
        status = LapSessionStatus.Recording,
        samples = updatedSamples,
        currentLapIndex = nextLapIndex,
        nextExpectedGateIndex = 1,
        crossingEvents = updatedEvents,
        completedLaps = session.completedLaps + lapRecord,
        activeLap = ActiveLap(
            lapIndex = nextLapIndex,
            startedAtMillis = currentSample.timestampMillis,
            passedGateIds = listOf(track.startFinishGate.id),
            sampleStartIndex = updatedSamples.lastIndex
        )
    )
}
```

- [ ] **Step 5: 跑 Task 1 和 Task 2 的单测，确认绿测**

Run: `cd "/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing" && ./gradlew :feature:test:testDebugUnitTest --tests com.blazepush.feature.test.usecase.LapTimingEngineTest.processSample_firstStartFinishCrossing_startsLapWithoutCompletingLap --tests com.blazepush.feature.test.usecase.LapTimingEngineTest.processSample_secondStartFinishCrossing_completesLapEvenWithoutAllSectors --tests com.blazepush.feature.test.usecase.LapTimingEngineTest.processSample_startFinishThenOrderedSectorsThenStartFinish_completesLapWithSectorTimes`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: 提交起终点闭圈修复**

```bash
git add feature/test/src/main/java/com/blazepush/feature/test/usecase/LapTimingEngine.kt feature/test/src/test/java/com/blazepush/feature/test/usecase/LapTimingEngineTest.kt
git commit -m "fix: close laps only on next start-finish crossing"
```

## Task 3: 只接受顺序 sector，并允许缺失 sector 的不完整圈

**Files:**
- Modify: `feature/test/src/main/java/com/blazepush/feature/test/model/laptiming/LapQualityFlag.kt`
- Modify: `feature/test/src/main/java/com/blazepush/feature/test/usecase/LapTimingEngine.kt`
- Test: `feature/test/src/test/java/com/blazepush/feature/test/usecase/LapTimingEngineTest.kt`

- [ ] **Step 1: 在 `LapQualityFlag.kt` 增加不完整圈标记**

```kotlin
enum class LapQualityFlag {
    LowAccuracy,
    SparseSamples,
    SuspectedJitter,
    IncompleteSectors
}
```

- [ ] **Step 2: 写失败测试，证明缺失 sector 也会生成 lap record，但会带 `IncompleteSectors`**

```kotlin
@Test
fun processSample_missingSectorStillCompletesLapWithIncompleteFlag() {
    val startedSession = engine.processSample(
        session = newSession(),
        track = track,
        previousSample = sample(timestampMillis = 1773477876490L, latitude = 30.4941093, longitude = 104.4334198),
        currentSample = sample(timestampMillis = 1773477876690L, latitude = 30.4941096, longitude = 104.4334258)
    )

    val sectorOneSession = engine.processSample(
        session = startedSession,
        track = track,
        previousSample = sample(timestampMillis = 1773478127090L, latitude = 30.4900734, longitude = 104.4312922),
        currentSample = sample(timestampMillis = 1773478127290L, latitude = 30.4900505, longitude = 104.4312828)
    )

    val finishedSession = engine.processSample(
        session = sectorOneSession,
        track = track,
        previousSample = sample(timestampMillis = 1773478143490L, latitude = 30.4899163, longitude = 104.4336467),
        currentSample = sample(timestampMillis = 1773478143690L, latitude = 30.4899217, longitude = 104.4336851)
    )

    val lap = finishedSession.completedLaps.first()
    assertEquals(listOf(250_600L), lap.sectorTimes)
    assertEquals(listOf(LapQualityFlag.IncompleteSectors), lap.qualityFlags)
}
```

- [ ] **Step 3: 跑红测，确认当前实现还没有写 quality flag**

Run: `cd "/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing" && ./gradlew :feature:test:testDebugUnitTest --tests com.blazepush.feature.test.usecase.LapTimingEngineTest.processSample_missingSectorStillCompletesLapWithIncompleteFlag`
Expected: FAIL（当前 `qualityFlags` 为 `emptyList()`）

- [ ] **Step 4: 写失败测试，证明乱序 sector 不被接收，但不会阻止下一次 `start-finish` 闭圈**

```kotlin
@Test
fun processSample_outOfOrderSectorIsIgnoredAndLapStillClosesOnNextStartFinish() {
    val startedSession = engine.processSample(
        session = newSession(),
        track = track,
        previousSample = sample(timestampMillis = 1773477876490L, latitude = 30.4941093, longitude = 104.4334198),
        currentSample = sample(timestampMillis = 1773477876690L, latitude = 30.4941096, longitude = 104.4334258)
    )

    val outOfOrderSession = engine.processSample(
        session = startedSession,
        track = track,
        previousSample = sample(timestampMillis = 1773478135290L, latitude = 30.4897091, longitude = 104.4322254),
        currentSample = sample(timestampMillis = 1773478135490L, latitude = 30.4897044, longitude = 104.4322638)
    )

    assertEquals(0, outOfOrderSession.activeLap!!.sectorEntries.size)
    assertEquals(1, outOfOrderSession.nextExpectedGateIndex)

    val finishedSession = engine.processSample(
        session = outOfOrderSession,
        track = track,
        previousSample = sample(timestampMillis = 1773478143490L, latitude = 30.4899163, longitude = 104.4336467),
        currentSample = sample(timestampMillis = 1773478143690L, latitude = 30.4899217, longitude = 104.4336851)
    )

    assertEquals(1, finishedSession.completedLaps.size)
    assertEquals(listOf(LapQualityFlag.IncompleteSectors), finishedSession.completedLaps.first().qualityFlags)
}
```

- [ ] **Step 5: 跑乱序红测，确认需要把 sector 检测改为“仅命中期待 sector 时记录，否则忽略/拒绝”**

Run: `cd "/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing" && ./gradlew :feature:test:testDebugUnitTest --tests com.blazepush.feature.test.usecase.LapTimingEngineTest.processSample_outOfOrderSectorIsIgnoredAndLapStillClosesOnNextStartFinish`
Expected: FAIL（当前实现的 unexpected gate 逻辑会与新语义不一致）

- [ ] **Step 6: 在 `LapTimingEngine.kt` 写最小的 sector 处理逻辑，只检测当前期待 sector**

```kotlin
private fun handleSectorCrossing(
    session: LapSession,
    track: Track,
    previousSample: GpsSample,
    currentSample: GpsSample,
    updatedSamples: List<GpsSample>
): LapSession {
    val activeLap = session.activeLap ?: return session.copy(samples = updatedSamples)
    val targetGate = track.sectorGates.sortedBy { it.sequenceIndex }.getOrNull(session.nextExpectedGateIndex - 1)
        ?: return session.copy(samples = updatedSamples)

    val detection = detector.detect(previous = previousSample, current = currentSample, gate = targetGate)
    val crossingEvent = CrossingEvent(
        gateId = targetGate.id,
        gateType = targetGate.type,
        timestampMillis = currentSample.timestampMillis,
        sampleIndex = updatedSamples.lastIndex,
        accepted = detection.accepted,
        reason = detection.reason,
        directionalSpeedMps = detection.directionalSpeedMps,
        directionScore = detection.directionScore
    )
    val updatedEvents = session.crossingEvents + crossingEvent

    if (!detection.accepted) {
        return session.copy(samples = updatedSamples, crossingEvents = updatedEvents)
    }

    return session.copy(
        samples = updatedSamples,
        nextExpectedGateIndex = session.nextExpectedGateIndex + 1,
        crossingEvents = updatedEvents,
        activeLap = activeLap.copy(
            passedGateIds = activeLap.passedGateIds + targetGate.id,
            sectorEntries = activeLap.sectorEntries + SectorEntry(
                gateId = targetGate.id,
                crossedAtMillis = currentSample.timestampMillis
            )
        )
    )
}
```

- [ ] **Step 7: 在 `handleStartFinishCrossing(...)` 写出不完整圈 quality flag 的最小实现**

```kotlin
val expectedSectorCount = track.sectorGates.size
val qualityFlags = if (activeLap.sectorEntries.size == expectedSectorCount) {
    emptyList()
} else {
    listOf(LapQualityFlag.IncompleteSectors)
}

val lapRecord = LapRecord(
    recordId = "${session.sessionId}-lap-${activeLap.lapIndex}",
    sessionId = session.sessionId,
    trackId = session.trackId,
    lapIndex = activeLap.lapIndex,
    startedAtMillis = activeLap.startedAtMillis,
    finishedAtMillis = currentSample.timestampMillis,
    durationMillis = currentSample.timestampMillis - activeLap.startedAtMillis,
    sectorTimes = activeLap.sectorEntries.toSectorTimes(activeLap.startedAtMillis),
    trajectory = updatedSamples.drop(activeLap.sampleStartIndex),
    crossingEvents = updatedEvents.dropWhile { it.timestampMillis < activeLap.startedAtMillis },
    qualityFlags = qualityFlags
)
```

- [ ] **Step 8: 跑全部 `LapTimingEngineTest`，确认新的闭圈与 sector 语义全绿**

Run: `cd "/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing" && ./gradlew :feature:test:testDebugUnitTest --tests com.blazepush.feature.test.usecase.LapTimingEngineTest`
Expected: BUILD SUCCESSFUL

- [ ] **Step 9: 提交 sector 顺序与不完整圈修复**

```bash
git add feature/test/src/main/java/com/blazepush/feature/test/model/laptiming/LapQualityFlag.kt feature/test/src/main/java/com/blazepush/feature/test/usecase/LapTimingEngine.kt feature/test/src/test/java/com/blazepush/feature/test/usecase/LapTimingEngineTest.kt
git commit -m "fix: allow incomplete laps while enforcing sector order"
```

## Task 4: 回归验证 ViewModel 与真机场景

**Files:**
- Test: `feature/test/src/test/java/com/blazepush/feature/test/viewmodel/TestSessionViewModelTrackLapTest.kt`
- Modify (only if assertions need更新): `feature/test/src/test/java/com/blazepush/feature/test/viewmodel/TestSessionViewModelTrackLapTest.kt`

- [ ] **Step 1: 在 `TestSessionViewModelTrackLapTest.kt` 增加缺失 sector 仍闭圈的 ViewModel 回归测试**

```kotlin
@Test
fun lapDebugMode_secondStartFinishClosesLapEvenWhenSectorChainIsIncomplete() = runTest {
    Dispatchers.setMain(dispatcher)
    try {
        val viewModel = createViewModel(runtimeTrackCatalog())

        viewModel.selectLapDebugMode("preset-tfic-lpcc")
        dispatcher.scheduler.advanceUntilIdle()

        emitGps(1773477876490L, 30.4941093, 104.4334198)
        dispatcher.scheduler.advanceUntilIdle()
        emitGps(1773477876690L, 30.4941096, 104.4334258)
        dispatcher.scheduler.advanceUntilIdle()

        emitGps(1773478143490L, 30.4899163, 104.4336467)
        dispatcher.scheduler.advanceUntilIdle()
        emitGps(1773478143690L, 30.4899217, 104.4336851)
        dispatcher.scheduler.advanceUntilIdle()

        val session = requireNotNull(viewModel.lapSession.value)
        assertEquals(1, session.completedLaps.size)
        assertEquals(2, session.currentLapIndex)
        assertTrue(session.completedLaps.first().qualityFlags.isNotEmpty())
    } finally {
        Dispatchers.resetMain()
    }
}
```

- [ ] **Step 2: 跑这个测试，确认 ViewModel 链路在引擎修复后是绿的**

Run: `cd "/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing" && ./gradlew :feature:test:testDebugUnitTest --tests com.blazepush.feature.test.viewmodel.TestSessionViewModelTrackLapTest.lapDebugMode_secondStartFinishClosesLapEvenWhenSectorChainIsIncomplete`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 跑回归测试集，确认没有把之前的 replay / timing card 修复打回去**

Run: `cd "/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing" && ./gradlew :feature:test:testDebugUnitTest --tests com.blazepush.feature.test.usecase.LapTimingEngineTest --tests com.blazepush.feature.test.viewmodel.TestSessionViewModelTrackLapTest --tests com.blazepush.feature.test.repository.ReplayAlignedTrackCatalogTest --tests com.blazepush.feature.test.ui.screen.LapDebugExecutionScreenStateTest`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 在华为真机复测一条完整/一条不完整圈**

Run:
```bash
adb -s 8KE0219522008434 shell run-as com.blazepush rm -f files/debug_log.txt
adb -s 8KE0219522008434 shell am start -n com.blazepush/.MainActivity
```
Expected: 应用启动成功，进入 LapDebug 后复测

人工验证要点：
- 第一次过 `start-finish` 后，圈开始但不产生 completed lap
- 按顺序过 `s1 -> s2` 后，再过 `start-finish`，产生完整圈
- 缺一个 sector 直接再过 `start-finish`，仍产生 lap，但标记为不完整
- 乱序命中 sector 时，不应把该 sector 计入顺序 sector time

- [ ] **Step 5: 提交最终回归与真机验证结果**

```bash
git add feature/test/src/test/java/com/blazepush/feature/test/viewmodel/TestSessionViewModelTrackLapTest.kt feature/test/src/main/java/com/blazepush/feature/test/usecase/LapTimingEngine.kt feature/test/src/main/java/com/blazepush/feature/test/model/laptiming/LapQualityFlag.kt feature/test/src/test/java/com/blazepush/feature/test/usecase/LapTimingEngineTest.kt
git commit -m "fix: align lap timing with start-finish closure semantics"
```

## Self-review

- Spec coverage: 本计划覆盖了用户刚确认的 4 个核心要求：`start-finish` 二次闭圈、sector 为 `start-finish -> s1 -> ... -> start-finish` 的完整链路、缺失 sector 仍生成 lap、乱序 sector 不计入。
- Placeholder scan: 计划内没有 `TODO` / `TBD` / “自行处理”式占位语句；测试、命令、文件路径都已写明。
- Type consistency: 使用的类型名与当前代码一致：`LapTimingEngine`、`LapRecord`、`LapQualityFlag`、`LapSession`、`SectorEntry`、`CrossingEvent`。新增 flag 名统一为 `IncompleteSectors`。
