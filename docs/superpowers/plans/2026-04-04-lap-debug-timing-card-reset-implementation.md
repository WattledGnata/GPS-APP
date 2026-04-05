# Lap Debug Timing Card Reset Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 重构圈速调试页的起终点计时卡，使其稳定展示上一圈、当前圈、当前圈路程、最近起点穿线时间和状态，并在退出后重新进入时回到全新干净会话。

**Architecture:** 保持现有 LapDebug 页面结构不变，只重构 `LapDebugExecutionScreen` 内的起终点计时卡状态模型、派生逻辑和布局。所有展示值都继续从 `LapSession` 派生，当前圈计时以最新 GPS sample 时间驱动，当前圈路程以最近 accepted 起终点后的样本轨迹距离累计，退出并重新进入的干净会话行为由 `TestSessionViewModel` 的现有会话生命周期测试锁住。

**Tech Stack:** Kotlin、Jetpack Compose、Android ViewModel、JUnit4、现有 LapSession / CrossingEvent / TimingGateType 模型

---

## File Structure

- **Modify:** `feature/test/src/main/java/com/blazepush/feature/test/ui/screen/LapDebugExecutionScreen.kt`
  - 责任：把 `StartFinishTimingCardState` 从三段自由文案重构为结构化字段；新增基于 `LapSession` 的上一圈/当前圈/路程/时分秒格式化派生逻辑；重构卡片布局为固定五字段展示。
- **Modify:** `feature/test/src/test/java/com/blazepush/feature/test/ui/screen/LapDebugExecutionScreenStateTest.kt`
  - 责任：用红绿测试锁住新的状态语义、路程累计窗口和时分秒格式化结果。
- **Modify:** `feature/test/src/test/java/com/blazepush/feature/test/viewmodel/TestSessionViewModelTest.kt`
  - 责任：补“停止退出后重新进入获得全新 LapSession、卡片状态回到初始态”的会话生命周期测试。

---

### Task 1: 重写起终点计时卡状态派生测试

**Files:**
- Modify: `feature/test/src/test/java/com/blazepush/feature/test/ui/screen/LapDebugExecutionScreenStateTest.kt`
- Test: `feature/test/src/test/java/com/blazepush/feature/test/ui/screen/LapDebugExecutionScreenStateTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test
fun rememberStartFinishTimingCardState_withoutAcceptedStartFinishCrossing_keepsInitialSummary() {
    val session = lapSession(
        samples = listOf(
            gpsSample(timestampMillis = 1_000L, latitude = 30.0, longitude = 104.0),
            gpsSample(timestampMillis = 2_000L, latitude = 30.0001, longitude = 104.0001)
        ),
        crossingEvents = emptyList()
    )

    val state = rememberStartFinishTimingCardState(session)

    assertEquals("--", state.lastLapElapsedLabel)
    assertEquals("0.000 s", state.currentLapElapsedLabel)
    assertEquals("0.0 m", state.currentLapDistanceLabel)
    assertEquals("--", state.lastStartFinishTimeLabel)
    assertEquals("等待起点", state.statusLabel)
}

@Test
fun rememberStartFinishTimingCardState_withFirstAcceptedStartFinishCrossing_startsCurrentLapSummary() {
    val session = lapSession(
        samples = listOf(
            gpsSample(timestampMillis = 3_000L, latitude = 30.0, longitude = 104.0),
            gpsSample(timestampMillis = 4_500L, latitude = 30.0001, longitude = 104.0001)
        ),
        crossingEvents = listOf(
            acceptedStartFinishCrossing(timestampMillis = 3_000L)
        )
    )

    val state = rememberStartFinishTimingCardState(session)

    assertEquals("--", state.lastLapElapsedLabel)
    assertEquals("1.500 s", state.currentLapElapsedLabel)
    assertEquals("14.7 m", state.currentLapDistanceLabel)
    assertEquals("08:00:03", state.lastStartFinishTimeLabel)
    assertEquals("当前圈进行中", state.statusLabel)
}

@Test
fun rememberStartFinishTimingCardState_withSecondAcceptedStartFinishCrossing_reportsLastLapAndResetsCurrentLapWindow() {
    val session = lapSession(
        samples = listOf(
            gpsSample(timestampMillis = 3_000L, latitude = 30.0, longitude = 104.0),
            gpsSample(timestampMillis = 5_000L, latitude = 30.0001, longitude = 104.0001),
            gpsSample(timestampMillis = 7_000L, latitude = 30.0002, longitude = 104.0002),
            gpsSample(timestampMillis = 8_000L, latitude = 30.0003, longitude = 104.0003)
        ),
        crossingEvents = listOf(
            acceptedStartFinishCrossing(timestampMillis = 3_000L),
            acceptedStartFinishCrossing(timestampMillis = 7_000L)
        )
    )

    val state = rememberStartFinishTimingCardState(session)

    assertEquals("4.000 s", state.lastLapElapsedLabel)
    assertEquals("1.000 s", state.currentLapElapsedLabel)
    assertEquals("14.7 m", state.currentLapDistanceLabel)
    assertEquals("08:00:07", state.lastStartFinishTimeLabel)
    assertEquals("当前圈进行中", state.statusLabel)
}
```

在同文件补两个最小 helper，保持测试数据可读：

```kotlin
private fun acceptedStartFinishCrossing(timestampMillis: Long): CrossingEvent =
    CrossingEvent(
        gateType = TimingGateType.StartFinish,
        timestampMillis = timestampMillis,
        accepted = true,
        distanceMeters = 0.0,
        triggerSampleIndex = 0
    )

private fun gpsSample(
    timestampMillis: Long,
    latitude: Double,
    longitude: Double
): LapGpsSample = LapGpsSample(
    timestampMillis = timestampMillis,
    latitude = latitude,
    longitude = longitude,
    speedKmh = 100.0,
    bearingDegrees = 180.0,
    horizontalAccuracyMeters = 1f,
    altitudeMeters = 500.0,
    source = GpsSampleSource.Live
)
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :feature:test:testDebugUnitTest --tests com.blazepush.feature.test.ui.screen.LapDebugExecutionScreenStateTest`
Expected: FAIL，因为 `StartFinishTimingCardState` 仍是旧字段，且当前状态派生仍输出 `elapsedLabel/detailLabel`。

- [ ] **Step 3: Write minimal implementation**

把 `LapDebugExecutionScreen.kt` 里的状态模型从：

```kotlin
data class StartFinishTimingCardState(
    val statusLabel: String,
    val elapsedLabel: String,
    val detailLabel: String
)
```

改成：

```kotlin
data class StartFinishTimingCardState(
    val lastLapElapsedLabel: String,
    val currentLapElapsedLabel: String,
    val currentLapDistanceLabel: String,
    val lastStartFinishTimeLabel: String,
    val statusLabel: String
)
```

并把 `rememberStartFinishTimingCardState(lapSession: LapSession?)` 改成下面这个结构化派生版本：

```kotlin
internal fun rememberStartFinishTimingCardState(lapSession: LapSession?): StartFinishTimingCardState {
    val acceptedStartFinishCrossings = lapSession
        ?.crossingEvents
        ?.filter { it.accepted && it.gateType == TimingGateType.StartFinish }
        .orEmpty()
    val latestAcceptedCrossing = acceptedStartFinishCrossings.lastOrNull()
    val previousAcceptedCrossing = acceptedStartFinishCrossings.dropLast(1).lastOrNull()
    val samples = lapSession?.samples.orEmpty()

    if (latestAcceptedCrossing == null) {
        return StartFinishTimingCardState(
            lastLapElapsedLabel = "--",
            currentLapElapsedLabel = "0.000 s",
            currentLapDistanceLabel = "0.0 m",
            lastStartFinishTimeLabel = "--",
            statusLabel = "等待起点"
        )
    }

    val latestSampleTimestamp = samples.lastOrNull()?.timestampMillis ?: latestAcceptedCrossing.timestampMillis
    val currentLapElapsedMillis = (latestSampleTimestamp - latestAcceptedCrossing.timestampMillis).coerceAtLeast(0L)
    val currentLapDistanceMeters = calculateDistanceSince(samples, latestAcceptedCrossing.timestampMillis)

    return StartFinishTimingCardState(
        lastLapElapsedLabel = previousAcceptedCrossing
            ?.let { formatElapsedMillis(latestAcceptedCrossing.timestampMillis - it.timestampMillis) }
            ?: "--",
        currentLapElapsedLabel = formatElapsedMillis(currentLapElapsedMillis),
        currentLapDistanceLabel = formatDistanceMeters(currentLapDistanceMeters),
        lastStartFinishTimeLabel = formatTimeOfDay(latestAcceptedCrossing.timestampMillis),
        statusLabel = "当前圈进行中"
    )
}
```

同文件新增最小格式化/累计函数：

```kotlin
private fun calculateDistanceSince(samples: List<LapGpsSample>, startTimestampMillis: Long): Double {
    val window = samples.filter { it.timestampMillis >= startTimestampMillis }
    if (window.size < 2) return 0.0
    return window.zipWithNext { first, second ->
        distanceMeters(
            startLat = first.latitude,
            startLon = first.longitude,
            endLat = second.latitude,
            endLon = second.longitude
        )
    }.sum()
}

private fun formatDistanceMeters(distanceMeters: Double): String =
    String.format(Locale.US, "%.1f m", distanceMeters)

private fun formatTimeOfDay(timestampMillis: Long): String =
    Instant.ofEpochMilli(timestampMillis)
        .atZone(ZoneId.systemDefault())
        .toLocalTime()
        .format(DateTimeFormatter.ofPattern("HH:mm:ss"))
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :feature:test:testDebugUnitTest --tests com.blazepush.feature.test.ui.screen.LapDebugExecutionScreenStateTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add feature/test/src/main/java/com/blazepush/feature/test/ui/screen/LapDebugExecutionScreen.kt feature/test/src/test/java/com/blazepush/feature/test/ui/screen/LapDebugExecutionScreenStateTest.kt
git commit -m "test: cover lap debug timing summary state"
```

---

### Task 2: 重构起终点计时卡布局为固定摘要卡

**Files:**
- Modify: `feature/test/src/main/java/com/blazepush/feature/test/ui/screen/LapDebugExecutionScreen.kt`
- Test: `feature/test/src/test/java/com/blazepush/feature/test/ui/screen/LapDebugExecutionScreenStateTest.kt`

- [ ] **Step 1: Write the failing UI-facing state test**

在 `LapDebugExecutionScreenStateTest.kt` 里补一个状态测试，锁住标题字段都可直接渲染，不再依赖 `detailLabel`：

```kotlin
@Test
fun startFinishTimingCardState_exposesFixedSummaryFieldsForUi() {
    val state = StartFinishTimingCardState(
        lastLapElapsedLabel = "4.000 s",
        currentLapElapsedLabel = "1.250 s",
        currentLapDistanceLabel = "32.4 m",
        lastStartFinishTimeLabel = "17:02:13",
        statusLabel = "当前圈进行中"
    )

    assertEquals("4.000 s", state.lastLapElapsedLabel)
    assertEquals("1.250 s", state.currentLapElapsedLabel)
    assertEquals("32.4 m", state.currentLapDistanceLabel)
    assertEquals("17:02:13", state.lastStartFinishTimeLabel)
    assertEquals("当前圈进行中", state.statusLabel)
}
```

然后删掉旧断言里对 `elapsedLabel/detailLabel` 的引用，确保测试文件只认新字段。

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :feature:test:testDebugUnitTest --tests com.blazepush.feature.test.ui.screen.LapDebugExecutionScreenStateTest`
Expected: FAIL，如果 `StartFinishTimingCard` 或状态仍依赖旧字段名，编译会失败。

- [ ] **Step 3: Write minimal implementation**

把 `StartFinishTimingCard` 从旧布局：

```kotlin
Text(text = state.statusLabel)
Text(text = state.elapsedLabel, ...)
Text(text = state.detailLabel)
```

改成固定三行摘要布局：

```kotlin
@Composable
private fun StartFinishTimingCard(state: StartFinishTimingCardState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(text = "起终点计时", fontWeight = FontWeight.Bold)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TimingSummaryField(
                    modifier = Modifier.weight(1f),
                    label = "上一圈",
                    value = state.lastLapElapsedLabel
                )
                TimingSummaryField(
                    modifier = Modifier.weight(1f),
                    label = "当前圈",
                    value = state.currentLapElapsedLabel
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TimingSummaryField(
                    modifier = Modifier.weight(1f),
                    label = "当前圈路程",
                    value = state.currentLapDistanceLabel
                )
                TimingSummaryField(
                    modifier = Modifier.weight(1f),
                    label = "最近起点穿线",
                    value = state.lastStartFinishTimeLabel
                )
            }
            TimingSummaryField(
                modifier = Modifier.fillMaxWidth(),
                label = "状态",
                value = state.statusLabel
            )
        }
    }
}

@Composable
private fun TimingSummaryField(
    modifier: Modifier = Modifier,
    label: String,
    value: String
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = label, style = MaterialTheme.typography.labelMedium)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold
        )
    }
}
```

要求：
- 卡片标题统一为 `起终点计时`
- 不再拼接自由文案 `detailLabel`
- 不新增第二张卡，不改其它 telemetry 区块

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :feature:test:testDebugUnitTest --tests com.blazepush.feature.test.ui.screen.LapDebugExecutionScreenStateTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add feature/test/src/main/java/com/blazepush/feature/test/ui/screen/LapDebugExecutionScreen.kt feature/test/src/test/java/com/blazepush/feature/test/ui/screen/LapDebugExecutionScreenStateTest.kt
git commit -m "feat: redesign lap debug timing summary card"
```

---

### Task 3: 锁住停止退出后重新进入的干净会话行为

**Files:**
- Modify: `feature/test/src/test/java/com/blazepush/feature/test/viewmodel/TestSessionViewModelTest.kt`
- Test: `feature/test/src/test/java/com/blazepush/feature/test/viewmodel/TestSessionViewModelTest.kt`

- [ ] **Step 1: Write the failing session lifecycle test**

在 `TestSessionViewModelTest.kt` 里补一个完整生命周期测试：

```kotlin
@Test
fun lapDebugMode_reenteringAfterStopAndExit_createsFreshSession() {
    val viewModel = buildTestSessionViewModel()
    val config = lapRunConfig()

    viewModel.selectLapDebugMode(config)
    val firstSessionId = requireNotNull(viewModel.lapSession.value).sessionId

    viewModel.stopLapDebugSession()
    viewModel.exitLapDebugMode()
    viewModel.selectLapDebugMode(config)

    val secondSession = requireNotNull(viewModel.lapSession.value)
    assertNotEquals(firstSessionId, secondSession.sessionId)
    assertEquals(LapSessionStatus.Ready, secondSession.status)
    assertTrue(secondSession.samples.isEmpty())
    assertTrue(secondSession.crossingEvents.isEmpty())
}
```

如果当前测试文件里缺少 `lapRunConfig()` helper，就补最小 helper：

```kotlin
private fun lapRunConfig(): LapRunConfig = LapRunConfig(
    track = sampleTrack(),
    minLapDurationMillis = 5_000L,
    autoStart = true
)
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :feature:test:testDebugUnitTest --tests com.blazepush.feature.test.viewmodel.TestSessionViewModelTest`
Expected: FAIL，如果当前测试未覆盖重新进入语义，新增断言会先红；若直接 PASS，则继续下一步并把这条视为行为锁定。

- [ ] **Step 3: Write minimal implementation**

如果测试失败，仅在 `TestSessionViewModel.kt` 做最小修正，保证重新进入一定通过 `selectLapDebugMode` 新建新 session，而不会复用旧对象。目标代码应保持下面语义：

```kotlin
fun selectLapDebugMode(config: LapRunConfig) {
    val track = config.track
    _currentMode.value = TestMode.LapDebug
    _lapRunConfig.value = config
    _lapSession.value = LapSession(
        sessionId = UUID.randomUUID().toString(),
        trackId = track.id,
        status = LapSessionStatus.Ready
    )
    isLapRecording = true
    _latestLapRecords.value = emptyList()
    lastLapGpsSample = null
}

fun exitLapDebugMode() {
    isLapRecording = false
    lastLapGpsSample = null
    _currentMode.value = TestMode.Acceleration
    _lapRunConfig.value = null
    _lapSession.value = null
    _latestLapRecords.value = emptyList()
}
```

要求：
- 不新增“页内重置按钮”
- 不保留旧 session 的 samples/crossings
- 只修会话生命周期，不碰穿线算法

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :feature:test:testDebugUnitTest --tests com.blazepush.feature.test.viewmodel.TestSessionViewModelTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt feature/test/src/test/java/com/blazepush/feature/test/viewmodel/TestSessionViewModelTest.kt
git commit -m "test: lock lap debug session reset on reentry"
```

---

### Task 4: 做最小集成回归验证

**Files:**
- Modify: none
- Test: `feature/test/src/test/java/com/blazepush/feature/test/ui/screen/LapDebugExecutionScreenStateTest.kt`
- Test: `feature/test/src/test/java/com/blazepush/feature/test/viewmodel/TestSessionViewModelTest.kt`

- [ ] **Step 1: Run the focused UI state tests**

Run: `./gradlew :feature:test:testDebugUnitTest --tests com.blazepush.feature.test.ui.screen.LapDebugExecutionScreenStateTest`
Expected: PASS

- [ ] **Step 2: Run the focused ViewModel lifecycle tests**

Run: `./gradlew :feature:test:testDebugUnitTest --tests com.blazepush.feature.test.viewmodel.TestSessionViewModelTest`
Expected: PASS

- [ ] **Step 3: Run the full feature/test unit test task**

Run: `./gradlew :feature:test:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Build the app for local verification**

Run: `./gradlew :app:assembleDebug :feature:test:assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add feature/test/src/main/java/com/blazepush/feature/test/ui/screen/LapDebugExecutionScreen.kt feature/test/src/test/java/com/blazepush/feature/test/ui/screen/LapDebugExecutionScreenStateTest.kt feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt feature/test/src/test/java/com/blazepush/feature/test/viewmodel/TestSessionViewModelTest.kt
git commit -m "feat: refresh lap debug timing summary behavior"
```

---

## Self-Review

- **Spec coverage:**
  - 卡片固定五字段：Task 1 + Task 2 覆盖
  - accepted 起点驱动上一圈/当前圈/当前圈路程/最近穿线时间：Task 1 覆盖
  - 最近起点穿线显示时分秒：Task 1 覆盖
  - 停止退出后重新进入获得干净会话：Task 3 覆盖
  - 不改穿线算法、不加重置按钮：Task 2 和 Task 3 明确限制
- **Placeholder scan:** 无 TBD/TODO/“类似上一步”占位；每个任务都给出了明确文件、代码、命令和期望结果。
- **Type consistency:** 全文统一使用 `StartFinishTimingCardState`、`rememberStartFinishTimingCardState`、`currentLapElapsedLabel`、`currentLapDistanceLabel`、`lastStartFinishTimeLabel`、`TestSessionViewModel`、`LapSessionStatus.Ready`，与设计稿和任务步骤保持一致。
