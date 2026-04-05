# TFIC LPCC Preset Track Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在当前 LapDebug 测试页面中新增可选固定赛道 `preset-tfic-lpcc`，让现有判圈链路可以基于 TFIC LPCC 的起点、s1、s2 进行运行时判定。

**Architecture:** 保持现有 `TrackCatalog -> LapDebug 配置页 -> TestSessionViewModel -> LapTimingEngine` 主链路不变，只扩展 `PresetTrackCatalog` 的内容。通过测试先约束新增赛道的存在、门线顺序和 ViewModel 选择能力，再用最小代码把 TFIC LPCC 的固定赛道硬编码进 `PresetTracks.kt`。

**Tech Stack:** Kotlin, JUnit4, Android ViewModel, StateFlow

---

## File Structure

- Modify: `feature/test/src/main/java/com/blazepush/feature/test/repository/PresetTracks.kt`
  - 保留 `preset-demo-circuit`，新增 `preset-tfic-lpcc`，把 RCZ 提取后的起点 / s1 / s2 与 replay reference path 固化为当前运行时 preset 数据。
- Modify: `feature/test/src/test/java/com/blazepush/feature/test/repository/TrackCatalogTest.kt`
  - 为 `PresetTrackCatalog` 增加 TDD 约束：必须同时暴露 demo 与 TFIC 两条赛道，并验证 TFIC 的 gate 顺序和类型。
- Modify: `feature/test/src/test/java/com/blazepush/feature/test/viewmodel/TestSessionViewModelTrackLapTest.kt`
  - 为 `TestSessionViewModel.selectLapDebugMode` 增加选择 `preset-tfic-lpcc` 的测试，确保 UI 入口可以进入新的固定赛道。

---

### Task 1: 先用测试锁定 TFIC preset 赛道目录内容

**Files:**
- Modify: `feature/test/src/test/java/com/blazepush/feature/test/repository/TrackCatalogTest.kt`
- Modify: `feature/test/src/main/java/com/blazepush/feature/test/repository/PresetTracks.kt`

- [ ] **Step 1: 在 TrackCatalogTest 中新增失败测试，约束必须暴露 TFIC preset track**

```kotlin
package com.blazepush.feature.test.repository

import com.blazepush.feature.test.model.track.TimingGateType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackCatalogTest {

    @Test
    fun `preset catalog exposes demo track and tfic lpcc track`() {
        val catalog = PresetTrackCatalog()

        val tracks = catalog.getAllTracks()

        assertTrue(tracks.any { it.id == "preset-demo-circuit" })
        assertTrue(tracks.any { it.id == "preset-tfic-lpcc" })
    }

    @Test
    fun `tfic lpcc track maps start finish and sector gates in expected order`() {
        val catalog = PresetTrackCatalog()

        val track = catalog.getTrack("preset-tfic-lpcc")

        assertNotNull(track)
        assertEquals("TFIC LPCC", track?.name)
        assertEquals(TimingGateType.StartFinish, track?.startFinishGate?.type)
        assertEquals("起点", track?.startFinishGate?.name)
        assertEquals(listOf("s1", "s2"), track?.sectorGates?.map { it.name })
        assertEquals(listOf(1, 2), track?.sectorGates?.map { it.sequenceIndex })
        assertEquals(listOf(TimingGateType.Sector, TimingGateType.Sector), track?.sectorGates?.map { it.type })
    }
}
```

- [ ] **Step 2: 运行单测，确认它先失败**

Run: `./gradlew :feature:test:testDebugUnitTest --tests com.blazepush.feature.test.repository.TrackCatalogTest`
Expected: FAIL，提示缺少 `preset-tfic-lpcc` 或 gate 断言不成立。

- [ ] **Step 3: 在 PresetTracks.kt 中新增最小 TFIC LPCC 固定赛道实现**

在现有 `presetTracks` 列表中追加一个 `Track(...)`，结构按下面形式落地：

```kotlin
Track(
    id = "preset-tfic-lpcc",
    name = "TFIC LPCC",
    layoutName = "RaceChrono RCZ",
    referencePath = TrackPath(
        points = listOf(
            // 这里填入从 tianfu_track_replay_5hz.json 抽取并固化的 GeoPoint 列表
            // 至少先放入首批连续样本点，最终实现时替换为完整或经确认足够的 referencePath
            GeoPoint(39.49457333333333, 104.43323583333334),
            GeoPoint(39.4945735, 104.43323583333334)
        )
    ),
    startFinishGate = TimingGate(
        id = "tfic-sf",
        name = "起点",
        type = TimingGateType.StartFinish,
        line = GeoLine(
            start = GeoPoint(/* 由 RCZ 换算后的门线起点 */),
            end = GeoPoint(/* 由 RCZ 换算后的门线终点 */)
        ),
        passDirection = GeoVector(x = /* 固化值 */, y = /* 固化值 */),
        sequenceIndex = 0,
        minDirectionalSpeedMps = 2.0
    ),
    sectorGates = listOf(
        TimingGate(
            id = "tfic-s1",
            name = "s1",
            type = TimingGateType.Sector,
            line = GeoLine(
                start = GeoPoint(/* 由 RCZ 换算后的门线起点 */),
                end = GeoPoint(/* 由 RCZ 换算后的门线终点 */)
            ),
            passDirection = GeoVector(x = /* 固化值 */, y = /* 固化值 */),
            sequenceIndex = 1,
            minDirectionalSpeedMps = 2.0
        ),
        TimingGate(
            id = "tfic-s2",
            name = "s2",
            type = TimingGateType.Sector,
            line = GeoLine(
                start = GeoPoint(/* 由 RCZ 换算后的门线起点 */),
                end = GeoPoint(/* 由 RCZ 换算后的门线终点 */)
            ),
            passDirection = GeoVector(x = /* 固化值 */, y = /* 固化值 */),
            sequenceIndex = 2,
            minDirectionalSpeedMps = 2.0
        )
    )
)
```

实现要求：
- 不删除 `preset-demo-circuit`
- `sectorGates` 顺序必须是 `s1` 再 `s2`
- `p房` 不进入 `Track`
- `referencePath` 使用 replay JSON 转换出的轨迹点，不从 `.rcz` 猜中心线

- [ ] **Step 4: 运行单测，确认 TrackCatalogTest 通过**

Run: `./gradlew :feature:test:testDebugUnitTest --tests com.blazepush.feature.test.repository.TrackCatalogTest`
Expected: PASS

- [ ] **Step 5: 提交本任务**

```bash
git add feature/test/src/test/java/com/blazepush/feature/test/repository/TrackCatalogTest.kt feature/test/src/main/java/com/blazepush/feature/test/repository/PresetTracks.kt
git commit -m "feat: add tfic lpcc preset track"
```

### Task 2: 用测试锁定 ViewModel 可以选择 TFIC preset

**Files:**
- Modify: `feature/test/src/test/java/com/blazepush/feature/test/viewmodel/TestSessionViewModelTrackLapTest.kt`
- Modify: `feature/test/src/main/java/com/blazepush/feature/test/repository/PresetTracks.kt`

- [ ] **Step 1: 在 TestSessionViewModelTrackLapTest 中新增失败测试，验证可选择 TFIC track**

在现有测试类中追加：

```kotlin
@Test
fun `selecting lap debug mode with tfic track stores tfic lap run config`() = runTest {
    Dispatchers.setMain(dispatcher)
    try {
        val viewModel = createViewModel()

        val config = LapRunConfig(trackId = "preset-tfic-lpcc")
        viewModel.selectLapDebugMode(config)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(TestMode.LapDebug, viewModel.currentMode.value)
        assertEquals("preset-tfic-lpcc", viewModel.lapRunConfig.value?.trackId)
        assertEquals("preset-tfic-lpcc", viewModel.lapSession.value?.trackId)
        assertTrue(viewModel.availableTracks.value.any { track -> track.id == "preset-tfic-lpcc" })
    } finally {
        Dispatchers.resetMain()
    }
}
```

- [ ] **Step 2: 运行单测，确认它先失败**

Run: `./gradlew :feature:test:testDebugUnitTest --tests com.blazepush.feature.test.viewmodel.TestSessionViewModelTrackLapTest`
Expected: FAIL，如果 `preset-tfic-lpcc` 尚未正确加入 catalog 或测试仍假设唯一 demo 赛道。

- [ ] **Step 3: 做最小修正，让 ViewModel 测试基于新赛道列表通过**

如果 Task 1 已正确完成，这一步通常只需要确保测试代码显式引用 `preset-tfic-lpcc`，不要再隐式依赖“只有一个 preset 赛道”。保留现有 `createViewModel()` 实现：

```kotlin
return TestSessionViewModel(
    gpsDataViewModel = gpsDataViewModel,
    bleDeviceManager = bleDeviceManager,
    testResultRepository = mock(TestResultRepository::class.java),
    calculateResultUseCase = mock(CalculateResultUseCase::class.java),
    trackCatalog = PresetTrackCatalog(),
    lapTimingEngine = LapTimingEngine()
)
```

如果已有断言写死 `preset-demo-circuit` 是唯一候选项，把断言改成“显式可选到目标赛道”，不要对列表长度作多余假设。

- [ ] **Step 4: 运行单测，确认 ViewModel 选择链路通过**

Run: `./gradlew :feature:test:testDebugUnitTest --tests com.blazepush.feature.test.viewmodel.TestSessionViewModelTrackLapTest`
Expected: PASS

- [ ] **Step 5: 提交本任务**

```bash
git add feature/test/src/test/java/com/blazepush/feature/test/viewmodel/TestSessionViewModelTrackLapTest.kt
git commit -m "test: cover tfic lap debug selection"
```

### Task 3: 做一次目标化回归验证

**Files:**
- Test: `feature/test/src/test/java/com/blazepush/feature/test/repository/TrackCatalogTest.kt`
- Test: `feature/test/src/test/java/com/blazepush/feature/test/viewmodel/TestSessionViewModelTrackLapTest.kt`
- Verify: `feature/test/src/main/java/com/blazepush/feature/test/ui/screen/LapDebugConfigScreen.kt`

- [ ] **Step 1: 运行两个目标测试类，确认新增赛道与选择链路都通过**

Run: `./gradlew :feature:test:testDebugUnitTest --tests com.blazepush.feature.test.repository.TrackCatalogTest --tests com.blazepush.feature.test.viewmodel.TestSessionViewModelTrackLapTest`
Expected: PASS

- [ ] **Step 2: 运行 feature:test 模块完整单测，确认没有被新增 preset 破坏**

Run: `./gradlew :feature:test:testDebugUnitTest`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 自查 UI 接入点，确认配置页会自动展示新增 TFIC 赛道**

确认以下逻辑保持不变即可，不需要再写代码：

```kotlin
LapDebugConfigScreen(
    availableTracks = availableTracks,
    initialConfig = lapRunConfig,
    onConfirm = { config: LapRunConfig ->
        testSessionViewModel.selectLapDebugMode(config)
        currentRoute = TestNavRoute.Execution
    },
    onBack = {
        currentRoute = TestNavRoute.Selection
    }
)
```

以及默认选中逻辑仍然基于 `availableTracks.firstOrNull()?.id`：

```kotlin
LaunchedEffect(availableTracks, selectedTrackId) {
    if (selectedTrackId == null || availableTracks.none { it.id == selectedTrackId }) {
        selectedTrackId = availableTracks.firstOrNull()?.id
    }
}
```

这保证新增 preset 后会自动出现在页面列表中。

- [ ] **Step 4: 提交验证任务**

```bash
git add feature/test/src/main/java/com/blazepush/feature/test/repository/PresetTracks.kt feature/test/src/test/java/com/blazepush/feature/test/repository/TrackCatalogTest.kt feature/test/src/test/java/com/blazepush/feature/test/viewmodel/TestSessionViewModelTrackLapTest.kt
git commit -m "test: verify tfic preset lap debug flow"
```

---

## Self-Review

- Spec coverage: 计划覆盖了 spec 中要求的新增 `preset-tfic-lpcc`、保留 demo、忽略 `p房`、使用 replay samples 作为 referencePath、让当前测试页可选 TFIC 赛道。
- Placeholder scan: 计划中唯一需要在实施时补入的，是由 `.rcz` 计算并固化的门线几何常量与 replay path 点集；实施时必须把这些值写成最终代码，不保留注释占位。
- Type consistency: 全程使用 `preset-tfic-lpcc`、`TrackCatalog`、`LapRunConfig(trackId = ...)`、`TimingGateType.StartFinish/Sector` 等现有命名，与当前代码结构一致。
