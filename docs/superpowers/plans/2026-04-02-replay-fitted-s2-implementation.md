# Replay Fitted S2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 replay / 集成测试链路增加 replay-only 的 fitted S2，使 `ReplayLapTimingIntegrationTest` 能在不污染正式赛道资产的前提下完成至少 1 圈。

**Architecture:** 保留 `RaceChronoReplayParser` 当前的时间锚点修复，只在 `feature:test` 的 test source set 中增加 `ReplayGateFitter`。该 fitter 读取 parser 输出的原始 gates 和 replay 样本，保留 `起点` 与 `s1`，仅对 `s2` 做平移拟合，然后由集成测试使用拟合后的 gates 构造 `Track`。

**Tech Stack:** Kotlin, JUnit4, Gradle, Android unit test (`:feature:test:testDebugUnitTest`)

---

## File Structure

- Create: `feature/test/src/test/java/com/blazepush/feature/test/usecase/ReplayGateFitter.kt`
  - replay/test 专用 gate 拟合器，只暴露 `fit(gates, replaySamples)`。
- Create: `feature/test/src/test/java/com/blazepush/feature/test/usecase/ReplayGateFitterTest.kt`
  - 覆盖 fitted S2 的核心约束：保留 `起点` / `s1`、只修改 `s2`、长度近似、位置位于 `s1` 与下一次 `起点` 之间。
- Modify: `feature/test/src/test/java/com/blazepush/feature/test/usecase/ReplayLapTimingIntegrationTest.kt`
  - 在 parse 原始 VBO gates 后接入 `ReplayGateFitter`。
- Reuse only: `simulator/src/main/java/com/blazepush/simulator/data/replay/RaceChronoReplayParser.kt`
  - 不改动，只作为原始 gate 来源。
- Reuse only: `feature/test/src/main/java/com/blazepush/feature/test/usecase/GateCrossingDetector.kt`
  - 复用现有 crossing 判定逻辑，不新增全局容差分支。

## Implementation Notes

- `ReplayGateFitter` 放在 `feature:test` 的 `src/test` 下，而不是 `src/main`，这样它天然只服务测试与回放验证链路。
- `ReplayGateFitter` 只识别名字为 `"s2"` / `"S2"` 的 split gate；没有 `s2` 时直接返回原 gates。
- 拟合方式采用“只平移、不旋转”：
  - 保留原始 `s2` 的线段向量和长度；
  - 在 replay 轨迹里定位 `s1` crossing 与下一次 `起点` crossing 的样本窗口；
  - 在窗口中扫描样本对，用原始 `s2` 的 `passDirection` 选择方向一致的段；
  - 以该段中点为目标中心，将原始 `s2` 平移过去，生成 fitted gate。
- `ReplayLapTimingIntegrationTest` 只验证“至少完成 1 圈”和 crossing 顺序中存在 `起点 -> s1 -> s2 -> 起点` 的 accepted 闭环；不要把拟合结果固化进正式资产文件。

### Task 1: 写出 ReplayGateFitter 的失败测试

**Files:**
- Create: `feature/test/src/test/java/com/blazepush/feature/test/usecase/ReplayGateFitterTest.kt`
- Reuse: `feature/test/src/main/java/com/blazepush/feature/test/usecase/GateCrossingDetector.kt`
- Reuse: `simulator/src/main/java/com/blazepush/simulator/data/replay/RaceChronoReplayModels.kt`

- [ ] **Step 1: 写第一个失败测试，锁定“保留起点和 s1，只拟合 s2”**

```kotlin
package com.blazepush.feature.test.usecase

import com.blazepush.simulator.data.replay.RaceChronoGateType
import com.blazepush.simulator.data.replay.ReplayGate
import com.blazepush.simulator.data.replay.ReplayGateLine
import com.blazepush.simulator.data.replay.ReplayGeoPoint
import com.blazepush.simulator.data.replay.ReplaySample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ReplayGateFitterTest {

    @Test
    fun `fit keeps start and s1 untouched and only moves s2`() {
        val gates = listOf(
            gate("起点", 0.0, 0.0, 0.0, 1.0, RaceChronoGateType.StartFinish),
            gate("s1", 2.0, 2.0, 2.0, 3.0, RaceChronoGateType.Split),
            gate("s2", 100.0, 100.0, 100.0, 101.0, RaceChronoGateType.Split)
        )
        val replay = listOf(
            sample(0L, -1.0, 0.5),
            sample(1_000L, 1.0, 0.5),
            sample(2_000L, 2.5, 1.5),
            sample(3_000L, 2.5, 3.5),
            sample(4_000L, 4.0, 2.0),
            sample(5_000L, 4.0, 4.0),
            sample(6_000L, -1.0, 0.5),
            sample(7_000L, 1.0, 0.5)
        )

        val fitted = ReplayGateFitter().fit(gates = gates, replaySamples = replay)

        assertEquals(gates.first(), fitted.first { it.name == "起点" })
        assertEquals(gates.first { it.name == "s1" }, fitted.first { it.name == "s1" })
        assertNotEquals(gates.first { it.name == "s2" }.line, fitted.first { it.name == "s2" }.line)
    }

    private fun gate(
        name: String,
        startLatitude: Double,
        startLongitude: Double,
        endLatitude: Double,
        endLongitude: Double,
        type: RaceChronoGateType
    ) = ReplayGate(
        type = type,
        name = name,
        line = ReplayGateLine(
            start = ReplayGeoPoint(startLatitude, startLongitude),
            end = ReplayGeoPoint(endLatitude, endLongitude)
        )
    )

    private fun sample(timestampMillis: Long, latitude: Double, longitude: Double) = ReplaySample(
        timestampMillis = timestampMillis,
        latitude = latitude,
        longitude = longitude,
        speedKmh = 120.0,
        bearingDegrees = 0.0,
        satellites = 10,
        fixType = 1,
        hdop = 0.8,
        altitudeMeters = 0.0,
        altitudePrecisionMeters = 0.0
    )
}
```

- [ ] **Step 2: 运行单测，确认当前失败**

Run: `./gradlew :feature:test:testDebugUnitTest --tests "com.blazepush.feature.test.usecase.ReplayGateFitterTest.fit keeps start and s1 untouched and only moves s2"`
Expected: FAIL，报 `Unresolved reference: ReplayGateFitter`

- [ ] **Step 3: 再写第二个失败测试，锁定“拟合后的 s2 必须落在 s1 与下一次起点之间，且长度保持同量级”**

```kotlin
@Test
fun `fit places s2 inside s1 to next start window and keeps original length`() {
    val gates = listOf(
        gate("起点", 0.0, 0.0, 0.0, 1.0, RaceChronoGateType.StartFinish),
        gate("s1", 2.0, 2.0, 2.0, 3.0, RaceChronoGateType.Split),
        gate("s2", 100.0, 100.0, 100.0, 101.0, RaceChronoGateType.Split)
    )
    val replay = listOf(
        sample(0L, -1.0, 0.5),
        sample(1_000L, 1.0, 0.5),
        sample(2_000L, 2.5, 1.5),
        sample(3_000L, 2.5, 3.5),
        sample(4_000L, 4.0, 2.0),
        sample(5_000L, 4.0, 4.0),
        sample(6_000L, -1.0, 0.5),
        sample(7_000L, 1.0, 0.5)
    )

    val fittedS2 = ReplayGateFitter().fit(gates = gates, replaySamples = replay)
        .first { it.name == "s2" }

    val midpointLatitude = (fittedS2.line.start.latitude + fittedS2.line.end.latitude) / 2.0
    val midpointLongitude = (fittedS2.line.start.longitude + fittedS2.line.end.longitude) / 2.0
    val originalLength = lineLength(gates.first { it.name == "s2" })
    val fittedLength = lineLength(fittedS2)

    assertEquals(4.0, midpointLatitude, 0.0001)
    assertEquals(3.0, midpointLongitude, 0.0001)
    assertEquals(originalLength, fittedLength, 0.0001)
}

private fun lineLength(gate: ReplayGate): Double {
    val dx = gate.line.end.latitude - gate.line.start.latitude
    val dy = gate.line.end.longitude - gate.line.start.longitude
    return kotlin.math.sqrt((dx * dx) + (dy * dy))
}
```

- [ ] **Step 4: 再次运行单测，确认仍然失败在实现缺失**

Run: `./gradlew :feature:test:testDebugUnitTest --tests "com.blazepush.feature.test.usecase.ReplayGateFitterTest"`
Expected: FAIL，报 `Unresolved reference: ReplayGateFitter`

- [ ] **Step 5: 提交测试骨架**

```bash
git add feature/test/src/test/java/com/blazepush/feature/test/usecase/ReplayGateFitterTest.kt
git commit -m "test: cover replay-only fitted s2"
```

### Task 2: 实现 ReplayGateFitter 的最小可用版本

**Files:**
- Create: `feature/test/src/test/java/com/blazepush/feature/test/usecase/ReplayGateFitter.kt`
- Test: `feature/test/src/test/java/com/blazepush/feature/test/usecase/ReplayGateFitterTest.kt`

- [ ] **Step 1: 先写最小类骨架，让测试能编译**

```kotlin
package com.blazepush.feature.test.usecase

import com.blazepush.simulator.data.replay.ReplayGate
import com.blazepush.simulator.data.replay.ReplaySample

class ReplayGateFitter {
    fun fit(gates: List<ReplayGate>, replaySamples: List<ReplaySample>): List<ReplayGate> {
        return gates
    }
}
```

- [ ] **Step 2: 运行测试，确认从“编译失败”推进到“断言失败”**

Run: `./gradlew :feature:test:testDebugUnitTest --tests "com.blazepush.feature.test.usecase.ReplayGateFitterTest"`
Expected: FAIL，断言 `s2` 没有被移动

- [ ] **Step 3: 实现最小拟合逻辑，只平移 s2 到候选窗口最佳段的中点**

```kotlin
package com.blazepush.feature.test.usecase

import com.blazepush.simulator.data.replay.RaceChronoGateType
import com.blazepush.simulator.data.replay.ReplayGate
import com.blazepush.simulator.data.replay.ReplayGateLine
import com.blazepush.simulator.data.replay.ReplayGeoPoint
import com.blazepush.simulator.data.replay.ReplaySample

class ReplayGateFitter {

    fun fit(gates: List<ReplayGate>, replaySamples: List<ReplaySample>): List<ReplayGate> {
        val s2 = gates.firstOrNull { it.name.equals("s2", ignoreCase = true) } ?: return gates
        val start = gates.firstOrNull { it.type == RaceChronoGateType.StartFinish } ?: return gates
        val s1 = gates.firstOrNull { it.name.equals("s1", ignoreCase = true) } ?: return gates
        val candidateWindow = replaySamples.zipWithNext()
            .dropWhile { (_, current) -> current.latitude < s1.line.start.latitude }
            .takeWhile { (_, current) -> current.latitude > start.line.start.latitude }
        val bestSegment = candidateWindow.maxByOrNull { (previous, current) ->
            scoreSegment(previous, current)
        } ?: return gates
        val fittedS2 = s2.translateTo(
            targetLatitude = (bestSegment.first.latitude + bestSegment.second.latitude) / 2.0,
            targetLongitude = (bestSegment.first.longitude + bestSegment.second.longitude) / 2.0
        )
        return gates.map { gate ->
            if (gate.name.equals("s2", ignoreCase = true)) fittedS2 else gate
        }
    }

    private fun scoreSegment(previous: ReplaySample, current: ReplaySample): Double {
        val dx = current.latitude - previous.latitude
        val dy = current.longitude - previous.longitude
        return kotlin.math.abs(dx) + kotlin.math.abs(dy)
    }

    private fun ReplayGate.translateTo(targetLatitude: Double, targetLongitude: Double): ReplayGate {
        val currentCenterLatitude = (line.start.latitude + line.end.latitude) / 2.0
        val currentCenterLongitude = (line.start.longitude + line.end.longitude) / 2.0
        val deltaLatitude = targetLatitude - currentCenterLatitude
        val deltaLongitude = targetLongitude - currentCenterLongitude
        return copy(
            line = ReplayGateLine(
                start = ReplayGeoPoint(
                    latitude = line.start.latitude + deltaLatitude,
                    longitude = line.start.longitude + deltaLongitude
                ),
                end = ReplayGeoPoint(
                    latitude = line.end.latitude + deltaLatitude,
                    longitude = line.end.longitude + deltaLongitude
                )
            )
        )
    }
}
```

- [ ] **Step 4: 收紧实现，使候选窗口以 crossing 顺序而不是裸坐标阈值定义**

```kotlin
fun fit(gates: List<ReplayGate>, replaySamples: List<ReplaySample>): List<ReplayGate> {
    val s2 = gates.firstOrNull { it.name.equals("s2", ignoreCase = true) } ?: return gates
    val start = gates.firstOrNull { it.type == RaceChronoGateType.StartFinish } ?: return gates
    val s1 = gates.firstOrNull { it.name.equals("s1", ignoreCase = true) } ?: return gates

    val startCrossingIndex = findFirstCrossingIndex(replaySamples, start) ?: return gates
    val s1CrossingIndex = findNextCrossingIndex(replaySamples, s1, startCrossingIndex + 1) ?: return gates
    val nextStartCrossingIndex = findNextCrossingIndex(replaySamples, start, s1CrossingIndex + 1) ?: return gates
    val bestSegment = replaySamples
        .subList(s1CrossingIndex, nextStartCrossingIndex + 1)
        .zipWithNext()
        .maxByOrNull { (previous, current) -> scoreSegment(previous, current) }
        ?: return gates

    val fittedS2 = s2.translateTo(
        targetLatitude = (bestSegment.first.latitude + bestSegment.second.latitude) / 2.0,
        targetLongitude = (bestSegment.first.longitude + bestSegment.second.longitude) / 2.0
    )
    return gates.map { gate -> if (gate.name.equals("s2", ignoreCase = true)) fittedS2 else gate }
}

private fun findFirstCrossingIndex(samples: List<ReplaySample>, gate: ReplayGate): Int? =
    samples.zipWithNext().indexOfFirst { (previous, current) ->
        val detector = ReplayGateCrossingSupport.detector
        detector.crosses(previous, current, gate)
    }.takeIf { it >= 0 }

private fun findNextCrossingIndex(samples: List<ReplaySample>, gate: ReplayGate, startIndex: Int): Int? =
    samples.drop(startIndex).zipWithNext().indexOfFirst { (previous, current) ->
        val detector = ReplayGateCrossingSupport.detector
        detector.crosses(previous, current, gate)
    }.takeIf { it >= 0 }?.plus(startIndex)
```

- [ ] **Step 5: 用一个最小内部适配器复用现有 crossing 逻辑，不改 production 代码**

```kotlin
private object ReplayGateCrossingSupport {
    val detector = GateCrossingDetector()

    fun GateCrossingDetector.crosses(
        previous: ReplaySample,
        current: ReplaySample,
        gate: ReplayGate
    ): Boolean {
        val detection = detect(
            previous = previous.toGpsSample(),
            current = current.toGpsSample(),
            gate = gate.toTimingGate()
        )
        return detection.accepted
    }

    private fun ReplaySample.toGpsSample() = com.blazepush.feature.test.model.laptiming.GpsSample(
        timestampMillis = timestampMillis,
        latitude = latitude,
        longitude = longitude,
        speedKmh = speedKmh,
        bearingDegrees = bearingDegrees,
        altitudeMeters = altitudeMeters,
        accuracyMeters = hdop
    )

    private fun ReplayGate.toTimingGate() = com.blazepush.feature.test.model.track.TimingGate(
        id = name,
        name = name,
        type = if (type == RaceChronoGateType.StartFinish) {
            com.blazepush.feature.test.model.track.TimingGateType.StartFinish
        } else {
            com.blazepush.feature.test.model.track.TimingGateType.Sector
        },
        line = com.blazepush.feature.test.model.track.GeoLine(
            start = com.blazepush.feature.test.model.track.GeoPoint(line.start.latitude, line.start.longitude),
            end = com.blazepush.feature.test.model.track.GeoPoint(line.end.latitude, line.end.longitude)
        ),
        passDirection = when (name.lowercase()) {
            "起点", "start/finish" -> com.blazepush.feature.test.model.track.GeoVector(1.0, 0.0)
            "s1" -> com.blazepush.feature.test.model.track.GeoVector(0.0, 1.0)
            "s2" -> com.blazepush.feature.test.model.track.GeoVector(-1.0, 0.0)
            else -> com.blazepush.feature.test.model.track.GeoVector(
                line.end.latitude - line.start.latitude,
                line.end.longitude - line.start.longitude
            )
        },
        sequenceIndex = if (type == RaceChronoGateType.StartFinish) 0 else 1,
        minDirectionalSpeedMps = null
    )
}
```

- [ ] **Step 6: 运行 GateFitter 测试，确认通过**

Run: `./gradlew :feature:test:testDebugUnitTest --tests "com.blazepush.feature.test.usecase.ReplayGateFitterTest"`
Expected: PASS

- [ ] **Step 7: 提交 fitter 实现**

```bash
git add \
  feature/test/src/test/java/com/blazepush/feature/test/usecase/ReplayGateFitter.kt \
  feature/test/src/test/java/com/blazepush/feature/test/usecase/ReplayGateFitterTest.kt
git commit -m "feat: add replay-only s2 gate fitter"
```

### Task 3: 接入集成测试并验证 completed lap

**Files:**
- Modify: `feature/test/src/test/java/com/blazepush/feature/test/usecase/ReplayLapTimingIntegrationTest.kt`
- Test: `feature/test/src/test/java/com/blazepush/feature/test/usecase/ReplayLapTimingIntegrationTest.kt`

- [ ] **Step 1: 在集成测试里接入 ReplayGateFitter**

```kotlin
@Test
fun `replay session and vbo gates can drive one completed lap`() {
    val parser = RaceChronoReplayParser()
    val replay = ReplayAssetLoader().loadReplayJson(replayJson())
    val rawGates = parser.parseVboGates(trackVbo(), replay.samples.first())
    val gates = ReplayGateFitter().fit(
        gates = rawGates,
        replaySamples = replay.samples
    )
    val track = gates.toTrack(referenceSamples = replay.samples)
    val engine = LapTimingEngine()

    var session = LapSession(
        sessionId = "replay-session",
        trackId = track.id,
        status = LapSessionStatus.Ready
    )

    replay.samples.zipWithNext().forEach { (previous, current) ->
        session = engine.processSample(
            session = session,
            track = track,
            previousSample = previous.toGpsSample(),
            currentSample = current.toGpsSample()
        )
    }

    assertEquals(1, session.completedLaps.size)
}
```

- [ ] **Step 2: 增加顺序断言，防止靠脏命中过关**

```kotlin
val acceptedOrder = session.crossingEvents
    .filter { it.accepted }
    .map { it.gateId }

assertEquals(1, session.completedLaps.size)
assertEquals(listOf("起点", "s1", "s2", "起点"), acceptedOrder.take(4))
assertEquals(1, session.completedLaps.first().lapIndex)
assertEquals(listOf(4_000L, 5_000L), session.completedLaps.first().sectorTimes)
assertEquals(14_000L, session.completedLaps.first().durationMillis)
```

- [ ] **Step 3: 运行集成测试，确认先红后绿；如果红，先看 crossing 顺序而不是改全局容差**

Run: `./gradlew :feature:test:testDebugUnitTest --tests "com.blazepush.feature.test.usecase.ReplayLapTimingIntegrationTest"`
Expected: PASS，至少 1 个 completed lap

- [ ] **Step 4: 跑最小回归集合，确认 parser 修复与 fitter 接入没有互相破坏**

Run: `./gradlew :simulator:testDebugUnitTest --tests "com.blazepush.simulator.data.replay.RaceChronoReplayParserTest" :feature:test:testDebugUnitTest --tests "com.blazepush.feature.test.usecase.ReplayGateFitterTest" --tests "com.blazepush.feature.test.usecase.ReplayLapTimingIntegrationTest"`
Expected: PASS，parser regression、gate fitter、integration test 全绿

- [ ] **Step 5: 提交集成接线与验证结果**

```bash
git add feature/test/src/test/java/com/blazepush/feature/test/usecase/ReplayLapTimingIntegrationTest.kt
git commit -m "test: verify replay lap timing with fitted s2"
```

## Self-Review

### Spec coverage

- “保留 parser 时间锚点修复、不再修改 parser” → File Structure / Task 3 已明确只复用 `RaceChronoReplayParser.kt`。
- “Replay-only，不污染正式 TrackCatalog” → Implementation Notes 已限定 `ReplayGateFitter` 放在 `feature:test/src/test`。
- “保留起点与 s1，只拟合 s2” → Task 1 第一个测试覆盖；Task 2 的 `fit()` 只替换 `s2`。
- “保留方向/长度级别，不靠全局放宽容差” → Task 1 第二个测试与 Task 2 的平移实现覆盖；Task 3 说明不改全局容差。
- “至少完成 1 圈并检查顺序” → Task 3 覆盖。

### Placeholder scan

- 计划中没有 `TODO` / `TBD` / “写一些测试” 这类占位语句。
- 每个代码步骤都给了具体 Kotlin 代码块。
- 每个运行步骤都给了准确命令和预期结果。

### Type consistency

- `ReplayGateFitter.fit(gates: List<ReplayGate>, replaySamples: List<ReplaySample>): List<ReplayGate>` 在测试、实现、集成测试中保持一致。
- `ReplayGate` / `ReplaySample` / `GateCrossingDetector` 的调用方式与现有代码一致。
- `ReplayLapTimingIntegrationTest` 仍使用现有 `toTrack()` / `toGpsSample()` 辅助方法，不引入额外未定义接口。
