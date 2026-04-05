# TFIC RCZ Track Geometry Alignment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 `preset-tfic-lpcc` 的 `start-finish / s1 / s2` gate 几何修正为 RCZ trap 真值，并让 runtime `ReplayAlignedTrackCatalog` 统一使用这套正确几何。

**Architecture:** 保持现有 `PresetTrackCatalog + ReplayAlignedTrackCatalog` 结构不变，只修 TFIC 赛道。`PresetTracks.kt` 直接落地 RCZ 推导后的 gate 端点与 `passDirection`；`ReplayAlignedTrackCatalog.kt` 保留 replay path 生成逻辑，但 runtime gate 统一从修正后的 TFIC preset 取值，避免错误 fallback 几何继续污染运行时对象。

**Tech Stack:** Kotlin、JUnit4、Gradle、Android JVM unit tests

---

## File Structure

### Modify
- `/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing/feature/test/src/main/java/com/blazepush/feature/test/repository/PresetTracks.kt`
  - 只修改 `preset-tfic-lpcc` 的 `startFinishGate`、`s1`、`s2` 端点与 `passDirection`
- `/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing/feature/test/src/main/java/com/blazepush/feature/test/repository/ReplayAlignedTrackCatalog.kt`
  - 只修改 TFIC runtime gate 的装配逻辑，统一使用修正后的 preset gate 几何
- `/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing/feature/test/src/test/java/com/blazepush/feature/test/repository/ReplayAlignedTrackCatalogTest.kt`
  - 新增 RCZ 真值断言，验证 preset/runtime 几何一致
- `/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing/feature/test/src/test/java/com/blazepush/feature/test/viewmodel/TestSessionViewModelTrackLapTest.kt`
  - 新增 debug summary / runtime geometry 对齐断言

### No New Production Files
- 本轮不新增通用 RCZ 解析器、不新增新的 model 层、不新增新的 repository。

---

### Task 1: 写失败测试锁定 TFIC RCZ 真值

**Files:**
- Modify: `/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing/feature/test/src/test/java/com/blazepush/feature/test/repository/ReplayAlignedTrackCatalogTest.kt`
- Test: `/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing/feature/test/src/test/java/com/blazepush/feature/test/repository/ReplayAlignedTrackCatalogTest.kt`

- [ ] **Step 1: 在 `ReplayAlignedTrackCatalogTest.kt` 中新增 preset 几何真值测试**

```kotlin
@Test
fun presetTrack_matchesTficRczTrapGeometry() {
    val track = requireNotNull(PresetTrackCatalog().getTrack("preset-tfic-lpcc"))

    assertGateLine(
        gate = track.startFinishGate,
        startLatitude = 30.496167246506413,
        startLongitude = 104.43343794245452,
        endLatitude = 30.49619075349359,
        endLongitude = 104.43291739087881,
        passDirectionX = -0.0002602757878550089,
        passDirectionY = -0.000023506987175358924
    )
    assertGateLine(
        gate = track.sectorGates.first { it.id == "s1" },
        startLatitude = 30.49004451419976,
        startLongitude = 104.43252709154902,
        endLatitude = 30.48959781913357,
        endLongitude = 104.43258157511764,
        passDirectionX = -0.00002724178431097556,
        passDirectionY = -0.00044669506619011374
    )
    assertGateLine(
        gate = track.sectorGates.first { it.id == "s2" },
        startLatitude = 30.4957579139104,
        startLongitude = 104.4369620745035,
        endLatitude = 30.495765752756267,
        endLongitude = 104.43748325882984,
        passDirectionX = -0.0002605921631704301,
        passDirectionY = 0.000007838845867048829
    )
}

private fun assertGateLine(
    gate: TimingGate,
    startLatitude: Double,
    startLongitude: Double,
    endLatitude: Double,
    endLongitude: Double,
    passDirectionX: Double,
    passDirectionY: Double
) {
    assertEquals(startLatitude, gate.line.start.latitude, 0.0000000001)
    assertEquals(startLongitude, gate.line.start.longitude, 0.0000000001)
    assertEquals(endLatitude, gate.line.end.latitude, 0.0000000001)
    assertEquals(endLongitude, gate.line.end.longitude, 0.0000000001)
    assertEquals(passDirectionX, gate.passDirection.x, 0.0000000001)
    assertEquals(passDirectionY, gate.passDirection.y, 0.0000000001)
}
```

- [ ] **Step 2: 新增 runtime 几何真值测试**

```kotlin
@Test
fun generatedTrack_reusesCorrectedTficGateGeometry() {
    val catalog = ReplayAlignedTrackCatalog(
        replayTrackSource = object : ReplayTrackSource {
            override fun loadReplayJson(): String = replayJson
            override fun loadTrackVbo(): String = replayVbo
        },
        fallbackCatalog = PresetTrackCatalog()
    )

    val track = requireNotNull(catalog.getTrack("preset-tfic-lpcc"))

    assertGateLine(
        gate = track.startFinishGate,
        startLatitude = 30.496167246506413,
        startLongitude = 104.43343794245452,
        endLatitude = 30.49619075349359,
        endLongitude = 104.43291739087881,
        passDirectionX = -0.0002602757878550089,
        passDirectionY = -0.000023506987175358924
    )
    assertGateLine(
        gate = track.sectorGates.first { it.id == "s1" },
        startLatitude = 30.49004451419976,
        startLongitude = 104.43252709154902,
        endLatitude = 30.48959781913357,
        endLongitude = 104.43258157511764,
        passDirectionX = -0.00002724178431097556,
        passDirectionY = -0.00044669506619011374
    )
    assertGateLine(
        gate = track.sectorGates.first { it.id == "s2" },
        startLatitude = 30.4957579139104,
        startLongitude = 104.4369620745035,
        endLatitude = 30.495765752756267,
        endLongitude = 104.43748325882984,
        passDirectionX = -0.0002605921631704301,
        passDirectionY = 0.000007838845867048829
    )
}
```

- [ ] **Step 3: 跑单测确认先失败**

Run:
```bash
./gradlew -p "/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing" :feature:test:testDebugUnitTest --tests "com.blazepush.feature.test.repository.ReplayAlignedTrackCatalogTest"
```

Expected: FAIL，至少出现 `presetTrack_matchesTficRczTrapGeometry` 或 `generatedTrack_reusesCorrectedTficGateGeometry` 的断言不匹配。

- [ ] **Step 4: 提交失败前状态说明到工作笔记，不提交代码**

记录预期失败点：
```text
当前 preset/start-finish、s1、s2 与 RCZ 真值不一致；runtime track 仍混入 fallback gate，测试红灯符合预期。
```

---

### Task 2: 修正 TFIC preset gate 真值

**Files:**
- Modify: `/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing/feature/test/src/main/java/com/blazepush/feature/test/repository/PresetTracks.kt`
- Test: `/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing/feature/test/src/test/java/com/blazepush/feature/test/repository/ReplayAlignedTrackCatalogTest.kt`

- [ ] **Step 1: 将 `startFinishGate` 改成 RCZ 真值**

把：
```kotlin
line = GeoLine(
    start = GeoPoint(30.4961790, 104.43329413333335),
    end = GeoPoint(30.49662805, 104.43326681666667)
),
passDirection = GeoVector(x = 0.00002731666667443733, y = 0.00044904999999673123)
```

改成：
```kotlin
line = GeoLine(
    start = GeoPoint(30.496167246506413, 104.43343794245452),
    end = GeoPoint(30.49619075349359, 104.43291739087881)
),
passDirection = GeoVector(
    x = -0.0002602757878550089,
    y = -0.000023506987175358924
)
```

- [ ] **Step 2: 将 `s1` 改成 RCZ 真值**

把：
```kotlin
line = GeoLine(
    start = GeoPoint(30.489821166666662, 104.43391746666666),
    end = GeoPoint(30.489774166666667, 104.43443643333333)
),
passDirection = GeoVector(x = -0.0005189666666751691, y = -0.000046999999995023245)
```

改成：
```kotlin
line = GeoLine(
    start = GeoPoint(30.49004451419976, 104.43252709154902),
    end = GeoPoint(30.48959781913357, 104.43258157511764)
),
passDirection = GeoVector(
    x = -0.00002724178431097556,
    y = -0.00044669506619011374
)
```

- [ ] **Step 3: 将 `s2` 改成 RCZ 真值**

把：
```kotlin
line = GeoLine(
    start = GeoPoint(30.48948356944875, 104.43221728363045),
    end = GeoPoint(30.48992993055125, 104.43227191636953)
),
passDirection = GeoVector(x = -0.00005463273907935218, y = 0.0004463611024974057)
```

改成：
```kotlin
line = GeoLine(
    start = GeoPoint(30.4957579139104, 104.4369620745035),
    end = GeoPoint(30.495765752756267, 104.43748325882984)
),
passDirection = GeoVector(
    x = -0.0002605921631704301,
    y = 0.000007838845867048829
)
```

- [ ] **Step 4: 跑 repository 测试确认 preset 部分通过**

Run:
```bash
./gradlew -p "/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing" :feature:test:testDebugUnitTest --tests "com.blazepush.feature.test.repository.ReplayAlignedTrackCatalogTest.presetTrack_matchesTficRczTrapGeometry"
```

Expected: PASS

- [ ] **Step 5: 提交 preset 真值修正**

```bash
git -C "/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing" add feature/test/src/main/java/com/blazepush/feature/test/repository/PresetTracks.kt feature/test/src/test/java/com/blazepush/feature/test/repository/ReplayAlignedTrackCatalogTest.kt
git -C "/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing" commit -m "fix: align TFIC preset gates with RCZ geometry"
```

---

### Task 3: 修正 runtime catalog gate 装配逻辑

**Files:**
- Modify: `/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing/feature/test/src/main/java/com/blazepush/feature/test/repository/ReplayAlignedTrackCatalog.kt`
- Test: `/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing/feature/test/src/test/java/com/blazepush/feature/test/repository/ReplayAlignedTrackCatalogTest.kt`

- [ ] **Step 1: 删除 `startFinishGate` 的旧 fallback 覆盖逻辑**

把：
```kotlin
val startFinishGate = fallbackTrack?.startFinishGate
    ?: alignedGates.firstOrNull { it.type == RuntimeGateType.StartFinish }?.toTimingGate(sequenceIndex = 0)
    ?: error("Start/finish gate missing")
```

改成基于 fallbackTrack 必须存在且直接取修正后真值：
```kotlin
val fallbackTrack = requireNotNull(fallbackCatalog.getTrack(TFIC_TRACK_ID)) {
    "Fallback track missing for $TFIC_TRACK_ID"
}
val startFinishGate = fallbackTrack.startFinishGate
```

- [ ] **Step 2: 让 runtime `sectorGates` 也统一复用修正后的 TFIC preset gate**

把：
```kotlin
val sectorGates = alignedGates
    .filter { it.type == RuntimeGateType.Split }
    .sortedBy { gate -> gateOrder(gate.name) }
    .mapIndexed { index, gate -> gate.toTimingGate(sequenceIndex = index + 1) }
```

改成：
```kotlin
val sectorGates = fallbackTrack.sectorGates
    .sortedBy { it.sequenceIndex }
```
```

并删除此方法内对 `alignedGates` 生成 `startFinishGate / sectorGates` 的依赖，只保留 replay path 用于 `referencePath`。

- [ ] **Step 3: 让 `buildReplayAlignedTrack` 只把 replay 数据用于路径，不再用于 gate 真值**

将 `buildReplayAlignedTrack` 逻辑收敛成：
```kotlin
private fun buildReplayAlignedTrack(replayJson: String, vbo: String): Track {
    val replaySamples = parseReplaySamples(replayJson)
    require(replaySamples.isNotEmpty()) { "Replay samples are empty" }

    val fallbackTrack = requireNotNull(fallbackCatalog.getTrack(TFIC_TRACK_ID)) {
        "Fallback track missing for $TFIC_TRACK_ID"
    }

    return Track(
        id = TFIC_TRACK_ID,
        name = fallbackTrack.name,
        layoutName = "REAL_TRACK_REPLAY",
        source = TrackSource.Generated,
        referencePath = TrackPath(
            points = replaySamples.map { sample ->
                GeoPoint(sample.latitude, sample.longitude)
            }
        ),
        startFinishGate = fallbackTrack.startFinishGate,
        sectorGates = fallbackTrack.sectorGates.sortedBy { it.sequenceIndex }
    )
}
```

同时删除本方法中不再使用的：
```kotlin
val rawGates = parseVboGates(...)
val alignedGates = buildReplayAlignedGates(...)
```

- [ ] **Step 4: 删除 `buildReplayAlignedTrack` 中不再使用的私有方法与 import**

删除未再被调用的私有逻辑：
```kotlin
parseVboGates(...)
buildVboNormalizer(...)
buildReplayAlignedGates(...)
findAcceptedCrossingStartIndex(...)
selectAnchor(...)
isTrustworthyWindow(...)
detect(...)
signedSide(...)
RuntimeGate.buildTemporaryFrom(...)
RuntimeGate.lineLength()
RuntimeGate.passDirection()
RuntimeGate.toTimingGate(...)
gateOrder(...)
parseRaceChronoTimeSeconds(...)
parseVboCoordinate(...)
toNmea(...)
```

同步删除不再使用的类型与 import，例如：
```kotlin
GeoLine
GeoVector
TimingGate
TimingGateType
Instant
ZoneOffset
abs
hypot
SerializedName 之外若仍在用则保留
```

目标是让 `ReplayAlignedTrackCatalog.kt` 只保留：
- replay JSON 解析
- asset source
- 生成 runtime referencePath
- 复用 fallbackTrack gate 真值

- [ ] **Step 5: 跑 repository 测试确认 runtime 几何已改正**

Run:
```bash
./gradlew -p "/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing" :feature:test:testDebugUnitTest --tests "com.blazepush.feature.test.repository.ReplayAlignedTrackCatalogTest"
```

Expected: PASS

- [ ] **Step 6: 提交 runtime catalog 修正**

```bash
git -C "/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing" add feature/test/src/main/java/com/blazepush/feature/test/repository/ReplayAlignedTrackCatalog.kt feature/test/src/test/java/com/blazepush/feature/test/repository/ReplayAlignedTrackCatalogTest.kt
git -C "/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing" commit -m "fix: unify TFIC runtime gates with corrected preset geometry"
```

---

### Task 4: 补 ViewModel 回归，锁定 runtime summary 已切换新几何

**Files:**
- Modify: `/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing/feature/test/src/test/java/com/blazepush/feature/test/viewmodel/TestSessionViewModelTrackLapTest.kt`
- Test: `/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing/feature/test/src/test/java/com/blazepush/feature/test/viewmodel/TestSessionViewModelTrackLapTest.kt`

- [ ] **Step 1: 在 ViewModel 测试中增加 debug summary 坐标断言**

在 `lapDebugMode_trackDebugSummaryIncludesRuntimeGeometryMetadata()` 里追加：
```kotlin
assertTrue(summary.contains("startFinish=30.496167246506413,104.43343794245452->30.49619075349359,104.43291739087881"))
assertTrue(summary.contains("s1=30.49004451419976,104.43252709154902->30.48959781913357,104.43258157511764"))
assertTrue(summary.contains("s2=30.4957579139104,104.4369620745035->30.495765752756267,104.43748325882984"))
```

- [ ] **Step 2: 保留开圈/闭圈回归测试，不改测试意图**

确认以下测试仍继续使用 runtime track 本身生成 crossing，不改其结构：
```kotlin
lapDebugMode_runtimeReplayCatalogUsesGeneratedTrackGeometry()
lapDebugMode_replayAlignedTrackCatalogProducesAcceptedStartFinishCrossing()
lapDebugMode_secondStartFinishClosesLapEvenWhenSectorChainIsIncomplete()
```

如有断言因新几何变更而需要调整，只允许调整坐标相关断言，不允许放宽业务语义断言。

- [ ] **Step 3: 跑 ViewModel 测试确认 summary 和主链路回归都通过**

Run:
```bash
./gradlew -p "/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing" :feature:test:testDebugUnitTest --tests "com.blazepush.feature.test.viewmodel.TestSessionViewModelTrackLapTest"
```

Expected: PASS

- [ ] **Step 4: 提交 ViewModel 回归测试修正**

```bash
git -C "/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing" add feature/test/src/test/java/com/blazepush/feature/test/viewmodel/TestSessionViewModelTrackLapTest.kt
git -C "/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing" commit -m "test: assert TFIC runtime track summary uses RCZ geometry"
```

---

### Task 5: 全量验证并准备真机回归

**Files:**
- Modify: none
- Test: repository + viewmodel unit tests

- [ ] **Step 1: 跑本轮相关 JVM 单测全集**

Run:
```bash
./gradlew -p "/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing" :feature:test:testDebugUnitTest --tests "com.blazepush.feature.test.repository.ReplayAlignedTrackCatalogTest" --tests "com.blazepush.feature.test.viewmodel.TestSessionViewModelTrackLapTest"
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 2: 查看工作区差异，确认只包含本轮范围文件**

Run:
```bash
git -C "/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing" status --short
```

Expected: 只看到以下文件或其子集：
```text
feature/test/src/main/java/com/blazepush/feature/test/repository/PresetTracks.kt
feature/test/src/main/java/com/blazepush/feature/test/repository/ReplayAlignedTrackCatalog.kt
feature/test/src/test/java/com/blazepush/feature/test/repository/ReplayAlignedTrackCatalogTest.kt
feature/test/src/test/java/com/blazepush/feature/test/viewmodel/TestSessionViewModelTrackLapTest.kt
```

- [ ] **Step 3: 记录真机验收观察点**

在执行真机安装前，明确要观察：
```text
1. lapDebugTrackSummary 中 startFinish/s1/s2 坐标是否切换为 RCZ 真值
2. 预期起点附近是否恢复 accepted crossing
3. 是否消除远处误触发
```

- [ ] **Step 4: 如用户要求继续真机验证，再进入 Android 验证工作流**

后续命令不在本计划内执行，但执行时应走已有 Android 安装/抓日志流程，而不是临时改 detector 或继续调几何。

---

## Self-Review

### Spec coverage
- RCZ 真值对齐到 TFIC preset：Task 1 + Task 2
- 修正 runtime catalog fallback 污染：Task 3
- 锁定 runtime summary / ViewModel 回归：Task 4
- 为真机复验准备明确观察点：Task 5

### Placeholder scan
- 无 `TODO` / `TBD`
- 所有代码步骤给出明确替换内容或断言内容
- 所有测试步骤给出精确命令和预期结果

### Type consistency
- 使用的文件、测试类、方法名均来自当前代码：
  - `PresetTrackCatalog`
  - `ReplayAlignedTrackCatalog`
  - `ReplayAlignedTrackCatalogTest`
  - `TestSessionViewModelTrackLapTest`
  - `currentLapTrackDebugSummary()`
- 计划只围绕现有类型与字段改动，没有引入未定义的新生产类型
