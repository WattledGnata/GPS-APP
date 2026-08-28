# 实施任务（依赖顺序）

本 change 单一 capability `active-lap-distance-accumulator`，跨 model + engine + UI 三层。**§3 engine 改造是 BREAKING 连锁**：`handleSectorCrossing` 签名加 `activeLapWithDistance: ActiveLap?` 参数 + 5 路径全部消费 + `processSample` 顶部集中构造 → 必须一气做完才能编译。

参考 `proposal.md` / `design.md` / `specs/active-lap-distance-accumulator/spec.md`。

---

## 0. grep 预检（已核实，作为实施依据存档）

- [x] 0.1 **`ActiveLap.kt` 真实路径**：`feature/test/src/main/java/com/blazepush/feature/test/model/laptiming/ActiveLap.kt`（5 字段：`lapIndex` / `startedAtMillis` / `passedGateIds` / `sectorEntries` / `sampleStartIndex`，本 change 加 `distanceMetersSinceStart` 为第 6 字段）
- [x] 0.2 **`LapRecord.kt` 真实路径**：`feature/test/src/main/java/com/blazepush/feature/test/model/laptiming/LapRecord.kt`（**不**在 core/domain，spec v2 P2-2 已纠正；本 change schema 零改动）
- [x] 0.3 **`processSample` 真实签名**：`fun processSample(session: LapSession, track: Track, previousSample: GpsSample, currentSample: GpsSample): LapSession`（4 参数，`previousSample` 是显式入参；签名 MUST 不变）
- [x] 0.4 **`ActiveLap` 构造点 2 处**：`LapTimingEngine.kt:142-148`（首次开圈，路径 b）+ `:199-204`（闭圈后新开 lap，路径 c），均需显式 `distanceMetersSinceStart = 0.0`
- [x] 0.5 **engine 5 类返回路径定位**：(a) line 73 ts 回跳 `return session` / (b) line 142-148 / (c) line 192-205 / (d) line 98 no target gate / (e) line 287-289 sector rejected / (f) line 292-303 sector accepted
- [x] 0.6 **UI 删除目标**：`LapDebugExecutionScreen.kt:239-255` `calculateDistanceSince` + `:257-275` private `haversineDistanceMeters` + `:23` 孤立 `GpsSample` import（删函数后该 import 仅 calculateDistanceSince 使用，必须一并删）
- [x] 0.7 **测试文件已存在**：`LapDebugExecutionScreenStateTest.kt` 已位于 `feature/test/src/test/.../ui/screen/`，追加测试不新建

---

## 1. `ActiveLap` 字段（D6）

- [x] 1.1 **加字段** 在 `ActiveLap.kt:15-21`：

  ```kotlin
  data class ActiveLap(
      val lapIndex: Int,
      val startedAtMillis: Long,
      val passedGateIds: List<String> = emptyList(),
      val sectorEntries: List<SectorEntry> = emptyList(),
      val sampleStartIndex: Int,
      // A22 change fix-active-lap-distance-accumulator：
      // 本圈累计距离（米），engine 唯一 producer，UI consumer-only。
      // active lap 生命期内单调不减；闭圈瞬间立即被新 ActiveLap(0.0) 替换。
      val distanceMetersSinceStart: Double = 0.0,
  )
  ```

  默认值 `0.0` 让现有调用点（不显式传该参数）保持编译通过；§3 engine 改造时再显式写出。
- [x] 1.2 **编译门槛**：`./gradlew :feature:test:compileDebugKotlin` BUILD SUCCESSFUL（仅加字段，零调用方改动应通过）。

---

## 2. 新建 `GeoMath.kt`（D1）

- [x] 2.1 **新建** `feature/test/src/main/java/com/blazepush/feature/test/usecase/GeoMath.kt`：

  ```kotlin
  package com.blazepush.feature.test.usecase

  /**
   * A22 change fix-active-lap-distance-accumulator：
   * 把 UI 私有 haversineDistanceMeters 实现迁出为 engine 可复用的 internal 工具。
   * 数学公式不变，仅改可见性 + 落点。
   */
  internal fun haversineDistanceMeters(
      startLatitude: Double,
      startLongitude: Double,
      endLatitude: Double,
      endLongitude: Double,
  ): Double {
      val earthRadiusMeters = 6_371_000.0
      val latitudeDelta = Math.toRadians(endLatitude - startLatitude)
      val longitudeDelta = Math.toRadians(endLongitude - startLongitude)
      val startLatitudeRadians = Math.toRadians(startLatitude)
      val endLatitudeRadians = Math.toRadians(endLatitude)
      val a = kotlin.math.sin(latitudeDelta / 2).let { it * it } +
          kotlin.math.cos(startLatitudeRadians) * kotlin.math.cos(endLatitudeRadians) *
          kotlin.math.sin(longitudeDelta / 2).let { it * it }
      val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
      return earthRadiusMeters * c
  }
  ```

  注意：实现体直接照搬 `LapDebugExecutionScreen.kt:257-275` 原代码，公式不动；可见性从 `private fun` 改为 `internal fun`（feature/test 模块内 engine + UI 可共享）。
- [x] 2.2 **编译门槛**：`./gradlew :feature:test:compileDebugKotlin` BUILD SUCCESSFUL。

---

## 3. engine `LapTimingEngine` 5 路径改造（D2 + D3，BREAKING 连锁）

> **注意**：§3.1-§3.5 必须**一气做完**到 §3.6 编译门槛才能通过。中间步骤会因 `handleSectorCrossing` 签名变更而暂时编译失败，**预期行为，不要在中途跑 compile**。

- [x] 3.1 **`processSample` 顶部集中构造 `activeLapWithDistance`**（`LapTimingEngine.kt:51-108` 主函数）：

  在 line 76 `val updatedSamples = session.samples + currentSample` 之后、line 77 `startFinishDetector.detect(...)` 之前插入：

  ```kotlin
  // A22：相邻 samples 流的 haversine 增量。增量来源 MUST 是 session.samples.lastOrNull()
  // 与 currentSample（samples 流口径），与 UI 旧 samples.zipWithNext() 同源；
  // 不复用 detector 用的 previousSample 参数（虽然此处通常指向同一 GpsSample，
  // 但语义来源应明示从 samples 流走，future 若 previousSample 因 ts 守卫等改变，
  // distance 仍跟 samples 流，无需协同改）。
  val activeLapWithDistance: ActiveLap? = session.activeLap?.let { current ->
      val prev = session.samples.lastOrNull()
      if (prev != null) {
          current.copy(
              distanceMetersSinceStart = current.distanceMetersSinceStart +
                  haversineDistanceMeters(
                      prev.latitude, prev.longitude,
                      currentSample.latitude, currentSample.longitude,
                  ),
          )
      } else {
          current  // 理论不发生：activeLap 存在 → 开圈帧已入 samples（sampleStartIndex 守卫）
      }
  }
  ```

  补 import：`import com.blazepush.feature.test.usecase.haversineDistanceMeters`（同模块同包 file-level fun，可能不需 import；tasks 实施时按编译反馈决定）。
- [x] 3.2 **路径 (d) no target gate** 携带累距（`LapTimingEngine.kt:98`）：

  ```kotlin
  // 改前
  val targetGate = expectedGate(track, session.nextExpectedGateIndex)
      ?: return session.copy(samples = updatedSamples)

  // 改后
  val targetGate = expectedGate(track, session.nextExpectedGateIndex)
      ?: return session.copy(samples = updatedSamples, activeLap = activeLapWithDistance)
  ```
- [x] 3.3 **`handleSectorCrossing` 签名加参数**（`LapTimingEngine.kt:225-232`）：

  ```kotlin
  // 改前
  private fun handleSectorCrossing(
      session: LapSession,
      track: Track,
      previousSample: GpsSample,
      currentSample: GpsSample,
      updatedSamples: List<GpsSample>,
      targetGate: TimingGate,
  ): LapSession {

  // 改后
  private fun handleSectorCrossing(
      session: LapSession,
      track: Track,
      previousSample: GpsSample,
      currentSample: GpsSample,
      updatedSamples: List<GpsSample>,
      targetGate: TimingGate,
      activeLapWithDistance: ActiveLap?,  // A22 新增
  ): LapSession {
  ```

  同时更新调用点（`processSample` line 100-107）：传 `activeLapWithDistance = activeLapWithDistance`。
- [x] 3.4 **路径 (e) sector rejected** 携带累距（`handleSectorCrossing` line 287-289）：

  ```kotlin
  // 改前
  if (!expectedGateDetection.accepted) {
      return session.copy(samples = updatedSamples, crossingEvents = updatedEvents)
  }

  // 改后
  if (!expectedGateDetection.accepted) {
      return session.copy(
          samples = updatedSamples,
          crossingEvents = updatedEvents,
          activeLap = activeLapWithDistance,  // A22 携带累距
      )
  }
  ```
- [x] 3.5 **路径 (f) sector accepted** 用 `activeLapWithDistance.copy` 派生（`handleSectorCrossing` line 292-303）：

  ```kotlin
  // 改前
  return session.copy(
      samples = updatedSamples,
      nextExpectedGateIndex = session.nextExpectedGateIndex + 1,
      crossingEvents = updatedEvents,
      activeLap = activeLap.copy(  // ← 用了 session.activeLap（line 233 提取的本地 val）
          passedGateIds = activeLap.passedGateIds + targetGate.id,
          sectorEntries = activeLap.sectorEntries + SectorEntry(
              gateId = targetGate.id,
              crossedAtMillis = expectedCrossingMillis,
          ),
      ),
  )

  // 改后
  return session.copy(
      samples = updatedSamples,
      nextExpectedGateIndex = session.nextExpectedGateIndex + 1,
      crossingEvents = updatedEvents,
      activeLap = activeLapWithDistance!!.copy(  // A22 从 activeLapWithDistance 派生
          // !! 安全：进入此分支前 line 233 `session.activeLap ?: return` 已守卫
          passedGateIds = activeLapWithDistance.passedGateIds + targetGate.id,
          sectorEntries = activeLapWithDistance.sectorEntries + SectorEntry(
              gateId = targetGate.id,
              crossedAtMillis = expectedCrossingMillis,
          ),
      ),
  )
  ```

  注意：`activeLapWithDistance.passedGateIds` 与 `session.activeLap.passedGateIds` 同值（distanceMetersSinceStart 是 copy 时唯一变化字段），但 spec R3 (f) Scenario 锁死必须从 `activeLapWithDistance` 派生 —— 源码 grep 自检会确认 `handleSectorCrossing` sector accepted 分支不再有 `session.activeLap.copy(`。
- [x] 3.6 **路径 (b) 首次开圈** 显式 `distanceMetersSinceStart = 0.0`（`handleStartFinishCrossing` line 142-148）：

  ```kotlin
  activeLap = ActiveLap(
      lapIndex = 1,
      startedAtMillis = crossingMillis,
      passedGateIds = listOf(track.startFinishGate.id),
      sampleStartIndex = updatedSamples.lastIndex,
      distanceMetersSinceStart = 0.0,  // A22 显式写出（默认值即此，但 spec 要求显式）
  )
  ```
- [x] 3.7 **路径 (c) 闭圈后新开 lap** 显式 `distanceMetersSinceStart = 0.0`（`handleStartFinishCrossing` line 199-204）：

  ```kotlin
  activeLap = ActiveLap(
      lapIndex = nextLapIndex,
      startedAtMillis = crossingMillis,
      passedGateIds = listOf(track.startFinishGate.id),
      sampleStartIndex = updatedSamples.lastIndex,
      distanceMetersSinceStart = 0.0,  // A22 闭圈后 distance 重置（与首次开圈对称）
  )
  ```

  注意：路径 (c) closing active lap 不显式累入闭圈帧（design D3 决策），无需在 `handleStartFinishCrossing` 闭圈分支添加 closing lap distance 处理。
- [x] 3.8 **编译门槛**：`./gradlew :feature:test:compileDebugKotlin` BUILD SUCCESSFUL（证明 §3 BREAKING 连锁闭环；若仍红需检查 import / 调用点遗漏）。

---

## 4. UI `LapDebugExecutionScreen` 改读字段 + 删除孤立 import（D4）

- [x] 4.1 **`rememberStartFinishTimingCardState` 改读 engine 字段**（`LapDebugExecutionScreen.kt:197-237`）：

  ```kotlin
  // 改前 line 230-233
  currentLapDistanceLabel = formatDistanceMeters(
      calculateDistanceSince(lapSession?.samples.orEmpty(), latestAcceptedCrossing.timestampMillis)
  ),

  // 改后 line 230-233
  currentLapDistanceLabel = formatDistanceMeters(
      lapSession?.activeLap?.distanceMetersSinceStart ?: 0.0
  ),
  ```
- [x] 4.2 **删除 `calculateDistanceSince`**（`LapDebugExecutionScreen.kt:239-255`）：整个 private fun 删除。
- [x] 4.3 **删除 private `haversineDistanceMeters`**（`LapDebugExecutionScreen.kt:257-275`）：整个 private fun 删除（实现已迁到 §2 的 `GeoMath.kt`）。
- [x] 4.4 **删除孤立 `GpsSample` import**（`LapDebugExecutionScreen.kt:23` `import com.blazepush.feature.test.model.laptiming.GpsSample`）：删除。删后整个文件不再引用 `GpsSample`。
- [x] 4.5 **编译门槛**：`./gradlew :feature:test:compileDebugKotlin` BUILD SUCCESSFUL。

---

## 5. 测试段

### 5.1 现有 `LapTimingEngineTest` 追加 6 条 path coverage 测试（spec R3）

- [x] 5.1.1 **路径 (a) ts 回跳早退不累距**：`processSample_whenTsRegression_returnsSessionWithoutDistanceUpdate`

  - 构造 ActiveLap 已开圈、`distanceMetersSinceStart = 100.0`
  - 调 `processSample` 传 `currentSample.timestampMillis < previousSample.timestampMillis`
  - 断言返回 session 与入参完全相等（包括 `activeLap.distanceMetersSinceStart == 100.0`）
- [x] 5.1.2 **路径 (b) 首次开圈 distance = 0.0**：`processSample_firstStartFinishCrossing_initializesDistanceToZero`

  - 构造 `session.activeLap == null`
  - 喂 start-finish accepted 帧
  - 断言 `session.activeLap.lapIndex == 1`、`session.activeLap.distanceMetersSinceStart == 0.0`
- [x] 5.1.3 **路径 (c) 闭圈不累入闭圈帧 + 新 lap = 0.0**：`processSample_lapClosing_resetsToZeroForNewLap`

  - 构造 ActiveLap (lap 1) `distanceMetersSinceStart = 4500.0`
  - 喂 start-finish accepted 闭圈帧
  - 断言 `session.activeLap.lapIndex == 2`、`session.activeLap.distanceMetersSinceStart == 0.0`
  - 断言 `session.completedLaps.size == 1`，且 `LapRecord` data class 字段集**未**含 `distanceMeters` / `distanceMetersSinceStart`（编译期通过 spec R6 锁定，此测试只验证逻辑路径）
- [x] 5.1.4 **路径 (d) no target gate 携带累距**：`processSample_noTargetGate_carriesDistanceForward`

  - 构造 ActiveLap 已开圈 `distanceMetersSinceStart = 100.0`、`session.nextExpectedGateIndex` 超出 `track.sectorGates.size`
  - 喂一帧 prev→current 距离已知（构造为约 5.0 米的合理坐标差，`assertEquals(105.0, …, delta = 0.1)`）
  - 断言 `session.activeLap.distanceMetersSinceStart` 在 `100.0 + expected` 容差内
- [x] 5.1.5 **路径 (e) sector rejected 携带累距**：`processSample_sectorRejected_carriesDistanceForward`

  - 构造 ActiveLap 已开圈 `distanceMetersSinceStart = 200.0`
  - 喂一帧 detector 对 expected sector gate 给出 `accepted = false` 的 sample
  - 断言 `session.activeLap.distanceMetersSinceStart` 增量 ≈ 实际相邻坐标 haversine 距离
- [x] 5.1.6 **路径 (f) sector accepted 累距 + sectorEntries 推进**：`processSample_sectorAccepted_accumulatesDistanceAndAdvancesSector`

  - 构造 ActiveLap 已开圈 `distanceMetersSinceStart = 300.0`、`passedGateIds = [start-finish]`
  - 喂一帧穿 s1 sector 门 accepted 的 sample
  - 断言 `session.activeLap.distanceMetersSinceStart` 增量正确
  - 断言 `session.activeLap.passedGateIds.last() == "s1"`
  - 断言 `session.activeLap.sectorEntries.size == 1`

### 5.2 `LapTimingEngineTest` 追加距离来源契约 + 单调性测试（spec R1 + R2）

- [x] 5.2.1 **distance 单调不减**：`activeLap_distanceMetersSinceStart_monotonicallyNonDecreasing`

  - ActiveLap 开圈后连续喂 N 帧 GpsSample
  - 收集每次 `processSample` 返回的 `session.activeLap.distanceMetersSinceStart` 序列
  - 断言序列单调不减（`zipWithNext().all { (a, b) -> b >= a }`）

### 5.3 迁移 `LapDebugExecutionScreenStateTest` 旧 distance 测试（**Review v1 P1-1 修补**）

A22 后 UI 不再自算 distance（不再走 `samples.zipWithNext()` haversine），改读 `activeLap.distanceMetersSinceStart`。`LapDebugExecutionScreenStateTest:217-223` 的 `activeLap(...)` helper 默认不传 `distanceMetersSinceStart` → 新字段 default 0.0 → line 92 `"14.7 m"` 与 line 121 `"32.4 m"` 两条旧断言会红。

旧测试本意是验证 UI **display label 渲染正确**（功能等价），A22 后仍需保留这层契约，只是 distance 来源从"UI 算出来"变成"engine 喂进来"。

- [x] 5.3.0a **`activeLap(...)` helper 加 `distanceMetersSinceStart` 参数**（`LapDebugExecutionScreenStateTest.kt:217-223`）：

  ```kotlin
  // 改前
  private fun activeLap(startedAtMillis: Long, sampleStartIndex: Int) = ActiveLap(
      lapIndex = 0,
      startedAtMillis = startedAtMillis,
      passedGateIds = emptyList(),
      sectorEntries = emptyList(),
      sampleStartIndex = sampleStartIndex,
  )

  // 改后（A22 兼容：default 0.0 让其他不关心 distance 的旧测试零迁移）
  private fun activeLap(
      startedAtMillis: Long,
      sampleStartIndex: Int,
      distanceMetersSinceStart: Double = 0.0,  // A22 新增参数
  ) = ActiveLap(
      lapIndex = 0,
      startedAtMillis = startedAtMillis,
      passedGateIds = emptyList(),
      sectorEntries = emptyList(),
      sampleStartIndex = sampleStartIndex,
      distanceMetersSinceStart = distanceMetersSinceStart,
  )
  ```
- [x] 5.3.0b **迁移旧测试 1 传 14.7m**（`LapDebugExecutionScreenStateTest.kt:85`，`rememberStartFinishTimingCardState_withFirstAcceptedStartFinishCrossing_*` 测试）：

  ```kotlin
  // 改前
  activeLap = activeLap(startedAtMillis = 3_000L, sampleStartIndex = 0)
  // line 92 断言 "14.7 m" 期待 UI 自行 haversine 算出

  // 改后
  activeLap = activeLap(
      startedAtMillis = 3_000L,
      sampleStartIndex = 0,
      distanceMetersSinceStart = 14.7,  // A22：UI 改读 engine 字段，由 helper 传入预期值
  )
  // line 92 断言 "14.7 m" 仍成立（功能等价：display label 渲染契约不变）
  ```
- [x] 5.3.0c **迁移旧测试 2 传 32.4m**（`LapDebugExecutionScreenStateTest.kt:114`，`rememberStartFinishTimingCardState_withSecondAcceptedStartFinishCrossing_*` 测试）：

  ```kotlin
  // 改后
  activeLap = activeLap(
      startedAtMillis = 32_533_000L,
      sampleStartIndex = 1,
      distanceMetersSinceStart = 32.4,  // A22 同上
  )
  ```

  注意：这两条旧测试在 A22 实施前是 v1 隐式契约（UI 全量 haversine + samples 输入决定 label），实施后变 v2 显式契约（engine 字段决定 label）。**这是合理的 contract 迁移**，不是测试退化。

### 5.4 `LapDebugExecutionScreenStateTest` 追加 UI consumer + 性能 smoke

- [x] 5.4.1 **UI 读 engine 字段**：`rememberStartFinishTimingCardState_readsEngineDistanceField`

  - 构造 LapSession 含 `activeLap.distanceMetersSinceStart = 1234.5`
  - 调 `rememberStartFinishTimingCardState(session, isTimeSynced = true)`
  - 断言返回 `StartFinishTimingCardState.currentLapDistanceLabel == formatDistanceMeters(1234.5)`
- [x] 5.4.2 **7500 samples 性能 smoke < 16ms**：`rememberStartFinishTimingCardState_with7500Samples_completesUnder16msMedian`

  - 构造 helper `buildSessionWith7500SamplesAndDistanceMetersSinceStart()` 返回 LapSession 含 7500 GpsSample 与一个 `activeLap.distanceMetersSinceStart = 任意预填值`
  - **三层防抖**：warm-up 10x → 5 次外层 measure（每次内 loop 10x，取均值 ns / 10）→ 5 个均值 sorted 取中位
  - 断言中位数 < 16,000,000 ns (= 16ms)
  - 用 `System.nanoTime()`（非 `currentTimeMillis()`）

  ```kotlin
  @Test
  fun rememberStartFinishTimingCardState_with7500Samples_completesUnder16msMedian() {
      val session = buildSessionWith7500SamplesAndDistanceMetersSinceStart()

      // warm-up
      repeat(10) { rememberStartFinishTimingCardState(session, isTimeSynced = true) }

      // measure
      val measuredNsPerCall = (1..5).map {
          val start = System.nanoTime()
          repeat(10) { rememberStartFinishTimingCardState(session, isTimeSynced = true) }
          (System.nanoTime() - start) / 10
      }.sorted()
      val medianNs = measuredNsPerCall[2]
      val medianMs = medianNs / 1_000_000.0

      assertTrue(
          "7500 samples median ${medianMs}ms 应 < 16ms（60fps 帧预算）。" +
              "硬区分 v1：v1 全量 haversine 7500 × 4 trig + filter ≈ 37500 ops 接近或超阈值；" +
              "v2 单字段读 O(1) 预期 < 1ms，留 16x 间隙",
          medianMs < 16.0,
      )
  }
  ```

### 5.5 源码 grep 自检测试（机器核销契约）

放入 `LapDebugExecutionScreenStateTest` 或新建 `ActiveLapDistanceAccumulatorSourceAssertionTest`（参考 Round 1 战役 F `FileLoggerTest` 源码断言写法）。

- [x] 5.5.1 **UI 源码不再含 `calculateDistanceSince` / 私有 `haversineDistanceMeters`**：

  ```kotlin
  @Test
  fun ui_sourceDoesNotContainDistanceCalculationFunctions() {
      val source = File("src/main/java/com/blazepush/feature/test/ui/screen/LapDebugExecutionScreen.kt").readText()
      assertFalse("UI 不应含 calculateDistanceSince（A22 已删）", source.contains("calculateDistanceSince"))
      assertFalse("UI 不应含 private fun haversineDistanceMeters（A22 已迁到 GeoMath）",
          source.contains("private fun haversineDistanceMeters"))
  }
  ```
- [x] 5.5.2 **UI 源码不再做 distance pattern 计算**：

  ```kotlin
  @Test
  fun ui_sourceDoesNotContainDistancePatternCalculations() {
      val source = File("src/main/java/com/blazepush/feature/test/ui/screen/LapDebugExecutionScreen.kt").readText()
      assertFalse("UI 不应含 samples.zipWithNext（O(N) distance pattern）",
          source.contains("samples.zipWithNext"))
      // samples.lastOrNull()?.timestampMillis 仍合法（O(1) 单帧 ts，非距离 pattern），不在断言范围
      val patternRegex = Regex("""samples\.filter \{[^}]*timestampMillis""")
      assertFalse("UI 不应含 samples.filter { … timestampMillis … } distance pattern",
          patternRegex.containsMatchIn(source))
  }
  ```
- [x] 5.5.3 **engine `handleSectorCrossing` 用 `activeLapWithDistance!!.copy(` 派生 + 反向禁止旧本地变量 `activeLap.copy(`**（**Review v1 P1-2 修补**：旧代码本就用本地 `val activeLap = session.activeLap ?: return ...` + `activeLap.copy(` 派生 sector accepted，`session.activeLap.copy(` 字面量从未存在；原 v1 grep 是假绿。改为正向 + 反向双断言）：

  ```kotlin
  @Test
  fun engine_handleSectorCrossing_sourceUsesActiveLapWithDistanceCopyAndForbidsLocalActiveLapCopy() {
      val source = File("src/main/java/com/blazepush/feature/test/usecase/LapTimingEngine.kt").readText()
      val handleSectorStart = source.indexOf("private fun handleSectorCrossing")
      assertTrue("handleSectorCrossing 函数应存在", handleSectorStart > 0)
      val handleSectorBody = source.substring(
          handleSectorStart,
          source.length.coerceAtMost(handleSectorStart + 4000),
      )

      // 正向断言：sector accepted 分支必须用 activeLapWithDistance!!.copy(...) 派生
      assertTrue(
          "handleSectorCrossing 必须含 activeLapWithDistance!!.copy( 用于 sector accepted 派生",
          handleSectorBody.contains("activeLapWithDistance!!.copy("),
      )
      assertTrue(
          "handleSectorCrossing 必须有 activeLapWithDistance: ActiveLap? 参数",
          handleSectorBody.contains("activeLapWithDistance: ActiveLap?"),
      )

      // 反向禁止：旧代码用本地 `val activeLap = session.activeLap ?: return` + `activeLap.copy(`
      // 派生 sector accepted。A22 后该本地 val 不应再被用作 copy 派生源（必须从 activeLapWithDistance 走）。
      // 用 word boundary `\bactiveLap\.copy\(` 避免误命中 `activeLapWithDistance.copy(`。
      val forbiddenLocalActiveLapCopy = Regex("""\bactiveLap\.copy\(""")
      assertFalse(
          "handleSectorCrossing 不应再用本地 `activeLap.copy(` 派生 sector accepted（A22 必须走 activeLapWithDistance!!.copy）",
          forbiddenLocalActiveLapCopy.containsMatchIn(handleSectorBody),
      )

      // 防御性同步禁止 session.activeLap.copy(（虽然旧代码本就不含此字面量，但以防 future 重构引入）
      assertFalse(
          "handleSectorCrossing 不应用 session.activeLap.copy(",
          handleSectorBody.contains("session.activeLap.copy("),
      )
  }
  ```

  **关键修补**：原 v1 只 grep `session.activeLap.copy(`，但旧代码 line 296 用本地变量 `activeLap.copy(...)`（line 233 提取 `val activeLap = session.activeLap ?: return ...`）—— 旧代码本就不含 `session.activeLap.copy(` 字面量，原 v1 永真假绿。新版**正向**要求 `activeLapWithDistance!!.copy(` 出现 + **反向**用 word boundary `\bactiveLap\.copy\(` 禁止本地变量 copy（`\b` 避免误命中 `activeLapWithDistance.copy`）。
- [x] 5.5.4 **`distanceMetersSinceStart` 写入仅在 engine + ActiveLap 字段定义**：

  ```kotlin
  @Test
  fun distanceMetersSinceStart_writtenOnlyByEngineAndDataClassDefault() {
      // grep `distanceMetersSinceStart\s*=` 在 feature/test/src/main 应仅命中
      // ActiveLap.kt（字段 default）+ LapTimingEngine.kt（producer 写入）
      val mainDir = File("src/main/java/com/blazepush/feature/test")
      val violations = mainDir.walk()
          .filter { it.isFile && it.extension == "kt" }
          .filter { f ->
              val name = f.absolutePath
              !name.endsWith("ActiveLap.kt") && !name.endsWith("LapTimingEngine.kt")
          }
          .filter { f -> Regex("""distanceMetersSinceStart\s*=""").containsMatchIn(f.readText()) }
          .map { it.name }
          .toList()
      assertTrue(
          "distanceMetersSinceStart 写入仅应位于 ActiveLap.kt（default）+ LapTimingEngine.kt（producer），" +
              "实际命中其他文件：$violations",
          violations.isEmpty(),
      )
  }
  ```
- [x] 5.5.5 **A56 边界：本 change diff 新增行不引入 Room/@Entity/@Dao/@Database/RoomDatabase/chunkWrite/persistDistance/@Insert/@Query**：

  此 grep 不适合写成 unit test（依赖 git）；改放 §6 合流门槛手动 verify（见 §6.5）。

### 5.6 测试门槛

- [x] 5.6.1 `./gradlew :feature:test:testDebugUnitTest --tests "*LapTimingEngineTest*"` 全绿（含 6 条 path coverage + 1 条单调性）
- [x] 5.6.2 `./gradlew :feature:test:testDebugUnitTest --tests "*LapDebugExecutionScreenStateTest*"` 全绿（含 helper 迁移 + UI consumer + 7500 smoke + grep 自检）

---

## 6. 合流门槛（non-negotiable）

- [x] 6.1 **Spec 验证**：`openspec validate fix-active-lap-distance-accumulator --strict` 返回 `Change ... is valid`
- [x] 6.2 **`feature:test` 全测绿**：`./gradlew :feature:test:testDebugUnitTest`
- [x] 6.3 **下游零回归**：
  - `./gradlew :core:bluetooth:testDebugUnitTest`（不涉及 parser）
  - `./gradlew :core:domain:test`（仅 domain layer test，本 change 不动 core/domain）
  - `./gradlew :app:compileDebugKotlin`
- [x] 6.4 **E2E 契约**：`./gradlew :feature:test:testDebugUnitTest --tests "*EndToEndLapTimingContractTest*"` 全绿（验证 LapSession.samples / ActiveLap 行为不回归）
- [x] 6.5 **A56 边界手动 grep verify**（spec R6 Scenario 2）：

  ```bash
  git diff <baseline-commit>..HEAD -- 'feature/test/**' 'core/**' 'app/**' \
    | grep -E "^\+" | grep -v "^\+\+\+" \
    | grep -E "@Entity\b|@Dao\b|@Database\b|RoomDatabase\b|chunkWrite|persistDistance|@Insert\b|@Query\b"
  ```

  预期零命中（运行期派生状态边界，本 round 不引入持久化 / Room schema）
- [x] 6.6 **backlog A22 迁 🟢 `pending_review`**：`docs/superpowers/reviews/attack-backlog.md` 一节 `🔴 pending` 删除 A22 条目，三节 `🟢 pending_review` 新增 A22 条目 + 核销成果块（覆盖修订后的条件 (3) 与 (5)：engine 是唯一 producer / 7500 smoke < 16ms / 5 路径全覆盖 / A56 边界 / UI 源码零残留），附录表格状态列同步
- [x] 6.7 **backlog 迁档 grep 自检**：`grep -nE "^### A22\b|\| A22 \|"` 应只命中 🟢 节 + 附录两处，🔴 节零命中

---

## 7. Commit 策略

本 change scope 中等（model + engine + UI + 测试 4 层，BREAKING 连锁），**1 个代码 commit**：

- [x] 7.1 **commit**：`fix(perf): 战役 F Round 3 A22 ActiveLap.distanceMetersSinceStart engine 增量累积 + UI 改读字段`

  body 要点：
  - **A22 model**：`ActiveLap` 加 `distanceMetersSinceStart: Double = 0.0`（第 6 字段，default 让现有调用编译通过）
  - **A22 engine**：`LapTimingEngine.processSample` 顶部集中构造 `activeLapWithDistance`，`session.samples.lastOrNull()` 与 `currentSample` 累加 haversine；5 类返回路径全部携带：(a) ts 回跳不动 / (b) 首次开圈 = 0 / (c) 闭圈不显式累入闭圈帧 + 新 lap = 0 / (d) no target gate 携带 / (e) sector rejected 携带 / (f) sector accepted 用 `activeLapWithDistance!!.copy(...)` 派生（不走 `session.activeLap.copy`）；`handleSectorCrossing` 签名加 `activeLapWithDistance: ActiveLap?` 参数；`handleStartFinishCrossing` 签名不变
  - **A22 UI**：`LapDebugExecutionScreen.rememberStartFinishTimingCardState` 改读 `lapSession?.activeLap?.distanceMetersSinceStart ?: 0.0`；删除 `calculateDistanceSince` + private `haversineDistanceMeters` + 孤立 `GpsSample` import
  - **新增** `feature/test/src/main/java/com/blazepush/feature/test/usecase/GeoMath.kt` 含 `internal fun haversineDistanceMeters`（从 UI 私有迁移，公式不变）
  - **测试**：`LapTimingEngineTest` 新增 6 path scenarios + 1 单调性 / `LapDebugExecutionScreenStateTest` 新增 UI consumer + 7500 samples < 16ms median smoke（warm-up 10x + measure 5 次取中位 + 内 loop 10x avg + System.nanoTime 三层防抖）+ 4 条源码 grep 自检
  - **A22 backlog 核销条件 (3) 与 (5) 修订**已在 proposal v2 申请并落入 backlog（commit ebaf394 之后）；本 commit 完成实施
  - 合流门槛：`openspec validate --strict` / `:feature:test:testDebugUnitTest` / 下游 `:core:bluetooth :core:domain :app` / E2E 契约 全绿 / A56 边界 diff 新增行 grep 零命中

  格式约束：
  - Conventional Commits
  - body 含 "A22" 便于 grep
  - Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
  - **kt-check**：若触发 legacy 违规，按战役 G B 方案纪律评估加 `// @IgnoreFormatCheck` 或精确修到位；本 change 改动面（4 个文件 + 1 新建）legacy 违规面应远小于 Round 1
- [x] 7.2 **commit 后回填 backlog 附录表格 commit 号**：A22 行的 `{pending commit}` 占位符替换成实际 commit hash
