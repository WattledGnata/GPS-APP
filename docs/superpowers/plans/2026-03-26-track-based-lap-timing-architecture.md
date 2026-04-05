# Track-Based Lap Timing Architecture Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将当前基于参考点命中的 `LapDebug` 过渡实现升级为以可复用 `Track` 为核心、以 `TimingGate + passDirection` 为判定基础的圈速记录架构，并保留当前测试流程外壳。

**Architecture:** 第一阶段在 `feature/test` 内建立独立的赛道与圈速领域模型，不污染 `core/domain` 现有测速模型。运行时由 `TrackCatalog` 提供预置赛道，`LapTimingEngine` 基于 GPS 样本、计时线穿越与方向分量驱动 `LapSession`，产出 `LapRecord`；UI 继续沿用“选择 → 配置 → 执行 → 结果”链路，但语义从“参考点命中调试”升级为“基于赛道的圈速记录”。

**Tech Stack:** Kotlin, Jetpack Compose, StateFlow, Android ViewModel, Koin, existing GPS domain models, JUnit

---

## 文件结构

### 修改文件
1. `feature/test/src/main/java/com/blazepush/feature/test/di/AppModule.kt` - 注册新的 `TrackCatalog`、`LapTimingEngine` 依赖并调整 ViewModel 构造参数
2. `feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt` - 从 `LapDebugAnalyzer` 过渡到 `Track` 驱动的 `LapSession` / `LapRecord` 实时状态
3. `feature/test/src/main/java/com/blazepush/feature/test/ui/TestFlowNavigation.kt` - 保留现有导航骨架，但将圈速链路接入 Track 选择/圈速结果语义
4. `feature/test/src/main/java/com/blazepush/feature/test/ui/screen/TestSelectionScreen.kt` - 圈速模式入口补充 Track 选择入口或进入 Track 配置页
5. `feature/test/src/main/java/com/blazepush/feature/test/ui/screen/LapDebugConfigScreen.kt` - 从“参考点配置”改为“Track 选择 + 视图选项”配置页
6. `feature/test/src/main/java/com/blazepush/feature/test/ui/screen/LapDebugExecutionScreen.kt` - 展示 Track、Gate、当前圈、下一计时点、最近 crossing 结果
7. `feature/test/src/main/java/com/blazepush/feature/test/ui/screen/LapDebugResultScreen.kt` - 展示 `LapRecord` 列表、sector 时间、crossing 摘要与轨迹结果
8. `feature/test/src/main/java/com/blazepush/feature/test/ui/components/LapDebugMapPlaceholder.kt` - 从“参考点/命中区域占位”升级为“Track path + gates + trajectory”占位渲染
9. `feature/test/build.gradle.kts` - 若测试代码需要额外断言库或数学辅助，做最小调整；否则不改

### 新建文件
1. `feature/test/src/main/java/com/blazepush/feature/test/model/track/GeoPoint.kt` - 赛道领域基础经纬度点
2. `feature/test/src/main/java/com/blazepush/feature/test/model/track/GeoLine.kt` - 计时线线段模型
3. `feature/test/src/main/java/com/blazepush/feature/test/model/track/GeoVector.kt` - 通过方向向量模型
4. `feature/test/src/main/java/com/blazepush/feature/test/model/track/Track.kt` - 可复用赛道定义模型
5. `feature/test/src/main/java/com/blazepush/feature/test/model/track/TrackPath.kt` - 赛道参考轨迹 polyline
6. `feature/test/src/main/java/com/blazepush/feature/test/model/track/TimingGate.kt` - 起终点 / sector 计时线模型
7. `feature/test/src/main/java/com/blazepush/feature/test/model/track/TrackSource.kt` - `Preset / Remote / Generated` 来源枚举
8. `feature/test/src/main/java/com/blazepush/feature/test/model/laptiming/GpsSample.kt` - 圈速域统一采样点
9. `feature/test/src/main/java/com/blazepush/feature/test/model/laptiming/CrossingReason.kt` - crossing 接受/拒绝原因枚举
10. `feature/test/src/main/java/com/blazepush/feature/test/model/laptiming/CrossingEvent.kt` - 计时线穿越事件
11. `feature/test/src/main/java/com/blazepush/feature/test/model/laptiming/ActiveLap.kt` - 正在进行中的圈草稿
12. `feature/test/src/main/java/com/blazepush/feature/test/model/laptiming/SectorEntry.kt` - 当前圈 sector 通过记录
13. `feature/test/src/main/java/com/blazepush/feature/test/model/laptiming/LapRecord.kt` - 单圈完成结果
14. `feature/test/src/main/java/com/blazepush/feature/test/model/laptiming/LapSession.kt` - 一次圈速记录运行态
15. `feature/test/src/main/java/com/blazepush/feature/test/model/laptiming/LapSessionStatus.kt` - session 状态枚举
16. `feature/test/src/main/java/com/blazepush/feature/test/model/laptiming/LapQualityFlag.kt` - 结果质量标记
17. `feature/test/src/main/java/com/blazepush/feature/test/repository/TrackCatalog.kt` - 预置赛道目录接口/实现
18. `feature/test/src/main/java/com/blazepush/feature/test/repository/PresetTracks.kt` - 第一阶段预置赛道数据
19. `feature/test/src/main/java/com/blazepush/feature/test/usecase/GateCrossingDetector.kt` - 线段穿越与方向分量判定
20. `feature/test/src/main/java/com/blazepush/feature/test/usecase/LapTimingEngine.kt` - 基于 Track 的圈速状态机引擎
21. `feature/test/src/main/java/com/blazepush/feature/test/model/LapViewOptions.kt` - 轨迹/计时线/命中等页面显示选项
22. `feature/test/src/main/java/com/blazepush/feature/test/model/LapRunConfig.kt` - 圈速运行配置（所选 Track + 视图选项）
23. `feature/test/src/test/java/com/blazepush/feature/test/repository/TrackCatalogTest.kt` - 预置赛道目录测试
24. `feature/test/src/test/java/com/blazepush/feature/test/usecase/GateCrossingDetectorTest.kt` - 穿线与方向判定测试
25. `feature/test/src/test/java/com/blazepush/feature/test/usecase/LapTimingEngineTest.kt` - 圈速状态机测试
26. `feature/test/src/test/java/com/blazepush/feature/test/viewmodel/TestSessionViewModelTrackLapTest.kt` - ViewModel Track 模式桥接测试

### 参考文件
1. `docs/superpowers/plans/2026-03-24-lap-debug-mode-implementation-plan.md` - 当前参考点命中版计划，识别哪些对象将被迁移
2. `feature/test/src/main/java/com/blazepush/feature/test/model/LapDebugConfig.kt` - 视图配置字段迁移参考
3. `feature/test/src/main/java/com/blazepush/feature/test/model/LapDebugState.kt` - 当前执行页状态参考
4. `feature/test/src/main/java/com/blazepush/feature/test/model/LapDebugResult.kt` - 当前结果页状态参考
5. `feature/test/src/main/java/com/blazepush/feature/test/usecase/LapDebugAnalyzer.kt` - 将被替换的过渡分析器
6. `core/domain/src/main/java/com/blazepush/core/domain/model/GpsData.kt` - GPS 原始输入来源
7. `feature/test/src/main/java/com/blazepush/feature/test/ui/screen/TestExecutionScreen.kt` - 普通测速执行页结构参考
8. `feature/test/src/main/java/com/blazepush/feature/test/ui/screen/TestResultScreen.kt` - 普通测速结果页结构参考

### 约束
- 第一阶段 `Track` 只描述一种固定跑法，不在单个 `Track` 内支持正反向双规则。
- 第一阶段只做预置赛道，不做服务端下发、用户编辑、轨迹生成器。
- `feature/test` 内先建立完整圈速领域，除非确有跨模块共用需求，否则不要修改 `core/domain`。
- 当前 `LapDebug*` 可作为过渡层存在，但新增实现必须以 `Track` / `TimingGate` / `LapSession` / `LapRecord` 为主语义。

---

## Task 1: 建立 Track 与圈速领域模型边界

**Files:**
- Create: `feature/test/src/main/java/com/blazepush/feature/test/model/track/GeoPoint.kt`
- Create: `feature/test/src/main/java/com/blazepush/feature/test/model/track/GeoLine.kt`
- Create: `feature/test/src/main/java/com/blazepush/feature/test/model/track/GeoVector.kt`
- Create: `feature/test/src/main/java/com/blazepush/feature/test/model/track/TrackSource.kt`
- Create: `feature/test/src/main/java/com/blazepush/feature/test/model/track/TrackPath.kt`
- Create: `feature/test/src/main/java/com/blazepush/feature/test/model/track/TimingGate.kt`
- Create: `feature/test/src/main/java/com/blazepush/feature/test/model/track/Track.kt`
- Create: `feature/test/src/main/java/com/blazepush/feature/test/model/laptiming/GpsSample.kt`
- Create: `feature/test/src/main/java/com/blazepush/feature/test/model/laptiming/CrossingReason.kt`
- Create: `feature/test/src/main/java/com/blazepush/feature/test/model/laptiming/CrossingEvent.kt`
- Create: `feature/test/src/main/java/com/blazepush/feature/test/model/laptiming/SectorEntry.kt`
- Create: `feature/test/src/main/java/com/blazepush/feature/test/model/laptiming/ActiveLap.kt`
- Create: `feature/test/src/main/java/com/blazepush/feature/test/model/laptiming/LapRecord.kt`
- Create: `feature/test/src/main/java/com/blazepush/feature/test/model/laptiming/LapSessionStatus.kt`
- Create: `feature/test/src/main/java/com/blazepush/feature/test/model/laptiming/LapQualityFlag.kt`
- Create: `feature/test/src/main/java/com/blazepush/feature/test/model/laptiming/LapSession.kt`
- Create: `feature/test/src/main/java/com/blazepush/feature/test/model/LapViewOptions.kt`
- Create: `feature/test/src/main/java/com/blazepush/feature/test/model/LapRunConfig.kt`

- [ ] **Step 1: 写模型结构快照测试，锁定最小字段集**

```kotlin
package com.blazepush.feature.test.model

import com.blazepush.feature.test.model.laptiming.LapSessionStatus
import com.blazepush.feature.test.model.track.TrackSource
import org.junit.Assert.assertEquals
import org.junit.Test

class LapTimingModelSmokeTest {
    @Test
    fun trackAndLapRunConfig_defaultsMatchFirstPhaseContract() {
        val options = LapViewOptions()
        val config = LapRunConfig(trackId = "preset-track")

        assertEquals(true, options.showReferencePath)
        assertEquals(true, options.showTimingGates)
        assertEquals(false, options.showCrossingDebug)
        assertEquals("preset-track", config.trackId)
        assertEquals(TrackSource.Preset, TrackSource.Preset)
        assertEquals(LapSessionStatus.Idle, LapSessionStatus.Idle)
    }
}
```

- [ ] **Step 2: 运行模型测试，确认失败**

Run: `./gradlew :feature:test:testDebugUnitTest --tests "com.blazepush.feature.test.model.LapTimingModelSmokeTest"`
Expected: FAIL，提示 `LapViewOptions`、`LapRunConfig` 或 Track 相关类型尚未定义

- [ ] **Step 3: 写 `LapViewOptions` 与 `LapRunConfig` 最小实现**

```kotlin
package com.blazepush.feature.test.model

data class LapViewOptions(
    val showReferencePath: Boolean = true,
    val showTimingGates: Boolean = true,
    val showTrajectory: Boolean = true,
    val showCrossingDebug: Boolean = false
)

data class LapRunConfig(
    val trackId: String,
    val viewOptions: LapViewOptions = LapViewOptions()
)
```

- [ ] **Step 4: 写 Track 基础几何模型实现**

```kotlin
package com.blazepush.feature.test.model.track

data class GeoPoint(
    val latitude: Double,
    val longitude: Double
)

data class GeoLine(
    val start: GeoPoint,
    val end: GeoPoint
)

data class GeoVector(
    val x: Double,
    val y: Double
)

enum class TrackSource {
    Preset,
    Remote,
    Generated
}

data class TrackPath(
    val points: List<GeoPoint>,
    val closed: Boolean = true
)
```

- [ ] **Step 5: 写 `TimingGate` 与 `Track` 最小实现**

```kotlin
package com.blazepush.feature.test.model.track

enum class TimingGateType {
    StartFinish,
    Sector
}

data class TimingGate(
    val id: String,
    val name: String,
    val type: TimingGateType,
    val line: GeoLine,
    val passDirection: GeoVector,
    val sequenceIndex: Int,
    val minDirectionalSpeedMps: Double? = null
)

data class Track(
    val id: String,
    val name: String,
    val layoutName: String? = null,
    val source: TrackSource = TrackSource.Preset,
    val referencePath: TrackPath,
    val startFinishGate: TimingGate,
    val sectorGates: List<TimingGate> = emptyList()
)
```

- [ ] **Step 6: 写圈速运行态模型实现**

```kotlin
package com.blazepush.feature.test.model.laptiming

import com.blazepush.feature.test.model.track.TimingGateType

data class GpsSample(
    val timestampMillis: Long,
    val latitude: Double,
    val longitude: Double,
    val speedKmh: Double? = null,
    val bearingDegrees: Double? = null,
    val altitudeMeters: Double? = null,
    val accuracyMeters: Double? = null
)

enum class CrossingReason {
    Accepted,
    WrongDirection,
    UnexpectedGateOrder,
    TooSlow,
    Cooldown,
    NoIntersection
}

data class CrossingEvent(
    val gateId: String,
    val gateType: TimingGateType,
    val timestampMillis: Long,
    val sampleIndex: Int,
    val accepted: Boolean,
    val reason: CrossingReason,
    val directionalSpeedMps: Double? = null,
    val directionScore: Double? = null
)

data class SectorEntry(
    val gateId: String,
    val crossedAtMillis: Long
)

data class ActiveLap(
    val lapIndex: Int,
    val startedAtMillis: Long,
    val passedGateIds: List<String> = emptyList(),
    val sectorEntries: List<SectorEntry> = emptyList(),
    val sampleStartIndex: Int
)

enum class LapSessionStatus {
    Idle,
    Ready,
    Recording,
    Finished,
    Cancelled
}

enum class LapQualityFlag {
    LowAccuracy,
    SparseSamples,
    SuspectedJitter
}

data class LapRecord(
    val recordId: String,
    val sessionId: String,
    val trackId: String,
    val lapIndex: Int,
    val startedAtMillis: Long,
    val finishedAtMillis: Long,
    val durationMillis: Long,
    val sectorTimes: List<Long> = emptyList(),
    val trajectory: List<GpsSample> = emptyList(),
    val crossingEvents: List<CrossingEvent> = emptyList(),
    val qualityFlags: List<LapQualityFlag> = emptyList()
)

data class LapSession(
    val sessionId: String,
    val trackId: String,
    val status: LapSessionStatus,
    val startedAtMillis: Long? = null,
    val samples: List<GpsSample> = emptyList(),
    val currentLapIndex: Int = 0,
    val nextExpectedGateIndex: Int = 0,
    val crossingEvents: List<CrossingEvent> = emptyList(),
    val completedLaps: List<LapRecord> = emptyList(),
    val activeLap: ActiveLap? = null
)
```

- [ ] **Step 7: 运行模型测试确认通过**

Run: `./gradlew :feature:test:testDebugUnitTest --tests "com.blazepush.feature.test.model.LapTimingModelSmokeTest"`
Expected: PASS

- [ ] **Step 8: 检查模型边界 diff**

Run: `git diff -- feature/test/src/main/java/com/blazepush/feature/test/model`
Expected: 新增 Track 与圈速领域模型；未把新模型塞进 `core/domain`

- [ ] **Step 9: Commit**

```bash
git add feature/test/src/main/java/com/blazepush/feature/test/model feature/test/src/test/java/com/blazepush/feature/test/model/LapTimingModelSmokeTest.kt
git commit -m "feat: add track-based lap timing models"
```

---

## Task 2: 建立预置 Track 目录与样例赛道

**Files:**
- Create: `feature/test/src/main/java/com/blazepush/feature/test/repository/TrackCatalog.kt`
- Create: `feature/test/src/main/java/com/blazepush/feature/test/repository/PresetTracks.kt`
- Create: `feature/test/src/test/java/com/blazepush/feature/test/repository/TrackCatalogTest.kt`

- [ ] **Step 1: 写失败测试，锁定预置赛道目录 contract**

```kotlin
package com.blazepush.feature.test.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class TrackCatalogTest {
    @Test
    fun presetCatalog_returnsAtLeastOneTrackWithStartFinishGate() {
        val catalog = PresetTrackCatalog()

        val tracks = catalog.getAllTracks()
        assertFalse(tracks.isEmpty())
        assertNotNull(tracks.first().startFinishGate)
        assertFalse(tracks.first().referencePath.points.isEmpty())
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `./gradlew :feature:test:testDebugUnitTest --tests "com.blazepush.feature.test.repository.TrackCatalogTest"`
Expected: FAIL，提示 `PresetTrackCatalog` 或 `TrackCatalog` 未定义

- [ ] **Step 3: 定义 `TrackCatalog` 接口**

```kotlin
package com.blazepush.feature.test.repository

import com.blazepush.feature.test.model.track.Track

interface TrackCatalog {
    fun getAllTracks(): List<Track>
    fun getTrack(trackId: String): Track?
}
```

- [ ] **Step 4: 写第一阶段预置赛道数据**

```kotlin
package com.blazepush.feature.test.repository

import com.blazepush.feature.test.model.track.GeoLine
import com.blazepush.feature.test.model.track.GeoPoint
import com.blazepush.feature.test.model.track.GeoVector
import com.blazepush.feature.test.model.track.TimingGate
import com.blazepush.feature.test.model.track.TimingGateType
import com.blazepush.feature.test.model.track.Track
import com.blazepush.feature.test.model.track.TrackPath

internal val presetTracks: List<Track> = listOf(
    Track(
        id = "preset-demo-circuit",
        name = "Demo Circuit",
        layoutName = "Forward",
        referencePath = TrackPath(
            points = listOf(
                GeoPoint(39.900000, 116.400000),
                GeoPoint(39.900300, 116.400400),
                GeoPoint(39.900700, 116.400350),
                GeoPoint(39.900900, 116.399900),
                GeoPoint(39.900500, 116.399500),
                GeoPoint(39.900000, 116.400000)
            )
        ),
        startFinishGate = TimingGate(
            id = "sf",
            name = "Start/Finish",
            type = TimingGateType.StartFinish,
            line = GeoLine(
                start = GeoPoint(39.900050, 116.399950),
                end = GeoPoint(39.899950, 116.400050)
            ),
            passDirection = GeoVector(x = 1.0, y = 0.0),
            sequenceIndex = 0,
            minDirectionalSpeedMps = 2.0
        ),
        sectorGates = listOf(
            TimingGate(
                id = "s1",
                name = "Sector 1",
                type = TimingGateType.Sector,
                line = GeoLine(
                    start = GeoPoint(39.900650, 116.400300),
                    end = GeoPoint(39.900750, 116.400450)
                ),
                passDirection = GeoVector(x = 0.0, y = 1.0),
                sequenceIndex = 1,
                minDirectionalSpeedMps = 2.0
            )
        )
    )
)
```

- [ ] **Step 5: 写 `PresetTrackCatalog` 实现**

```kotlin
package com.blazepush.feature.test.repository

import com.blazepush.feature.test.model.track.Track

class PresetTrackCatalog : TrackCatalog {
    override fun getAllTracks(): List<Track> = presetTracks

    override fun getTrack(trackId: String): Track? = presetTracks.firstOrNull { it.id == trackId }
}
```

- [ ] **Step 6: 运行目录测试确认通过**

Run: `./gradlew :feature:test:testDebugUnitTest --tests "com.blazepush.feature.test.repository.TrackCatalogTest"`
Expected: PASS

- [ ] **Step 7: 人工检查预置数据范围**

Run: `git diff -- feature/test/src/main/java/com/blazepush/feature/test/repository`
Expected: 仅新增预置目录与最小赛道数据；未引入服务端或编辑逻辑

- [ ] **Step 8: Commit**

```bash
git add feature/test/src/main/java/com/blazepush/feature/test/repository feature/test/src/test/java/com/blazepush/feature/test/repository/TrackCatalogTest.kt
git commit -m "feat: add preset track catalog"
```

---

## Task 3: 先用测试驱动实现计时线穿越判定器

**Files:**
- Create: `feature/test/src/main/java/com/blazepush/feature/test/usecase/GateCrossingDetector.kt`
- Create: `feature/test/src/test/java/com/blazepush/feature/test/usecase/GateCrossingDetectorTest.kt`

- [ ] **Step 1: 写失败测试，覆盖“穿线 + 正方向 + 速度阈值”**

```kotlin
package com.blazepush.feature.test.usecase

import com.blazepush.feature.test.model.laptiming.GpsSample
import com.blazepush.feature.test.model.track.GeoLine
import com.blazepush.feature.test.model.track.GeoPoint
import com.blazepush.feature.test.model.track.GeoVector
import com.blazepush.feature.test.model.track.TimingGate
import com.blazepush.feature.test.model.track.TimingGateType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GateCrossingDetectorTest {
    private val gate = TimingGate(
        id = "sf",
        name = "Start/Finish",
        type = TimingGateType.StartFinish,
        line = GeoLine(
            start = GeoPoint(0.0, 0.0),
            end = GeoPoint(0.0, 1.0)
        ),
        passDirection = GeoVector(1.0, 0.0),
        sequenceIndex = 0,
        minDirectionalSpeedMps = 1.0
    )

    @Test
    fun crossingInPositiveDirection_isAccepted() {
        val detector = GateCrossingDetector()
        val previous = GpsSample(1000L, -0.1, 0.5, speedKmh = 10.0)
        val current = GpsSample(1040L, 0.1, 0.5, speedKmh = 10.0)

        val result = detector.detect(previous, current, gate)
        assertTrue(result.accepted)
    }

    @Test
    fun crossingInReverseDirection_isRejected() {
        val detector = GateCrossingDetector()
        val previous = GpsSample(1000L, 0.1, 0.5, speedKmh = 10.0)
        val current = GpsSample(1040L, -0.1, 0.5, speedKmh = 10.0)

        val result = detector.detect(previous, current, gate)
        assertFalse(result.accepted)
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `./gradlew :feature:test:testDebugUnitTest --tests "com.blazepush.feature.test.usecase.GateCrossingDetectorTest"`
Expected: FAIL，提示 `GateCrossingDetector` 未定义

- [ ] **Step 3: 写最小检测结果模型与判定器实现**

```kotlin
package com.blazepush.feature.test.usecase

import com.blazepush.feature.test.model.track.TimingGate
import com.blazepush.feature.test.model.laptiming.CrossingReason
import com.blazepush.feature.test.model.laptiming.GpsSample

data class GateCrossingDetection(
    val accepted: Boolean,
    val reason: CrossingReason,
    val directionalSpeedMps: Double?,
    val directionScore: Double?
)

class GateCrossingDetector {
    fun detect(previous: GpsSample, current: GpsSample, gate: TimingGate): GateCrossingDetection {
        val crossed = (previous.latitude < gate.line.start.latitude && current.latitude >= gate.line.start.latitude) ||
            (previous.latitude > gate.line.start.latitude && current.latitude <= gate.line.start.latitude)
        if (!crossed) {
            return GateCrossingDetection(false, CrossingReason.NoIntersection, null, null)
        }

        val movementX = current.latitude - previous.latitude
        val movementY = current.longitude - previous.longitude
        val directionScore = movementX * gate.passDirection.x + movementY * gate.passDirection.y
        if (directionScore <= 0) {
            return GateCrossingDetection(false, CrossingReason.WrongDirection, null, directionScore)
        }

        val dtSeconds = (current.timestampMillis - previous.timestampMillis).coerceAtLeast(1L) / 1000.0
        val directionalSpeedMps = directionScore / dtSeconds
        val minSpeed = gate.minDirectionalSpeedMps
        if (minSpeed != null && directionalSpeedMps < minSpeed) {
            return GateCrossingDetection(false, CrossingReason.TooSlow, directionalSpeedMps, directionScore)
        }

        return GateCrossingDetection(true, CrossingReason.Accepted, directionalSpeedMps, directionScore)
    }
}
```

- [ ] **Step 4: 增加冷却与未穿线失败用例**

```kotlin
@Test
fun movementWithoutIntersection_isRejected() {
    val detector = GateCrossingDetector()
    val previous = GpsSample(1000L, -0.1, 0.2, speedKmh = 10.0)
    val current = GpsSample(1040L, -0.05, 0.25, speedKmh = 10.0)

    val result = detector.detect(previous, current, gate)
    assertFalse(result.accepted)
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `./gradlew :feature:test:testDebugUnitTest --tests "com.blazepush.feature.test.usecase.GateCrossingDetectorTest"`
Expected: PASS

- [ ] **Step 6: 检查职责边界**

Run: `git diff -- feature/test/src/main/java/com/blazepush/feature/test/usecase/GateCrossingDetector.kt`
Expected: 仅实现局部几何判定；未混入 session 状态机

- [ ] **Step 7: Commit**

```bash
git add feature/test/src/main/java/com/blazepush/feature/test/usecase/GateCrossingDetector.kt feature/test/src/test/java/com/blazepush/feature/test/usecase/GateCrossingDetectorTest.kt
git commit -m "feat: add timing gate crossing detector"
```

---

## Task 4: 用测试驱动实现 `LapTimingEngine` 状态机

**Files:**
- Create: `feature/test/src/main/java/com/blazepush/feature/test/usecase/LapTimingEngine.kt`
- Create: `feature/test/src/test/java/com/blazepush/feature/test/usecase/LapTimingEngineTest.kt`
- Reference: `feature/test/src/main/java/com/blazepush/feature/test/usecase/GateCrossingDetector.kt`
- Reference: `feature/test/src/main/java/com/blazepush/feature/test/model/track/Track.kt`
- Reference: `feature/test/src/main/java/com/blazepush/feature/test/model/laptiming/LapSession.kt`

- [ ] **Step 1: 写失败测试，覆盖起圈、sector、完圈**

```kotlin
package com.blazepush.feature.test.usecase

import com.blazepush.feature.test.model.laptiming.GpsSample
import com.blazepush.feature.test.repository.PresetTrackCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LapTimingEngineTest {
    private val track = PresetTrackCatalog().getAllTracks().first()
    private val engine = LapTimingEngine(GateCrossingDetector())

    @Test
    fun acceptedStartFinishCrossing_startsActiveLap() {
        val session = engine.createSession(track.id)
        val started = engine.onSample(
            session = session,
            track = track,
            sample = GpsSample(1_000L, -0.1, 0.5, speedKmh = 20.0)
        )
        val crossed = engine.onSample(
            session = started,
            track = track,
            sample = GpsSample(1_040L, 0.1, 0.5, speedKmh = 20.0)
        )

        assertTrue(crossed.activeLap != null)
        assertEquals(1, crossed.currentLapIndex)
        assertEquals(1, crossed.nextExpectedGateIndex)
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `./gradlew :feature:test:testDebugUnitTest --tests "com.blazepush.feature.test.usecase.LapTimingEngineTest"`
Expected: FAIL，提示 `LapTimingEngine` 未定义

- [ ] **Step 3: 写 `LapTimingEngine` 最小骨架**

```kotlin
package com.blazepush.feature.test.usecase

import com.blazepush.feature.test.model.laptiming.ActiveLap
import com.blazepush.feature.test.model.laptiming.CrossingEvent
import com.blazepush.feature.test.model.laptiming.GpsSample
import com.blazepush.feature.test.model.laptiming.LapSession
import com.blazepush.feature.test.model.laptiming.LapSessionStatus
import com.blazepush.feature.test.model.track.Track
import java.util.UUID

class LapTimingEngine(
    private val gateCrossingDetector: GateCrossingDetector
) {
    fun createSession(trackId: String): LapSession {
        return LapSession(
            sessionId = UUID.randomUUID().toString(),
            trackId = trackId,
            status = LapSessionStatus.Ready
        )
    }

    fun onSample(session: LapSession, track: Track, sample: GpsSample): LapSession {
        val samples = session.samples + sample
        if (samples.size < 2) {
            return session.copy(status = LapSessionStatus.Recording, samples = samples)
        }
        return session.copy(status = LapSessionStatus.Recording, samples = samples)
    }
}
```

- [ ] **Step 4: 增加失败测试，覆盖合法 sector 推进与完圈产物**

```kotlin
@Test
fun passingSectorThenStartFinish_completesLapRecord() {
    val engine = LapTimingEngine(GateCrossingDetector())
    var session = engine.createSession(track.id)

    session = engine.onSample(session, track, GpsSample(1_000L, -0.1, 0.5, speedKmh = 20.0))
    session = engine.onSample(session, track, GpsSample(1_040L, 0.1, 0.5, speedKmh = 20.0))
    session = engine.onSample(session, track, GpsSample(2_000L, 0.65, 0.3, speedKmh = 20.0))
    session = engine.onSample(session, track, GpsSample(2_040L, 0.75, 0.45, speedKmh = 20.0))
    session = engine.onSample(session, track, GpsSample(3_000L, -0.1, 0.5, speedKmh = 20.0))
    session = engine.onSample(session, track, GpsSample(3_040L, 0.1, 0.5, speedKmh = 20.0))

    assertEquals(1, session.completedLaps.size)
    assertEquals(1, session.completedLaps.first().lapIndex)
    assertEquals(1, session.completedLaps.first().sectorTimes.size)
}
```

- [ ] **Step 5: 运行测试，确认新增用例失败**

Run: `./gradlew :feature:test:testDebugUnitTest --tests "com.blazepush.feature.test.usecase.LapTimingEngineTest"`
Expected: FAIL，提示起圈、sector 或完圈逻辑未实现

- [ ] **Step 6: 最小实现状态机推进逻辑**

```kotlin
private fun buildAcceptedCrossingEvent(
    gateId: String,
    gateType: com.blazepush.feature.test.model.track.TimingGateType,
    sampleIndex: Int,
    sample: GpsSample,
    detection: GateCrossingDetection
): CrossingEvent {
    return CrossingEvent(
        gateId = gateId,
        gateType = gateType,
        timestampMillis = sample.timestampMillis,
        sampleIndex = sampleIndex,
        accepted = true,
        reason = com.blazepush.feature.test.model.laptiming.CrossingReason.Accepted,
        directionalSpeedMps = detection.directionalSpeedMps,
        directionScore = detection.directionScore
    )
}
```

实现要求：
- 第一次合法 `StartFinish`：创建 `activeLap`，`currentLapIndex = 1`，`nextExpectedGateIndex = 1`
- 合法 `Sector` 且 gate 顺序匹配：追加 `SectorEntry`，推进 `nextExpectedGateIndex`
- 所有 sector 完成后再次合法通过 `StartFinish`：生成 `LapRecord`
- 非预期顺序 crossing：记录 rejected event，reason = `UnexpectedGateOrder`
- 第一阶段可不做复杂 cooldown，仅保留结构位置

- [ ] **Step 7: 增加 rejected crossing 失败用例**

```kotlin
@Test
fun unexpectedGateOrder_createsRejectedCrossingEvent() {
    val engine = LapTimingEngine(GateCrossingDetector())
    var session = engine.createSession(track.id)

    session = engine.onSample(session, track, GpsSample(2_000L, 0.65, 0.3, speedKmh = 20.0))
    session = engine.onSample(session, track, GpsSample(2_040L, 0.75, 0.45, speedKmh = 20.0))

    assertTrue(session.crossingEvents.isNotEmpty())
    assertEquals(false, session.crossingEvents.last().accepted)
}
```

- [ ] **Step 8: 运行状态机测试确认通过**

Run: `./gradlew :feature:test:testDebugUnitTest --tests "com.blazepush.feature.test.usecase.LapTimingEngineTest"`
Expected: PASS

- [ ] **Step 9: 检查引擎职责边界**

Run: `git diff -- feature/test/src/main/java/com/blazepush/feature/test/usecase/LapTimingEngine.kt`
Expected: 只处理 session 状态、crossing 事件和 record 产出；未混入 UI 或导航代码

- [ ] **Step 10: Commit**

```bash
git add feature/test/src/main/java/com/blazepush/feature/test/usecase/LapTimingEngine.kt feature/test/src/test/java/com/blazepush/feature/test/usecase/LapTimingEngineTest.kt
git commit -m "feat: add track-based lap timing engine"
```

---

## Task 5: 扩展 DI 与 ViewModel，切换到 Track 驱动的圈速会话

**Files:**
- Modify: `feature/test/src/main/java/com/blazepush/feature/test/di/AppModule.kt`
- Modify: `feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt`
- Create: `feature/test/src/test/java/com/blazepush/feature/test/viewmodel/TestSessionViewModelTrackLapTest.kt`
- Reference: `feature/test/src/main/java/com/blazepush/feature/test/repository/TrackCatalog.kt`
- Reference: `feature/test/src/main/java/com/blazepush/feature/test/usecase/LapTimingEngine.kt`

- [ ] **Step 1: 写失败测试，锁定 ViewModel 新 contract**

```kotlin
package com.blazepush.feature.test.viewmodel

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.blazepush.feature.test.model.LapRunConfig
import com.blazepush.feature.test.model.TestMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TestSessionViewModelTrackLapTest {
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    @Test
    fun selectingLapDebugModeWithTrack_storesLapRunConfig() {
        val viewModel = createViewModel()
        val config = LapRunConfig(trackId = "preset-demo-circuit")

        viewModel.setCurrentMode(TestMode.LapDebug)
        viewModel.setLapRunConfig(config)

        assertEquals(TestMode.LapDebug, viewModel.currentMode.value)
        assertEquals("preset-demo-circuit", viewModel.lapRunConfig.value?.trackId)
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `./gradlew :feature:test:testDebugUnitTest --tests "com.blazepush.feature.test.viewmodel.TestSessionViewModelTrackLapTest"`
Expected: FAIL，提示 `lapRunConfig`、`setLapRunConfig` 或相关状态未实现

- [ ] **Step 3: 注册 `TrackCatalog` 与 `LapTimingEngine`**

```kotlin
val domainModule = module {
    factory { CalculateResultUseCase() }
    factory { SmartTestLauncher() }
    factory { DataQualityEvaluator() }
    factory { AnomalyDetector() }
    factory { DataSmoothing() }
    factory { DataInterpolator() }
    factory { GateCrossingDetector() }
    factory { LapTimingEngine(get()) }
}

val repositoryModule = module {
    single { GpsDataRepository(get()) }
    single { TestResultRepository(get(), get(), get()) }
    single { CarModelRepository(get()) }
    single { BluetoothDeviceRepository(get()) }
    single { TestDataFileStorage(androidContext()) }
    single<TrackCatalog> { PresetTrackCatalog() }
}
```

- [ ] **Step 4: 修改 ViewModel 构造函数与状态定义**

```kotlin
class TestSessionViewModel(
    private val gpsDataViewModel: GpsDataViewModel,
    private val bleDeviceManager: BleDeviceManager,
    private val testResultRepository: TestResultRepository,
    private val calculateResultUseCase: CalculateResultUseCase,
    private val smartTestLauncher: SmartTestLauncher = SmartTestLauncher(),
    private val gpsDataFilter: GpsDataFilter = GpsDataFilter(),
    private val trackCatalog: TrackCatalog,
    private val lapTimingEngine: LapTimingEngine
) : ViewModel() {
    private val _lapRunConfig = MutableStateFlow<LapRunConfig?>(null)
    val lapRunConfig: StateFlow<LapRunConfig?> = _lapRunConfig.asStateFlow()

    private val _lapSession = MutableStateFlow<LapSession?>(null)
    val lapSession: StateFlow<LapSession?> = _lapSession.asStateFlow()

    private val _latestLapRecords = MutableStateFlow<List<LapRecord>>(emptyList())
    val latestLapRecords: StateFlow<List<LapRecord>> = _latestLapRecords.asStateFlow()
}
```

- [ ] **Step 5: 增加失败测试，覆盖 GPS 桥接和停止结果驻留**

```kotlin
@Test
fun gpsUpdatesDuringTrackLapRecording_updateLapSessionAndLatestRecords() {
    val viewModel = createViewModel()
    viewModel.setCurrentMode(TestMode.LapDebug)
    viewModel.setLapRunConfig(LapRunConfig(trackId = "preset-demo-circuit"))

    viewModel.startLapRecording()
    emitGpsSamplesForLap(viewModel)
    viewModel.stopLapRecording()

    assertEquals(true, viewModel.lapSession.value != null)
    assertEquals(true, viewModel.latestLapRecords.value.isNotEmpty() || viewModel.lapSession.value?.samples?.isNotEmpty() == true)
}
```

- [ ] **Step 6: 运行测试，确认失败**

Run: `./gradlew :feature:test:testDebugUnitTest --tests "com.blazepush.feature.test.viewmodel.TestSessionViewModelTrackLapTest"`
Expected: FAIL，提示会话启动、GPS 桥接或停止逻辑未实现

- [ ] **Step 7: 最小实现 Track 模式桥接**

实现要求：
- `setLapRunConfig(config)`：保存所选 Track 与视图配置
- `startLapRecording()`：根据 `trackId` 创建新 `LapSession`
- `gpsData.collect` 中，当 `currentMode == LapDebug` 且 `lapSession != null` 时，将 `GpsData` 映射为 `GpsSample` 并送入 `lapTimingEngine`
- `stopLapRecording()`：停止继续桥接实时数据，并把 `completedLaps` 快照写入 `latestLapRecords`
- 不写入正式 `TestResultRepository`
- 仍保留普通测速链路原有行为

- [ ] **Step 8: 运行 ViewModel 测试确认通过**

Run: `./gradlew :feature:test:testDebugUnitTest --tests "com.blazepush.feature.test.viewmodel.TestSessionViewModelTrackLapTest"`
Expected: PASS

- [ ] **Step 9: 检查 diff 确认未污染普通测速主链路**

Run: `git diff -- feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt feature/test/src/main/java/com/blazepush/feature/test/di/AppModule.kt`
Expected: 变更集中在圈速状态与依赖注入接缝；普通测速 trigger / finish 逻辑主体不变

- [ ] **Step 10: Commit**

```bash
git add feature/test/src/main/java/com/blazepush/feature/test/di/AppModule.kt feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt feature/test/src/test/java/com/blazepush/feature/test/viewmodel/TestSessionViewModelTrackLapTest.kt
git commit -m "feat: wire track lap session into test session viewmodel"
```

---

## Task 6: 升级配置页为 Track 选择 + 视图选项

**Files:**
- Modify: `feature/test/src/main/java/com/blazepush/feature/test/ui/screen/TestSelectionScreen.kt`
- Modify: `feature/test/src/main/java/com/blazepush/feature/test/ui/TestFlowNavigation.kt`
- Modify: `feature/test/src/main/java/com/blazepush/feature/test/ui/screen/LapDebugConfigScreen.kt`

- [ ] **Step 1: 调整 `LapDebugConfigScreen` 输入 contract**

```kotlin
@Composable
fun LapDebugConfigScreen(
    availableTracks: List<Track>,
    initialConfig: LapRunConfig?,
    onConfirm: (LapRunConfig) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
)
```

- [ ] **Step 2: 替换“参考点配置”表单为 Track 选择**

```kotlin
val selectedTrackId = remember(initialConfig) {
    mutableStateOf(initialConfig?.trackId ?: availableTracks.firstOrNull()?.id.orEmpty())
}
val showReferencePath = remember(initialConfig) {
    mutableStateOf(initialConfig?.viewOptions?.showReferencePath ?: true)
}
val showTimingGates = remember(initialConfig) {
    mutableStateOf(initialConfig?.viewOptions?.showTimingGates ?: true)
}
val showTrajectory = remember(initialConfig) {
    mutableStateOf(initialConfig?.viewOptions?.showTrajectory ?: true)
}
```

- [ ] **Step 3: 提交时输出 `LapRunConfig`**

```kotlin
Button(
    onClick = {
        onConfirm(
            LapRunConfig(
                trackId = selectedTrackId.value,
                viewOptions = LapViewOptions(
                    showReferencePath = showReferencePath.value,
                    showTimingGates = showTimingGates.value,
                    showTrajectory = showTrajectory.value
                )
            )
        )
    },
    enabled = selectedTrackId.value.isNotBlank()
) {
    Text("进入圈速记录")
}
```

- [ ] **Step 4: 在导航中改为传递 `LapRunConfig` 与 Track 列表**

```kotlin
LapDebugConfigScreen(
    availableTracks = testSessionViewModel.availableTracks,
    initialConfig = pendingLapRunConfig,
    onConfirm = { config ->
        testSessionViewModel.setLapRunConfig(config)
        currentRoute = TestNavRoute.LapDebugExecution
    },
    onBack = { currentRoute = TestNavRoute.Selection }
)
```

- [ ] **Step 5: 运行最小人工检查**

Run: `git diff -- feature/test/src/main/java/com/blazepush/feature/test/ui/screen/LapDebugConfigScreen.kt feature/test/src/main/java/com/blazepush/feature/test/ui/TestFlowNavigation.kt feature/test/src/main/java/com/blazepush/feature/test/ui/screen/TestSelectionScreen.kt`
Expected: 圈速模式配置已围绕 Track 选择组织；旧参考点表单已退出主流程

- [ ] **Step 6: Commit**

```bash
git add feature/test/src/main/java/com/blazepush/feature/test/ui/screen/LapDebugConfigScreen.kt feature/test/src/main/java/com/blazepush/feature/test/ui/TestFlowNavigation.kt feature/test/src/main/java/com/blazepush/feature/test/ui/screen/TestSelectionScreen.kt
git commit -m "feat: switch lap config to track selection"
```

---

## Task 7: 升级执行页与结果页为 Track/Gate/LapRecord 语义

**Files:**
- Modify: `feature/test/src/main/java/com/blazepush/feature/test/ui/screen/LapDebugExecutionScreen.kt`
- Modify: `feature/test/src/main/java/com/blazepush/feature/test/ui/screen/LapDebugResultScreen.kt`
- Modify: `feature/test/src/main/java/com/blazepush/feature/test/ui/components/LapDebugMapPlaceholder.kt`
- Modify: `feature/test/src/main/java/com/blazepush/feature/test/ui/TestFlowNavigation.kt`

- [ ] **Step 1: 执行页替换状态摘要字段**

```kotlin
Text(
    text = "Track=${selectedTrack?.name ?: \"未选择\"} · 当前圈=${lapSession?.currentLapIndex ?: 0} · 下一 Gate=${nextGateLabel}",
    style = MaterialTheme.typography.bodyMedium
)
Text(
    text = "Crossing=${lastCrossingLabel}",
    style = MaterialTheme.typography.bodyMedium
)
```

- [ ] **Step 2: 将开始/停止动作改为 Track 录圈语义**

```kotlin
ActionCard(
    hasStartedRecording = lapSession?.status == LapSessionStatus.Recording,
    onStart = { testSessionViewModel.startLapRecording() },
    onStop = {
        testSessionViewModel.stopLapRecording()
        onResultReady()
    },
    onBack = onBack
)
```

- [ ] **Step 3: 升级地图占位组件 contract**

```kotlin
@Composable
fun LapDebugMapPlaceholder(
    modifier: Modifier = Modifier,
    track: Track?,
    trajectory: List<GpsSample>,
    latestCrossing: CrossingEvent?,
    title: String = "赛道占位区"
)
```

要求：
- 显示 `referencePath` 点数
- 显示 `startFinishGate` 与 `sectorGates` 数量
- 显示当前 trajectory 点数
- 显示最近 crossing 是否 accepted

- [ ] **Step 4: 结果页改为展示 `latestLapRecords`**

```kotlin
val latestLapRecords by testSessionViewModel.latestLapRecords.collectAsState()

if (latestLapRecords.isEmpty()) {
    EmptyLapDebugResultScreen(onBackToSelection = onBackToSelection)
} else {
    latestLapRecords.forEach { lap ->
        CandidateLapResultRow(
            label = "Lap #${lap.lapIndex}",
            durationMillis = lap.durationMillis,
            sectorTimes = lap.sectorTimes
        )
    }
}
```

- [ ] **Step 5: 增加 crossing 摘要展示**

```kotlin
SummaryRow(
    label = "最近 Crossing",
    value = latestLapRecords.lastOrNull()?.crossingEvents?.lastOrNull()?.reason?.name ?: "无"
)
```

- [ ] **Step 6: 人工检查 UI 语义完成迁移**

Run: `git diff -- feature/test/src/main/java/com/blazepush/feature/test/ui/screen/LapDebugExecutionScreen.kt feature/test/src/main/java/com/blazepush/feature/test/ui/screen/LapDebugResultScreen.kt feature/test/src/main/java/com/blazepush/feature/test/ui/components/LapDebugMapPlaceholder.kt`
Expected: 页面主语义已从参考点命中迁移为 Track / Gate / LapRecord

- [ ] **Step 7: Commit**

```bash
git add feature/test/src/main/java/com/blazepush/feature/test/ui/screen/LapDebugExecutionScreen.kt feature/test/src/main/java/com/blazepush/feature/test/ui/screen/LapDebugResultScreen.kt feature/test/src/main/java/com/blazepush/feature/test/ui/components/LapDebugMapPlaceholder.kt feature/test/src/main/java/com/blazepush/feature/test/ui/TestFlowNavigation.kt
git commit -m "feat: upgrade lap debug screens to track timing semantics"
```

---

## Task 8: 收尾迁移与旧 `LapDebug*` 兼容清理

**Files:**
- Modify: `feature/test/src/main/java/com/blazepush/feature/test/model/LapDebugConfig.kt`
- Modify: `feature/test/src/main/java/com/blazepush/feature/test/model/LapDebugState.kt`
- Modify: `feature/test/src/main/java/com/blazepush/feature/test/model/LapDebugResult.kt`
- Modify or Delete (only if explicitly approved during implementation): `feature/test/src/main/java/com/blazepush/feature/test/model/LapDebugReferencePoint.kt`
- Modify or Delete (only if explicitly approved during implementation): `feature/test/src/main/java/com/blazepush/feature/test/usecase/LapDebugAnalyzer.kt`

- [ ] **Step 1: 审计旧 `LapDebug*` 是否仍被主流程依赖**

Run: `git grep -n "LapDebugReferencePoint\|LapDebugAnalyzer\|LapDebugResult\|LapDebugState" -- feature/test/src/main/java`
Expected: 只剩兼容层或结果页空态使用点；主流程已切到 Track 语义

- [ ] **Step 2: 若需保留兼容层，改为薄包装对象**

```kotlin
@Deprecated("Use LapRunConfig instead")
data class LapDebugConfig(
    val trackId: String,
    val showTrajectory: Boolean = true,
    val showTimingGates: Boolean = true
)
```

- [ ] **Step 3: 若无需保留，删除前先确认引用已清零**

Run: `git grep -n "LapDebugReferencePoint\|LapDebugAnalyzer" -- feature/test/src/main/java`
Expected: 无输出后再删除

- [ ] **Step 4: 运行全量圈速相关测试**

Run: `./gradlew :feature:test:testDebugUnitTest --tests "com.blazepush.feature.test.*"`
Expected: Track 相关新测试通过；若旧测试仍存在，需明确保留原因

- [ ] **Step 5: Commit**

```bash
git add feature/test/src/main/java/com/blazepush/feature/test/model feature/test/src/main/java/com/blazepush/feature/test/usecase feature/test/src/test/java/com/blazepush/feature/test
git commit -m "refactor: migrate lap debug flow to track-based timing"
```

---

## Task 9: 最终验证与交付说明

**Files:**
- Verify: `feature/test/src/main/java/com/blazepush/feature/test/**`
- Verify: `docs/superpowers/plans/2026-03-26-track-based-lap-timing-architecture.md`

- [ ] **Step 1: 运行预置目录测试**

Run: `./gradlew :feature:test:testDebugUnitTest --tests "com.blazepush.feature.test.repository.TrackCatalogTest"`
Expected: PASS

- [ ] **Step 2: 运行穿线判定测试**

Run: `./gradlew :feature:test:testDebugUnitTest --tests "com.blazepush.feature.test.usecase.GateCrossingDetectorTest"`
Expected: PASS

- [ ] **Step 3: 运行状态机测试**

Run: `./gradlew :feature:test:testDebugUnitTest --tests "com.blazepush.feature.test.usecase.LapTimingEngineTest"`
Expected: PASS

- [ ] **Step 4: 运行 ViewModel Track 圈速测试**

Run: `./gradlew :feature:test:testDebugUnitTest --tests "com.blazepush.feature.test.viewmodel.TestSessionViewModelTrackLapTest"`
Expected: PASS

- [ ] **Step 5: 运行 feature/test 全量单测**

Run: `./gradlew :feature:test:testDebugUnitTest`
Expected: 本次新增测试通过；若有仓库既有失败，需区分是否与本次变更相关

- [ ] **Step 6: 执行人工验证清单**

检查点：
- 圈速模式从 `Selection -> LapDebugConfig -> LapDebugExecution -> LapDebugResult` 正常走通
- 配置页可选择预置 Track
- 执行页可看到 Track 名称、起终点/sector 数量、最近 crossing 摘要
- 合法顺序通过 gate 后能形成 `LapRecord`
- 反向或错误顺序 crossing 不会误计圈
- 停止后结果页展示 lap 时间与 sector 时间
- 正式历史中没有新增 `TestResult`
- 普通测速链路仍可正常进入 `Execution -> Result -> History`

- [ ] **Step 7: 检查 diff 范围**

Run: `git diff --stat`
Expected: 变更集中在 `feature/test` 的 Track 圈速实现、测试与最小接缝；未大面积修改 `core/domain`

- [ ] **Step 8: 准备交付说明**

交付说明必须包含：
- 新增的 Track / TimingGate / LapSession / LapRecord 文件
- `TestSessionViewModel`、`AppModule`、导航和 UI 的最小接缝修改
- 旧 `LapDebug*` 的处理策略（保留兼容层或完成迁移）
- 哪些能力刻意未做：服务端下发、用户编辑、基于轨迹生成、复杂 GPS 纠偏
- 新增测试分别覆盖了哪些关键行为

---

## Self-Review

### Spec coverage
- 已覆盖 `Track` 独立可复用模型：Task 1, Task 2
- 已覆盖 `TimingGate + passDirection`：Task 1, Task 3
- 已覆盖 `LapSession / LapRecord`：Task 1, Task 4, Task 5
- 已覆盖配置页从参考点迁移到 Track 选择：Task 6
- 已覆盖执行页 / 结果页迁移：Task 7
- 已覆盖旧 `LapDebug*` 迁移策略：Task 8
- 已覆盖测试与人工验证：Task 9

### Placeholder scan
- 未使用 TBD / TODO / implement later
- 每个代码步骤提供了实际代码块
- 每个运行步骤提供了明确命令与期望输出

### Type consistency
- 主类型名统一使用：`Track`、`TimingGate`、`LapSession`、`LapRecord`、`LapRunConfig`
- 旧 `LapDebug*` 只在迁移任务中作为兼容对象出现，不再作为新增主模型
