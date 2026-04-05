# Replay Loop Playback Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 simulator 在 `REAL_TRACK_REPLAY` 场景下播完当前 replay 资产后自动从第一帧重新开始，持续循环播放。

**Architecture:** 保持现有 replay 运行时结构不变：`SimulatorViewModel` 仍然负责读取 asset、生成 `frames`、逐帧发射 BLE 数据。只在 `startReplayDataUpdate()` 的发射循环层增加可取消的外层循环，并补一条最小单测锁住“播完会回到首帧继续发射”的行为，不改 UI、planner 或 asset schema。

**Tech Stack:** Kotlin、JUnit4、kotlinx-coroutines、Android ViewModel、现有 replay asset loader / playback planner

---

## File Structure

- **Create:** `simulator/src/test/java/com/blazepush/simulator/viewmodel/SimulatorViewModelReplayLoopTest.kt`
  - 责任：验证 replay 播放在完成一轮后会重新从第一帧开始，而不是自然结束。
- **Modify:** `simulator/src/main/java/com/blazepush/simulator/viewmodel/SimulatorViewModel.kt`
  - 责任：把单次 `for (frame in frames)` 改成可取消的循环播放实现。
- **Optional Modify（仅当测试需要提取最小可测函数时）:** `simulator/src/main/java/com/blazepush/simulator/viewmodel/SimulatorViewModel.kt`
  - 责任：提取一个仅服务于现有 replay 发射流程的最小私有/内部帮助方法，避免在测试里依赖 Android `Context` 和 BLE 实例。

---

### Task 1: 为 replay 循环播放写红测

**Files:**
- Create: `simulator/src/test/java/com/blazepush/simulator/viewmodel/SimulatorViewModelReplayLoopTest.kt`
- Test: `simulator/src/test/java/com/blazepush/simulator/viewmodel/SimulatorViewModelReplayLoopTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.blazepush.simulator.viewmodel

import com.blazepush.simulator.data.replay.ReplayPlaybackFrame
import com.blazepush.simulator.data.replay.ReplaySample
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SimulatorViewModelReplayLoopTest {

    @Test
    fun `replay frames loop back to first frame after last frame`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val emitted = mutableListOf<Long>()
        val frames = listOf(
            ReplayPlaybackFrame(
                delayMillis = 0,
                sample = ReplaySample(
                    timestampMillis = 1000L,
                    latitude = 30.0,
                    longitude = 104.0,
                    speedKmh = 80.0,
                    bearingDegrees = 180.0,
                    satellites = 10,
                    fixType = 1,
                    hdop = 0.8,
                    altitudeMeters = 400.0,
                    altitudePrecisionMeters = 0.0
                )
            ),
            ReplayPlaybackFrame(
                delayMillis = 200,
                sample = ReplaySample(
                    timestampMillis = 1200L,
                    latitude = 30.1,
                    longitude = 104.1,
                    speedKmh = 82.0,
                    bearingDegrees = 182.0,
                    satellites = 10,
                    fixType = 1,
                    hdop = 0.8,
                    altitudeMeters = 401.0,
                    altitudePrecisionMeters = 0.0
                )
            )
        )

        val job = scope.launch {
            SimulatorViewModel.playReplayFramesForever(frames) { frame ->
                emitted += frame.sample.timestampMillis
            }
        }

        scope.advanceUntilIdle()
        advanceTimeBy(450)

        assertEquals(listOf(1000L, 1200L, 1000L, 1200L), emitted.take(4))

        job.cancelAndJoin()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :simulator:testDebugUnitTest --tests com.blazepush.simulator.viewmodel.SimulatorViewModelReplayLoopTest`
Expected: FAIL，因为 `playReplayFramesForever` 尚不存在。

- [ ] **Step 3: Write minimal implementation**

在 `SimulatorViewModel.kt` 中先增加一个最小内部帮助方法，专门承载循环逻辑：

```kotlin
internal companion object {
    suspend fun playReplayFramesForever(
        frames: List<ReplayPlaybackFrame>,
        emitFrame: suspend (ReplayPlaybackFrame) -> Unit
    ) {
        while (kotlin.coroutines.coroutineContext.isActive) {
            for (frame in frames) {
                kotlinx.coroutines.ensureActive()
                if (frame.delayMillis > 0) kotlinx.coroutines.delay(frame.delayMillis)
                emitFrame(frame)
            }
        }
    }
}
```

要求：
- 只做最小循环逻辑
- 不在这一步改 `startReplayDataUpdate()`
- 不处理空列表之外的额外特性；若要避免空列表死循环，直接在函数开头 `if (frames.isEmpty()) return`

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :simulator:testDebugUnitTest --tests com.blazepush.simulator.viewmodel.SimulatorViewModelReplayLoopTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add simulator/src/test/java/com/blazepush/simulator/viewmodel/SimulatorViewModelReplayLoopTest.kt simulator/src/main/java/com/blazepush/simulator/viewmodel/SimulatorViewModel.kt
git commit -m "test: cover replay loop playback"
```

---

### Task 2: 把 replay 发射接到循环播放实现

**Files:**
- Modify: `simulator/src/main/java/com/blazepush/simulator/viewmodel/SimulatorViewModel.kt`
- Test: `simulator/src/test/java/com/blazepush/simulator/viewmodel/SimulatorViewModelReplayLoopTest.kt`
- Test: `simulator/src/test/java/com/blazepush/simulator/data/GpsDataGeneratorReplayTest.kt`

- [ ] **Step 1: Write the failing integration-oriented test expectation**

在 `SimulatorViewModelReplayLoopTest.kt` 里新增一个更贴近取消语义的测试：

```kotlin
@Test
fun `playReplayFramesForever stops after coroutine cancellation`() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val scope = TestScope(dispatcher)
    val emitted = mutableListOf<Long>()
    val frames = listOf(
        ReplayPlaybackFrame(
            delayMillis = 0,
            sample = ReplaySample(
                timestampMillis = 1000L,
                latitude = 30.0,
                longitude = 104.0,
                speedKmh = 80.0,
                bearingDegrees = 180.0,
                satellites = 10,
                fixType = 1,
                hdop = 0.8,
                altitudeMeters = 400.0,
                altitudePrecisionMeters = 0.0
            )
        ),
        ReplayPlaybackFrame(
            delayMillis = 100,
            sample = ReplaySample(
                timestampMillis = 1100L,
                latitude = 30.1,
                longitude = 104.1,
                speedKmh = 81.0,
                bearingDegrees = 181.0,
                satellites = 10,
                fixType = 1,
                hdop = 0.8,
                altitudeMeters = 401.0,
                altitudePrecisionMeters = 0.0
            )
        )
    )

    val job = scope.launch {
        SimulatorViewModel.playReplayFramesForever(frames) { frame ->
            emitted += frame.sample.timestampMillis
        }
    }

    advanceTimeBy(120)
    job.cancelAndJoin()
    val sizeAfterCancel = emitted.size

    advanceTimeBy(300)

    assertEquals(sizeAfterCancel, emitted.size)
}
```

- [ ] **Step 2: Run test to verify it fails if cancellation is not handled correctly**

Run: `./gradlew :simulator:testDebugUnitTest --tests com.blazepush.simulator.viewmodel.SimulatorViewModelReplayLoopTest`
Expected: 若实现尚未正确检查取消状态，则 FAIL；若前一步 helper 已具备取消能力，可进入下一步并把这条视为先红后绿中的补充锁定。

- [ ] **Step 3: Write minimal implementation**

把 `startReplayDataUpdate()` 中的单次遍历：

```kotlin
for (frame in frames) {
    if (frame.delayMillis > 0) delay(frame.delayMillis)
    generator.applyReplaySample(frame.sample)
    Log.d(...)
    val mainData = generator.generateGpsMainData()
    val timeData = generator.generateGpsTimeData()
    manager.updateGpsData(mainData, timeData)
    _uiState.value = _uiState.value.copy(...)
}
```

替换为：

```kotlin
playReplayFramesForever(frames) { frame ->
    generator.applyReplaySample(frame.sample)
    Log.d(
        TAG,
        "Replay frame: ts=${frame.sample.timestampMillis}, lat=${frame.sample.latitude}, lon=${frame.sample.longitude}, speed=${frame.sample.speedKmh}, bearing=${frame.sample.bearingDegrees}, sats=${frame.sample.satellites}"
    )
    val mainData = generator.generateGpsMainData()
    val timeData = generator.generateGpsTimeData()
    manager.updateGpsData(mainData, timeData)
    _uiState.value = _uiState.value.copy(
        currentSpeed = frame.sample.speedKmh.toFloat(),
        currentLatitude = frame.sample.latitude,
        currentLongitude = frame.sample.longitude,
        satellites = frame.sample.satellites,
        frequency = 5
    )
}
```

同时保证 helper 本身包含：
- `if (frames.isEmpty()) return`
- 每轮/每帧前检查取消状态

- [ ] **Step 4: Run targeted tests to verify they pass**

Run: `./gradlew :simulator:testDebugUnitTest --tests com.blazepush.simulator.viewmodel.SimulatorViewModelReplayLoopTest`
Expected: PASS

Run: `./gradlew :simulator:testDebugUnitTest --tests com.blazepush.simulator.data.GpsDataGeneratorReplayTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add simulator/src/main/java/com/blazepush/simulator/viewmodel/SimulatorViewModel.kt simulator/src/test/java/com/blazepush/simulator/viewmodel/SimulatorViewModelReplayLoopTest.kt
git commit -m "feat: loop replay playback automatically"
```

---

### Task 3: 构建并做最小真机回环验证

**Files:**
- Modify: none
- Test: built APK + device launch verification

- [ ] **Step 1: Build simulator APK**

Run: `./gradlew :simulator:assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 2: Install to iFlytek device**

Run: `adb -s DP011011255100142 install -r "/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing/simulator/build/outputs/apk/debug/simulator-debug.apk"`
Expected: `Success`

- [ ] **Step 3: Launch app to verify startup**

Run: `adb -s DP011011255100142 shell am start -W -n com.blazepush.simulator/.MainActivity`
Expected:
- `Status: ok`
- `Activity: com.blazepush.simulator/.MainActivity`

- [ ] **Step 4: Manual replay loop check**

在讯飞设备上：
- 选择 `真实赛道回放 (天府 5Hz)`
- 点击 `开始广播`
- 等待一整轮 replay 接近结束
- 继续观察 `数据预览` 中的速度/经纬度

验收标准：
- replay 不会在最后一帧后停住
- 一轮结束后，速度/坐标会跳回首帧并继续更新
- 无需再次点击 `开始广播`

- [ ] **Step 5: Optional local log evidence**

如需补日志证据，运行：

```bash
adb -s DP011011255100142 logcat -d | grep "Replay frame:"
```

Expected:
- 日志持续输出，不只覆盖单轮末尾
- 在后段时间戳之后，再次出现首帧附近的时间戳（例如重新回到 `1773478969360`）

---

## Self-Review

- **Spec coverage:** 本计划覆盖了 spec 中的自动循环播放、保持现有 asset/planner 架构、不新增 UI、保持取消语义、补最小单测与构建/真机验证，没有遗漏 in-scope 要求。
- **Placeholder scan:** 无 TBD/TODO/“类似上一步”式占位；每个任务都包含明确文件、代码、命令和期望结果。
- **Type consistency:** 全文统一使用 `playReplayFramesForever`、`ReplayPlaybackFrame`、`ReplaySample`、`startReplayDataUpdate()`，与现有代码和本计划后续步骤保持一致。
