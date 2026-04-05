# Replay Temporary Gate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 replay 测试域内用 `replay-derived temporary gate` 替换异常 `s2`，让真实 replay 集成测试完成一圈，同时不修改正式赛道资产和 `LapTimingEngine` 主逻辑。

**Architecture:** 保持 `RaceChronoReplayParser`、`GateCrossingDetector`、`LapTimingEngine` 现有职责不变，仅在测试代码中新增 `ReplayTemporaryGateBuilder`。它先用可信 `Start/Finish` 与 `s1` 定位 `s2` 搜索窗口，再基于 replay 局部轨迹派生 temporary gate；若证据不足则直接回退原始 gates，让测试显式暴露资产问题。

**Tech Stack:** Kotlin, JUnit4, Gradle, existing `feature:test` unit tests, RaceChrono replay assets

---

## 文件结构

### 修改文件
1. `feature/test/src/test/java/com/blazepush/feature/test/usecase/ReplayLapTimingIntegrationTest.kt` - 将 replay 集成测试接线从 `ReplayGateFitter` 改为 `ReplayTemporaryGateBuilder`，并保留 accepted order / lap 完成断言。
2. `docs/superpowers/plans/2026-04-01-replay-lap-progress-log.md` - 记录本轮从 fitted S2 过渡到 replay temporary gate 的验证结果与停靠点。

### 新建文件
1. `feature/test/src/test/java/com/blazepush/feature/test/usecase/ReplayTemporaryGateBuilder.kt` - 测试域 builder，本轮只负责在 replay window 内派生 temporary `s2`。
2. `feature/test/src/test/java/com/blazepush/feature/test/usecase/ReplayTemporaryGateBuilderTest.kt` - builder 的 contract 与回退测试。
3. `feature/test/src/test/java/com/blazepush/feature/test/usecase/ReplayTemporaryGateGeometryTest.kt` - 验证 temporary gate 的几何合理性与 accepted crossing。

### 保持不变
1. `feature/test/src/main/java/com/blazepush/feature/test/usecase/GateCrossingDetector.kt` - 不放宽全局 crossing 判定。
2. `feature/test/src/main/java/com/blazepush/feature/test/usecase/LapTimingEngine.kt` - 不修改状态机主逻辑。
3. 正式 `TrackCatalog` / 赛道资产 - 不进入本轮实现范围。

### 参考文件
1. `docs/superpowers/specs/2026-04-02-replay-temporary-gate-design.md` - 本轮设计说明。
2. `feature/test/src/test/java/com/blazepush/feature/test/usecase/ReplayLapTimingIntegrationTest.kt` - 真实 replay 集成入口。
3. `feature/test/src/main/java/com/blazepush/feature/test/usecase/GateCrossingDetector.kt` - crossing 判定规则。
4. `feature/test/src/main/java/com/blazepush/feature/test/usecase/LapTimingEngine.kt` - 圈速状态机行为约束。

### 约束
- 只在测试代码中实现 replay temporary gate，不进入生产代码。
- 只替换异常 `s2`，`Start/Finish` 与 `s1` 必须保持原始 gate。
- 找不到可信 window / anchor 时直接回退原始 gates。
- 不允许通过放宽 detector 容差来让测试通过。
- 每个任务遵守 TDD：先写失败测试，再最小实现，再运行验证。

---

## Task 1: 建立 ReplayTemporaryGateBuilder 的 contract 与回退边界

**Files:**
- Create: `feature/test/src/test/java/com/blazepush/feature/test/usecase/ReplayTemporaryGateBuilderTest.kt`
- Create: `feature/test/src/test/java/com/blazepush/feature/test/usecase/ReplayTemporaryGateBuilder.kt`

- [ ] **Step 1: 写失败测试，锁定“只替换 s2，其他 gate 保持不变” contract**

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

class ReplayTemporaryGateBuilderTest {

    @Test
    fun `build replaces only s2 and keeps start and s1 untouched`() {
        val gates = listOf(
            gate("起点", 0.0, 0.0, 0.0, 1.0, RaceChronoGateType.StartFinish),
            gate("s1", 2.0, 2.0, 2.0, 3.0, RaceChronoGateType.Split),
            gate("s2", 100.0, 100.0, 100.0, 101.0, RaceChronoGateType.Split)
        )

        val built = ReplayTemporaryGateBuilder().build(gates = gates, replaySamples = replaySamples())

        assertEquals(gates.first { it.name == "起点" }, built.first { it.name == "起点" })
        assertEquals(gates.first { it.name == "s1" }, built.first { it.name == "s1" })
        assertNotEquals(gates.first { it.name == "s2" }.line, built.first { it.name == "s2" }.line)
    }

    private fun replaySamples() = listOf(
        sample(0L, -1.0, 0.5),
        sample(1_000L, 1.0, 0.5),
        sample(2_000L, 1.5, 1.5),
        sample(3_000L, 2.5, 2.5),
        sample(4_000L, 3.0, 3.0),
        sample(5_000L, 5.0, 3.0),
        sample(6_000L, 3.0, 3.0),
        sample(7_000L, -1.0, 0.5),
        sample(8_000L, 1.0, 0.5)
    )

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

- [ ] **Step 2: 运行 builder contract 测试，确认失败**

Run: `./gradlew :feature:test:testDebugUnitTest --tests "com.blazepush.feature.test.usecase.ReplayTemporaryGateBuilderTest.build replaces only s2 and keeps start and s1 untouched"`
Expected: FAIL，提示 `ReplayTemporaryGateBuilder` 未定义

- [ ] **Step 3: 增加失败测试，锁定“无可信 window 时回退原始 gates”**

```kotlin
@Test
fun `build falls back to original gates when no valid window can be found`() {
    val gates = listOf(
        gate("起点", 0.0, 0.0, 0.0, 1.0, RaceChronoGateType.StartFinish),
        gate("s1", 2.0, 2.0, 2.0, 3.0, RaceChronoGateType.Split),
        gate("s2", 100.0, 100.0, 100.0, 101.0, RaceChronoGateType.Split)
    )
    val replay = listOf(
        sample(0L, -1.0, 0.5),
        sample(1_000L, 1.0, 0.5),
        sample(2_000L, 1.5, 1.5)
    )

    val built = ReplayTemporaryGateBuilder().build(gates = gates, replaySamples = replay)

    assertEquals(gates, built)
}
```

- [ ] **Step 4: 再次运行 builder 测试，确认失败**

Run: `./gradlew :feature:test:testDebugUnitTest --tests "com.blazepush.feature.test.usecase.ReplayTemporaryGateBuilderTest"`
Expected: FAIL，提示 `ReplayTemporaryGateBuilder.build(...)` 尚未实现

- [ ] **Step 5: 写 `ReplayTemporaryGateBuilder` 最小骨架与回退逻辑**

```kotlin
package com.blazepush.feature.test.usecase

import com.blazepush.feature.test.model.laptiming.GpsSample
import com.blazepush.feature.test.model.track.GeoLine
import com.blazepush.feature.test.model.track.GeoPoint
import com.blazepush.feature.test.model.track.GeoVector
import com.blazepush.feature.test.model.track.TimingGate
import com.blazepush.feature.test.model.track.TimingGateType
import com.blazepush.simulator.data.replay.RaceChronoGateType
import com.blazepush.simulator.data.replay.ReplayGate
import com.blazepush.simulator.data.replay.ReplayGateLine
import com.blazepush.simulator.data.replay.ReplayGeoPoint
import com.blazepush.simulator.data.replay.ReplaySample
import kotlin.math.hypot

class ReplayTemporaryGateBuilder(
    private val detector: GateCrossingDetector = GateCrossingDetector()
) {

    fun build(gates: List<ReplayGate>, replaySamples: List<ReplaySample>): List<ReplayGate> {
        val startGate = gates.firstOrNull { it.type == RaceChronoGateType.StartFinish } ?: return gates
        val s1Gate = gates.firstOrNull { it.name.equals("s1", ignoreCase = true) } ?: return gates
        val s2Gate = gates.firstOrNull { it.name.equals("s2", ignoreCase = true) } ?: return gates

        val startIndex = findAcceptedCrossingStartIndex(replaySamples, startGate, 0) ?: return gates
        val s1Index = findAcceptedCrossingStartIndex(replaySamples, s1Gate, startIndex + 1) ?: return gates
        val nextStartIndex = findAcceptedCrossingStartIndex(replaySamples, startGate, s1Index + 1) ?: return gates
        val candidate = selectAnchor(replaySamples, s1Index + 1, nextStartIndex) ?: return gates

        val temporaryS2 = s2Gate.buildTemporaryFrom(candidate.first, candidate.second)
        return gates.map { gate -> if (gate.name.equals("s2", ignoreCase = true)) temporaryS2 else gate }
    }

    private fun findAcceptedCrossingStartIndex(
        samples: List<ReplaySample>,
        gate: ReplayGate,
        startIndex: Int
    ): Int? = samples
        .drop(startIndex)
        .zipWithNext()
        .indexOfFirst { (previous, current) ->
            detector.detect(previous.toGpsSample(), current.toGpsSample(), gate.toTimingGate()).accepted
        }
        .takeIf { it >= 0 }
        ?.plus(startIndex)

    private fun selectAnchor(
        samples: List<ReplaySample>,
        fromIndex: Int,
        toIndex: Int
    ): Pair<ReplaySample, ReplaySample>? {
        val pairs = samples.subList(fromIndex, toIndex + 1).zipWithNext()
        if (pairs.isEmpty()) return null
        return pairs.maxByOrNull { (previous, current) ->
            hypot(current.latitude - previous.latitude, current.longitude - previous.longitude)
        }
    }

    private fun ReplayGate.buildTemporaryFrom(previous: ReplaySample, current: ReplaySample): ReplayGate {
        val centerLatitude = (previous.latitude + current.latitude) / 2.0
        val centerLongitude = (previous.longitude + current.longitude) / 2.0
        val movementLatitude = current.latitude - previous.latitude
        val movementLongitude = current.longitude - previous.longitude
        val movementLength = hypot(movementLatitude, movementLongitude)
        if (movementLength == 0.0) return this

        val halfWidth = lineLength() / 2.0
        val normalLatitude = -movementLongitude / movementLength
        val normalLongitude = movementLatitude / movementLength

        return copy(
            line = ReplayGateLine(
                start = ReplayGeoPoint(
                    latitude = centerLatitude + (normalLatitude * halfWidth),
                    longitude = centerLongitude + (normalLongitude * halfWidth)
                ),
                end = ReplayGeoPoint(
                    latitude = centerLatitude - (normalLatitude * halfWidth),
                    longitude = centerLongitude - (normalLongitude * halfWidth)
                )
            )
        )
    }

    private fun ReplayGate.lineLength(): Double = hypot(
        line.end.latitude - line.start.latitude,
        line.end.longitude - line.start.longitude
    )

    private fun ReplayGate.toTimingGate(): TimingGate = TimingGate(
        id = name,
        name = name,
        type = if (type == RaceChronoGateType.StartFinish) TimingGateType.StartFinish else TimingGateType.Sector,
        line = GeoLine(
            start = GeoPoint(line.start.latitude, line.start.longitude),
            end = GeoPoint(line.end.latitude, line.end.longitude)
        ),
        passDirection = passDirection(),
        sequenceIndex = if (type == RaceChronoGateType.StartFinish) 0 else 1,
        minDirectionalSpeedMps = null
    )

    private fun ReplayGate.passDirection(): GeoVector = when (name.lowercase()) {
        "start/finish", "起点" -> GeoVector(x = 1.0, y = 0.0)
        "s1" -> GeoVector(x = 0.0, y = 1.0)
        "s2" -> GeoVector(x = -1.0, y = 0.0)
        else -> GeoVector(
            x = line.end.latitude - line.start.latitude,
            y = line.end.longitude - line.start.longitude
        )
    }

    private fun ReplaySample.toGpsSample(): GpsSample = GpsSample(
        timestampMillis = timestampMillis,
        latitude = latitude,
        longitude = longitude,
        speedKmh = speedKmh,
        bearingDegrees = bearingDegrees,
        altitudeMeters = altitudeMeters,
        accuracyMeters = hdop
    )
}
```

- [ ] **Step 6: 运行 builder contract 测试，确认通过**

Run: `./gradlew :feature:test:testDebugUnitTest --tests "com.blazepush.feature.test.usecase.ReplayTemporaryGateBuilderTest"`
Expected: PASS

- [ ] **Step 7: 检查 builder 仍只存在于测试域**

Check:
```bash
git diff -- feature/test/src/test/java/com/blazepush/feature/test/usecase/ReplayTemporaryGateBuilder.kt feature/test/src/test/java/com/blazepush/feature/test/usecase/ReplayTemporaryGateBuilderTest.kt
```
Expected: diff 仅落在 `src/test/java`

- [ ] **Step 8: Commit**

```bash
git add feature/test/src/test/java/com/blazepush/feature/test/usecase/ReplayTemporaryGateBuilder.kt feature/test/src/test/java/com/blazepush/feature/test/usecase/ReplayTemporaryGateBuilderTest.kt
git commit -m "test: add replay temporary gate builder contract"
```

---

## Task 2: 锁定 temporary gate 的几何合理性与 accepted crossing

**Files:**
- Create: `feature/test/src/test/java/com/blazepush/feature/test/usecase/ReplayTemporaryGateGeometryTest.kt`
- Modify: `feature/test/src/test/java/com/blazepush/feature/test/usecase/ReplayTemporaryGateBuilder.kt`

- [ ] **Step 1: 写失败测试，锁定“temporary gate 落在 s1 到 next start window 内”**

```kotlin
package com.blazepush.feature.test.usecase

import com.blazepush.simulator.data.replay.RaceChronoGateType
import com.blazepush.simulator.data.replay.ReplayGate
import com.blazepush.simulator.data.replay.ReplayGateLine
import com.blazepush.simulator.data.replay.ReplayGeoPoint
import com.blazepush.simulator.data.replay.ReplaySample
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class ReplayTemporaryGateGeometryTest {

    @Test
    fun `build places s2 midpoint close to replay path inside s1 to next start window`() {
        val gates = listOf(
            gate("起点", 0.0, 0.0, 0.0, 1.0, RaceChronoGateType.StartFinish),
            gate("s1", 2.0, 2.0, 2.0, 3.0, RaceChronoGateType.Split),
            gate("s2", 100.0, 100.0, 100.0, 101.0, RaceChronoGateType.Split)
        )
        val replay = replaySamples()

        val builtS2 = ReplayTemporaryGateBuilder().build(gates = gates, replaySamples = replay)
            .first { it.name == "s2" }

        val midpointLatitude = (builtS2.line.start.latitude + builtS2.line.end.latitude) / 2.0
        val midpointLongitude = (builtS2.line.start.longitude + builtS2.line.end.longitude) / 2.0
        val window = replay.subList(4, 8)
        val minDistance = window.minOf { sample ->
            hypot(midpointLatitude - sample.latitude, midpointLongitude - sample.longitude)
        }

        assertTrue("midpoint should be close to replay window", minDistance < 0.2)
    }

    private fun replaySamples() = listOf(
        sample(0L, -1.0, 0.5),
        sample(1_000L, 1.0, 0.5),
        sample(2_000L, 1.5, 1.5),
        sample(3_000L, 2.5, 2.5),
        sample(4_000L, 3.0, 3.0),
        sample(5_000L, 5.0, 3.0),
        sample(6_000L, 3.0, 3.0),
        sample(7_000L, -1.0, 0.5),
        sample(8_000L, 1.0, 0.5)
    )

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

- [ ] **Step 2: 运行几何测试，确认失败**

Run: `./gradlew :feature:test:testDebugUnitTest --tests "com.blazepush.feature.test.usecase.ReplayTemporaryGateGeometryTest.build places s2 midpoint close to replay path inside s1 to next start window"`
Expected: FAIL，当前 builder 尚未明确保证几何约束

- [ ] **Step 3: 写失败测试，锁定“temporary gate 至少触发一次 accepted crossing”**

```kotlin
@Test
fun `build creates temporary s2 that accepts at least one crossing within replay window`() {
    val gates = listOf(
        gate("起点", 0.0, 0.0, 0.0, 1.0, RaceChronoGateType.StartFinish),
        gate("s1", 2.0, 2.0, 2.0, 3.0, RaceChronoGateType.Split),
        gate("s2", 100.0, 100.0, 100.0, 101.0, RaceChronoGateType.Split)
    )
    val replay = replaySamples()
    val builtS2 = ReplayTemporaryGateBuilder().build(gates = gates, replaySamples = replay)
        .first { it.name == "s2" }
    val detector = GateCrossingDetector()

    val accepted = replay.subList(4, 8)
        .zipWithNext()
        .any { (previous, current) ->
            detector.detect(previous.toGpsSample(), current.toGpsSample(), builtS2.toTimingGate()).accepted
        }

    assertTrue("temporary s2 should accept at least one crossing", accepted)
}
```

- [ ] **Step 4: 在几何测试文件补上最小转换函数**

```kotlin
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
    type = if (type == RaceChronoGateType.StartFinish) com.blazepush.feature.test.model.track.TimingGateType.StartFinish else com.blazepush.feature.test.model.track.TimingGateType.Sector,
    line = com.blazepush.feature.test.model.track.GeoLine(
        start = com.blazepush.feature.test.model.track.GeoPoint(line.start.latitude, line.start.longitude),
        end = com.blazepush.feature.test.model.track.GeoPoint(line.end.latitude, line.end.longitude)
    ),
    passDirection = com.blazepush.feature.test.model.track.GeoVector(
        x = line.start.longitude - line.end.longitude,
        y = line.end.latitude - line.start.latitude
    ),
    sequenceIndex = 2,
    minDirectionalSpeedMps = null
)
```

- [ ] **Step 5: 最小调整 builder，优先窗口中部且过滤零位移 anchor**

```kotlin
private data class AnchorCandidate(
    val previous: ReplaySample,
    val current: ReplaySample,
    val score: Double
)

private fun selectAnchor(
    samples: List<ReplaySample>,
    fromIndex: Int,
    toIndex: Int
): Pair<ReplaySample, ReplaySample>? {
    val pairs = samples.subList(fromIndex, toIndex + 1).zipWithNext()
    val centerIndex = pairs.lastIndex / 2.0
    return pairs
        .mapIndexedNotNull { index, (previous, current) ->
            val displacement = hypot(current.latitude - previous.latitude, current.longitude - previous.longitude)
            if (displacement == 0.0) return@mapIndexedNotNull null
            AnchorCandidate(
                previous = previous,
                current = current,
                score = displacement - kotlin.math.abs(index - centerIndex)
            )
        }
        .maxByOrNull { it.score }
        ?.let { it.previous to it.current }
}
```

- [ ] **Step 6: 运行 builder 与几何测试，确认通过**

Run: `./gradlew :feature:test:testDebugUnitTest --tests "com.blazepush.feature.test.usecase.ReplayTemporaryGateBuilderTest" --tests "com.blazepush.feature.test.usecase.ReplayTemporaryGateGeometryTest"`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add feature/test/src/test/java/com/blazepush/feature/test/usecase/ReplayTemporaryGateBuilder.kt feature/test/src/test/java/com/blazepush/feature/test/usecase/ReplayTemporaryGateBuilderTest.kt feature/test/src/test/java/com/blazepush/feature/test/usecase/ReplayTemporaryGateGeometryTest.kt
git commit -m "test: verify replay temporary gate geometry"
```

---

## Task 3: 将真实 replay 集成测试切到 ReplayTemporaryGateBuilder

**Files:**
- Modify: `feature/test/src/test/java/com/blazepush/feature/test/usecase/ReplayLapTimingIntegrationTest.kt`
- Modify: `feature/test/src/test/java/com/blazepush/feature/test/usecase/ReplayTemporaryGateBuilder.kt`

- [ ] **Step 1: 先把集成测试接线改成新的 builder**

```kotlin
@Test
fun `replay session and temporary gates can drive one completed lap`() {
    val parser = RaceChronoReplayParser()
    val replay = ReplayAssetLoader().loadReplayJson(replayJson())
    val rawGates = parser.parseVboGates(trackVbo(), replay.samples.first())
    val gates = ReplayTemporaryGateBuilder().build(gates = rawGates, replaySamples = replay.samples)
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

    val acceptedOrder = session.crossingEvents
        .filter { it.accepted }
        .map { it.gateId }

    assertEquals(1, session.completedLaps.size)
    assertEquals(listOf("起点", "s1", "s2", "起点"), acceptedOrder.take(4))
    assertEquals(1, session.completedLaps.first().lapIndex)
    assertEquals(listOf(4_000L, 5_000L), session.completedLaps.first().sectorTimes)
    assertEquals(14_000L, session.completedLaps.first().durationMillis)
}
```

- [ ] **Step 2: 运行 replay 集成测试，确认失败并记录失败信息**

Run: `./gradlew :feature:test:testDebugUnitTest --tests "com.blazepush.feature.test.usecase.ReplayLapTimingIntegrationTest.replay session and temporary gates can drive one completed lap"`
Expected: 初次 FAIL；记录 accepted order / crossingEvents 输出，确认仍卡在 temporary gate 质量或 passDirection

- [ ] **Step 3: 最小补强 builder，让 temporary gate 的 `passDirection` 使用 replay 前进方向**

```kotlin
private fun ReplayGate.buildTemporaryFrom(previous: ReplaySample, current: ReplaySample): ReplayGate {
    val centerLatitude = (previous.latitude + current.latitude) / 2.0
    val centerLongitude = (previous.longitude + current.longitude) / 2.0
    val movementLatitude = current.latitude - previous.latitude
    val movementLongitude = current.longitude - previous.longitude
    val movementLength = hypot(movementLatitude, movementLongitude)
    if (movementLength == 0.0) return this

    val halfWidth = lineLength() / 2.0
    val normalLatitude = -movementLongitude / movementLength
    val normalLongitude = movementLatitude / movementLength

    val start = ReplayGeoPoint(
        latitude = centerLatitude + (normalLatitude * halfWidth),
        longitude = centerLongitude + (normalLongitude * halfWidth)
    )
    val end = ReplayGeoPoint(
        latitude = centerLatitude - (normalLatitude * halfWidth),
        longitude = centerLongitude - (normalLongitude * halfWidth)
    )

    return copy(line = ReplayGateLine(start = start, end = end))
}

private fun ReplayGate.toTimingGate(): TimingGate = TimingGate(
    id = name,
    name = name,
    type = if (type == RaceChronoGateType.StartFinish) TimingGateType.StartFinish else TimingGateType.Sector,
    line = GeoLine(
        start = GeoPoint(line.start.latitude, line.start.longitude),
        end = GeoPoint(line.end.latitude, line.end.longitude)
    ),
    passDirection = GeoVector(
        x = line.start.longitude - line.end.longitude,
        y = line.end.latitude - line.start.latitude
    ),
    sequenceIndex = if (type == RaceChronoGateType.StartFinish) 0 else if (name.equals("s1", ignoreCase = true)) 1 else 2,
    minDirectionalSpeedMps = null
)
```

- [ ] **Step 4: 运行集成测试确认通过**

Run: `./gradlew :feature:test:testDebugUnitTest --tests "com.blazepush.feature.test.usecase.ReplayLapTimingIntegrationTest"`
Expected: PASS

- [ ] **Step 5: 运行所有 replay 相关测试，确认没有回归**

Run: `./gradlew :feature:test:testDebugUnitTest --tests "com.blazepush.feature.test.usecase.ReplayTemporaryGateBuilderTest" --tests "com.blazepush.feature.test.usecase.ReplayTemporaryGateGeometryTest" --tests "com.blazepush.feature.test.usecase.ReplayLapTimingIntegrationTest" --tests "com.blazepush.feature.test.usecase.GateCrossingDetectorTest" --tests "com.blazepush.feature.test.usecase.LapTimingEngineTest"`
Expected: PASS

- [ ] **Step 6: 检查 diff，确认未触碰生产代码**

Check:
```bash
git diff -- feature/test/src/main feature/test/src/test docs/superpowers/plans/2026-04-01-replay-lap-progress-log.md
```
Expected: 仅 `src/test` 与 progress log 有改动，`src/main` 无变更

- [ ] **Step 7: Commit**

```bash
git add feature/test/src/test/java/com/blazepush/feature/test/usecase/ReplayLapTimingIntegrationTest.kt feature/test/src/test/java/com/blazepush/feature/test/usecase/ReplayTemporaryGateBuilder.kt feature/test/src/test/java/com/blazepush/feature/test/usecase/ReplayTemporaryGateGeometryTest.kt feature/test/src/test/java/com/blazepush/feature/test/usecase/ReplayTemporaryGateBuilderTest.kt
git commit -m "test: drive replay lap timing with temporary gate"
```

---

## Task 4: 更新本地进度日志并固化停靠点

**Files:**
- Modify: `docs/superpowers/plans/2026-04-01-replay-lap-progress-log.md`

- [ ] **Step 1: 在进度日志中补充本轮结果段落**

```md
### 2026-04-02 temporary gate 结果
- 采用 replay test-only `ReplayTemporaryGateBuilder` 替代 fitted S2 方向；
- builder 仅替换 `s2`，`Start/Finish` 与 `s1` 保持原始 gate；
- temporary gate 基于 `s1 -> next Start/Finish` window 派生，并在无可信 window / anchor 时回退原始 gates；
- `ReplayLapTimingIntegrationTest` 现已通过，说明当前 replay 链路可在不修改 `LapTimingEngine` 的前提下完成一圈；
- 该结果仅证明 replay 测试链路打通，不代表正式资产真值已修复。
```

- [ ] **Step 2: 运行最小回归验证，确认日志描述和测试结果一致**

Run: `./gradlew :feature:test:testDebugUnitTest --tests "com.blazepush.feature.test.usecase.ReplayLapTimingIntegrationTest"`
Expected: PASS，与日志描述一致

- [ ] **Step 3: Commit**

```bash
git add docs/superpowers/plans/2026-04-01-replay-lap-progress-log.md
git commit -m "docs: record replay temporary gate checkpoint"
```

---

## 最终验证

- [ ] **Step 1: 查看工作区状态**

Run: `git status --short`
Expected: 仅剩本轮预期改动；无意外文件

- [ ] **Step 2: 复跑 replay 相关测试**

Run: `./gradlew :feature:test:testDebugUnitTest --tests "com.blazepush.feature.test.usecase.ReplayTemporaryGateBuilderTest" --tests "com.blazepush.feature.test.usecase.ReplayTemporaryGateGeometryTest" --tests "com.blazepush.feature.test.usecase.ReplayLapTimingIntegrationTest"`
Expected: PASS

- [ ] **Step 3: 人工核对成功标准**

Checklist:
- 只在测试域新增 replay temporary gate
- `LapTimingEngine` 未改
- `GateCrossingDetector` 未放宽
- replay 集成测试完成一圈
- progress log 已记录新的停靠结论

- [ ] **Step 4: 准备交付说明**

交付说明应包含：
- 新增的测试域 builder 与测试文件
- 真实 replay 已通过的测试命令
- 仍未解决的是正式资产真值，不应误表述为资产修复完成
