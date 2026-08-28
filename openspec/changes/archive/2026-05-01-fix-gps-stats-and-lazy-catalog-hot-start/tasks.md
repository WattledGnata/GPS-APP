# 实施任务（依赖顺序）

本 change 按 2 个 Capability 组织：

1. **§1-§2 A28 / gps-runtime-stats**：`GpsDataViewModel` 内部改造（接口零破坏）
2. **§3-§5 A37 / track-catalog-hot-start**：`TrackCatalog` 接口 **BREAKING** suspend 化 + 所有现有调用点迁移

两个 Capability 代码域正交（ViewModel 字段 vs Repository 接口），可同 commit 实施但 §3 是 BREAKING，必须全部调用点同步迁移后才能编译通过。

参考 `proposal.md` / `design.md` / `specs/gps-runtime-stats/spec.md` / `specs/track-catalog-hot-start/spec.md`。

---

## 0. grep 预检（已核实，作为实施依据存档）

- [x] 0.1 **预置 catalog 文件名**：文件 `feature/test/src/main/java/com/blazepush/feature/test/repository/PresetTracks.kt`，类名 `PresetTrackCatalog`（文件与类名不同，不要误改）
- [x] 0.2 **`dataCount` 字段存在性**：`GpsDataViewModel.kt:63 `private var dataCount = 0`；line 99 `dataCount++`；line 165 `dataCount = 0`——确认删除清单完整
- [x] 0.3 **`getAllTracks()` 调用点清单**（全部需要迁移为 suspend / runTest）：
  - 生产：`TestSessionViewModel.kt:80`（构造期同步调 → 改 viewModelScope.launch）
  - 生产：`PresetTracks.kt:75`（override，改 suspend fun）
  - 生产：`ReplayAlignedTrackCatalog.kt:43,44`（override + 内部 fallback 调用，改 suspend fun + withContext）
  - 测试：`TrackCatalogTest.kt:16`（改 runTest）
  - 测试：`ReplayAlignedTrackCatalogTest.kt:177`（改 runTest）
- [x] 0.4 **`TrackCatalog` 实现清单**：`PresetTrackCatalog`（生产）+ `ReplayAlignedTrackCatalog`（生产）+ `TestSessionViewModelTrackLapTest.runtimeTrackCatalog()` helper（测试，line 327）—— 需要一并对齐 suspend 签名

---

## 1. A28 · `GpsDataViewModel` 内部重写

- [x] 1.1 **删除 `_dataStats` API 相关幻影字段不存在性验证**：grep `_dataStats` / `val dataStats` / `StateFlow<DataStats>` 在 `GpsDataViewModel.kt` 应**零命中**（v2 spec 已禁止扩大 scope，防止起草错位）
- [x] 1.2 **重写 `updateDataStats(data)`**（`GpsDataViewModel.kt:82-120`）：

  ```kotlin
  private fun updateDataStats(data: GpsData) {
      val now = System.currentTimeMillis()

      // 数据年龄（保持现有口径）
      val dataAge = if (data.timestamp > 0) now - data.timestamp else now - lastDataTime
      lastDataTime = now

      // A28 frequency：直接透传 parser 1 秒滑窗结果（design D1 / spec R1）
      val frequency = data.frequency

      // A28 packetLoss：调用纯函数计算（见 1.2b，Review v1 P2-1 修补：测试确定性）
      val packetLossRate = computePacketLossRate(dataAge = dataAge, frequency = data.frequency)

      val stats = DataStats(
          dataAge = dataAge,
          packetLossRate = packetLossRate,
          frequency = frequency,
      )
      _dataQuality.value = dataQualityEvaluator.calculateQuality(data, stats)
  }
  ```

  注意：`DataStats` 作为局部值传入 evaluator，**不暴露为 StateFlow**；evaluator 内部把 `stats.frequency` / `stats.packetLossRate` 反映到 `DataQuality.frequency` / `DataQuality.packetLoss`（字段名差异由 domain 层 data class 定，本 change 不改）。
- [x] 1.2b **新增纯函数 `computePacketLossRate`**（Review v1 P2-1 修补：测试确定性，避免 `System.currentTimeMillis()` 毫秒漂移污染断言）：

  ```kotlin
  // 放在 GpsDataViewModel 内 companion object 或 file-private fun
  // 纯函数语义（无副作用 / 可确定性测试）：从 data.frequency 反推期望采样周期
  // frequency ≤ 0 为暖启动 / 丢连，回退 0 避免 NaN / 除零 / 冷启动误告警
  @androidx.annotation.VisibleForTesting
  internal fun computePacketLossRate(dataAge: Long, frequency: Double): Double {
      val expectedSampleInterval = if (frequency > 0.0) 1000.0 / frequency else 0.0
      return if (expectedSampleInterval > 0.0 && dataAge > expectedSampleInterval * 2) {
          ((dataAge - expectedSampleInterval) / expectedSampleInterval).coerceIn(0.0, 100.0)
      } else {
          0.0
      }
  }
  ```

  **测试策略**：§2.3 直接对纯函数断言，精确无容差（不依赖 `System.currentTimeMillis()`）；`updateDataStats` 集成路径的 dataAge 容差由 §2.2 覆盖（只断言透传成立，不断言 packetLoss 精确值）。
- [x] 1.3 **删除 `dataCount` 相关字段 + 初始化**：
  - 删除 `private var dataCount = 0`（line 63）
  - 删除 `private var dataCountStartTime = 0L`（line 64）
  - 若文件内 `dataCount++` / `dataCountStartTime = now` 的任何赋值还残留，一并删净
- [x] 1.4 **裁剪 `resetStats()`**（line 164-169）：

  ```kotlin
  fun resetStats() {
      lastDataTime = 0L
      _dataQuality.value = DataQuality.Empty   // 直接回到 companion 初始态（DataQuality.kt:49-59）
  }
  ```

  注意：`DataQuality.Empty` 已存在，不需新增；`dataCount / dataCountStartTime` 已在 1.3 删除，reset body 对齐。
- [x] 1.5 **`init` block 加 DISCONNECTED 订阅链**（Review v1 P2-2 修补）：

  **代码真相**：
  - ViewModel 已有 `val gpsData: StateFlow<GpsData> = gpsDataRepository.gpsDataFlow`（line 32）
  - 现有 init 用 `viewModelScope.launch { gpsData.collect { ... } }` pattern（line 68-75）
  - `connectionState: StateFlow<ConnectionState>` 已是现有字段（line 34，`stateIn` 包装）

  **改造策略**：**现有 `viewModelScope.launch { gpsData.collect { ... } }` 保持不动**（最小改动 + 与现有风格一致）；**只新增一个并列的 launch** 做 DISCONNECTED 订阅：

  ```kotlin
  init {
      // 现有：gpsData collect 保持不变（line 68-76 原样，updateDataStats 被 §1.2 重写）
      viewModelScope.launch {
          gpsData.collect { data ->
              Log.d(/* 现有 log 保留 */)
              updateDataStats(data)
          }
      }

      // A28 新增：DISCONNECTED → resetStats（design D1 / spec R3）
      viewModelScope.launch {
          connectionState
              .distinctUntilChanged()
              .filter { it == ConnectionState.DISCONNECTED }
              .collect { resetStats() }
      }
  }
  ```

  import 补齐若缺：`kotlinx.coroutines.flow.distinctUntilChanged` + `kotlinx.coroutines.flow.filter`（`collect` 已有，不用新加 `onEach` / `launchIn`）。
- [x] 1.6 **编译门槛**：`./gradlew :feature:test:compileDebugKotlin` BUILD SUCCESSFUL，无 unresolved reference / 字段残留编译错误。

---

## 2. A28 · 测试段

- [x] 2.1 **新增 `GpsDataViewModelTest.kt` 或对齐现有 test 文件**（路径 `feature/test/src/test/java/com/blazepush/feature/test/viewmodel/GpsDataViewModelTest.kt`）。若文件不存在则新建；若已存在则追加 3 个 @Test。
- [x] 2.2 **测试 1：`frequency_transparentlyMirrorsLatestParserFrequency`**（R1 Scenario "稳态 25Hz 透传" + "低频帧立即透传"，集成测试，**不精确断言 packetLoss**——那由 §2.3 纯函数测试负责）：

  - 构造 ViewModel（注入 fake `GpsDataRepository` 可 emit `GpsData` + 可 emit `ConnectionState`）
  - 连续喂 30 帧 `GpsData(frequency = 25.0, ...)`（runTest 推进）
  - 再喂 1 帧 `GpsData(frequency = 1.0, ...)`
  - 断言 `viewModel.dataQuality.value.frequency == 1.0`（硬区分 v1 累计平均会显示 > 10.0）
  - **不**断言 `dataQuality.value.packetLoss` 精确值（`System.currentTimeMillis()` 不确定性由 §2.3 纯函数路径规避）
- [x] 2.3 **测试 2：纯函数 `computePacketLossRate` 精确断言**（R2 Scenarios "25Hz 稳态 / 25Hz 超阈值 / 10Hz 低频 / 暖启动回退"）：

  **Review v1 P2-1 修补**：直接对 §1.2b 纯函数做 input-output 精确断言，不经过 `updateDataStats` 的 `System.currentTimeMillis()` 路径，避免毫秒漂移污染。用 `assertEquals(expected, actual, delta=0.0001)` 确保浮点稳定：

  ```kotlin
  @Test
  fun computePacketLossRate_returnsZero_whenFrequencyIsZero() {
      assertEquals(0.0, computePacketLossRate(dataAge = 9999L, frequency = 0.0), 0.0001)
  }

  @Test
  fun computePacketLossRate_25HzSteady_30ms_returnsZero() {
      // expectedSampleInterval = 40ms, dataAge=30 < 80 阈值
      assertEquals(0.0, computePacketLossRate(dataAge = 30L, frequency = 25.0), 0.0001)
  }

  @Test
  fun computePacketLossRate_25Hz_200ms_returns4_0_hardDiscriminatesV1() {
      // v2: (200-40)/40 = 4.0
      // v1 硬编码 expectedInterval=100L 在 25Hz 同场景：(200-100)/100 = 1.0
      // 两者差 4x，断裂点足够硬
      assertEquals(4.0, computePacketLossRate(dataAge = 200L, frequency = 25.0), 0.0001)
  }

  @Test
  fun computePacketLossRate_10HzLowFreq_300ms_returns2_0() {
      // expectedSampleInterval = 100ms, (300-100)/100 = 2.0
      assertEquals(2.0, computePacketLossRate(dataAge = 300L, frequency = 10.0), 0.0001)
  }
  ```

  这 4 条测试完整覆盖 R2 所有 Scenario，且**零时间源依赖**。
- [x] 2.4 **测试 3：`resetStats_onConnectionStateDisconnected_clearsQuality`**（R3 Scenario "DISCONNECTED 触发 resetStats"）：

  - 喂若干帧让 `dataQuality.value.frequency > 0`
  - 通过 fake repository 发射 `ConnectionState.DISCONNECTED`
  - runTest advance
  - 断言 `viewModel.dataQuality.value == DataQuality.Empty`
- [x] 2.5 **测试门槛**：`./gradlew :feature:test:testDebugUnitTest --tests "*GpsDataViewModelTest*"` 全绿。

---

## 3. A37 · `TrackCatalog` 接口 + 两个实现改造（BREAKING）

> **注意**：§3 是 BREAKING 连锁编译失败，必须 3.1 → 3.2 → 3.3 → 3.4 一口气做完才能再次编译通过。中间步骤会红。

- [x] 3.1 **接口签名改 suspend**（`TrackCatalog.kt`）：

  ```kotlin
  interface TrackCatalog {
      suspend fun getAllTracks(): List<Track>
      fun getTrack(trackId: String): Track?   // 保持同步（design D4 / spec R1）
  }
  ```

- [x] 3.2 **`PresetTrackCatalog.getAllTracks` 对齐 suspend**（`PresetTracks.kt:75`）：

  ```kotlin
  override suspend fun getAllTracks(): List<Track> = presetTracks  // 直返，无 withContext（spec R2）
  ```

- [x] 3.3 **`ReplayAlignedTrackCatalog` 改造**（`ReplayAlignedTrackCatalog.kt:29-52`）：
  - 删除 `private val replayAlignedTrack: Track? by lazy { ... }`（line 34-41）
  - 新增显式缓存字段：

    ```kotlin
    @Volatile private var cachedReplayTrack: Track? = null
    @Volatile private var cacheInitialized = false
    ```

  - `getAllTracks()` 改为 suspend fun + 顶层 `withContext(Dispatchers.IO)`（design D5 / spec R3）：

    ```kotlin
    override suspend fun getAllTracks(): List<Track> = withContext(Dispatchers.IO) {
        ensureReplayTrackLoaded()
        val fallbackTracks = fallbackCatalog.getAllTracks().filterNot { it.id == TFIC_TRACK_ID }
        val replayTrack = cachedReplayTrack ?: fallbackCatalog.getTrack(TFIC_TRACK_ID)
        if (replayTrack != null) fallbackTracks + replayTrack else fallbackTracks
    }
    ```

    注意：`fallbackCatalog.getAllTracks()` 是 suspend 调用（因为 fallback 也是 `TrackCatalog` 实现），所以必须在 `withContext(Dispatchers.IO)` 的 coroutine scope 内调用——签名已对齐。
  - `getTrack(trackId)` 改造为**冷缓存降级**（spec R4）：

    ```kotlin
    override fun getTrack(trackId: String): Track? {
        if (trackId != TFIC_TRACK_ID) return fallbackCatalog.getTrack(trackId)
        // 冷缓存：未调过 getAllTracks，直接走 fallback 降级，不触发 asset IO
        return if (cacheInitialized) {
            cachedReplayTrack ?: fallbackCatalog.getTrack(trackId)
        } else {
            fallbackCatalog.getTrack(trackId)
        }
    }
    ```

  - 新增 `ensureReplayTrackLoaded` 双检锁（design D5）：

    ```kotlin
    private fun ensureReplayTrackLoaded() {
        if (cacheInitialized) return
        synchronized(this) {
            if (cacheInitialized) return
            cachedReplayTrack = runCatching {
                buildReplayAlignedTrack(
                    replayJson = replayTrackSource.loadReplayJson(),
                    vbo = replayTrackSource.loadTrackVbo(),
                )
            }.getOrNull()
            cacheInitialized = true
        }
    }
    ```

  - `buildReplayAlignedTrack` / `parseReplaySamples` / data class 保持不变。
- [x] 3.4 **import 补齐**（`ReplayAlignedTrackCatalog.kt` 顶部）：
  - 新增 `import kotlinx.coroutines.Dispatchers`
  - 新增 `import kotlinx.coroutines.withContext`
- [x] 3.5 **（跳过中间 compile 门槛）**：BREAKING 改 `TrackCatalog` 签名后，`TestSessionViewModel.kt:80` 同步 `trackCatalog.getAllTracks()` 会立即红。此时 **MUST NOT** 跑 `:feature:test:compileDebugKotlin`（必失败，不是异常）——继续到 §4 迁移消费方后一并编译。Review v1 P1-1 修补：真正 compile 门槛挪到 §4.2。

---

## 4. A37 · 消费方 `TestSessionViewModel` 异步加载

- [x] 4.1 **`_availableTracks` 初始化改空列表 + async 加载**（`TestSessionViewModel.kt:80`）：

  ```kotlin
  private val _availableTracks = MutableStateFlow<List<Track>>(emptyList())
  val availableTracks: StateFlow<List<Track>> = _availableTracks.asStateFlow()
  ```

  init block 内（现有 init 已存在相应位置）加一行：

  ```kotlin
  init {
      // ... 现有逻辑 ...
      viewModelScope.launch {
          _availableTracks.value = trackCatalog.getAllTracks()   // catalog 自负 IO（design D6），此处不指定 Dispatchers.IO
      }
  }
  ```

  import 若缺，补 `kotlinx.coroutines.launch`。
- [x] 4.2 **编译门槛**：`./gradlew :feature:test:compileDebugKotlin` BUILD SUCCESSFUL。

---

## 5. A37 · 测试段

- [x] 5.1 **更新 `TrackCatalogTest.kt:16`**：`catalog.getAllTracks().map { it.id }` 调用所在的 `@Test fun` 加 `runTest { ... }` 包裹（import `kotlinx.coroutines.test.runTest`）。
- [x] 5.2 **更新 `ReplayAlignedTrackCatalogTest.kt:177`** 同样用 `runTest { }`（`getAllTracks_exposesReplayAlignedTrackToRuntimeSelection`）。
- [x] 5.2b **迁移 3 条依赖 replay-aligned getTrack 的旧测试**（Review v1 P1-2 修补）：新契约下**冷缓存** `getTrack(TFIC)` 走 fallback 降级、不再触发 asset IO，这 3 条原本靠 `by lazy` 首次访问触发 IO 的测试**会立刻断言失败**。迁移方式：每条测试顶部加 `runTest { catalog.getAllTracks() }` 先 **warm cache**，再做原同步 `getTrack` 断言（断言本身不变，因为热缓存命中返回 replay-aligned 版）：
  - `generatedStartFinishGate_acceptsReplayOpeningCrossingSamples`（line 51-70）
  - `generatedTrack_reusesCorrectedTficGateGeometry`（line 106-144）
  - `getTrack_buildsGeneratedTficTrackFromReplayAssets`（line 147-165）

  示例改造模板：

  ```kotlin
  @Test
  fun getTrack_buildsGeneratedTficTrackFromReplayAssets() = runTest {
      val catalog = ReplayAlignedTrackCatalog(
          replayTrackSource = object : ReplayTrackSource { /* ... */ },
          fallbackCatalog = PresetTrackCatalog()
      )

      // warm cache：唯一会触发 replay asset IO 的入口是 getAllTracks（spec R4 契约）
      catalog.getAllTracks()

      // warm 后 getTrack 命中缓存，返回 replay-aligned 版，原断言成立
      val track = requireNotNull(catalog.getTrack("preset-tfic-lpcc"))
      assertEquals(TrackSource.Generated, track.source)
      assertEquals("REAL_TRACK_REPLAY", track.layoutName)
      // ... 其他断言不变
  }
  ```
- [x] 5.3 **更新 `TestSessionViewModelTrackLapTest.kt:327 runtimeTrackCatalog()`** 的 TrackCatalog 实现（若是匿名对象 override `fun getAllTracks` 需改 `suspend override fun getAllTracks`）；所有 `trackCatalog.getAllTracks()` 调用点加 runTest 或 use suspend context。
- [x] 5.4 **新增测试 1：`ReplayAlignedTrackCatalogTest.getAllTracks_doesNotBlockCallerThread_firstCallExecutesOffMainTestScheduler`**（R3 Scenario "首次调用 asset 读取切出调用方 / Main / Test 线程" + "源码断言 withContext(Dispatchers.IO)"）：

  - `FakeBlockingReplayTrackSource` 实现 `ReplayTrackSource`，在 `loadReplayJson()` 捕获 `Thread.currentThread()` 到 `CompletableDeferred<Thread>`
  - `runTest { catalog.getAllTracks() }` 调用
  - 断言捕获线程 **不等于** `Thread.currentThread()`（测试当前线程）、**不等于** `Looper.getMainLooper()?.thread`（若可获取）
  - 不做线程名字面量断言（Review v2 P2 修补，规避 `DefaultDispatcher-worker-N` 命名耦合）
  - **补源码断言**：`File("src/main/java/com/blazepush/feature/test/repository/ReplayAlignedTrackCatalog.kt").readText().contains("withContext(Dispatchers.IO)")`——锁死实现位置（spec R3 Scenario "源码断言"）
- [x] 5.5 **新增测试 2：`ReplayAlignedTrackCatalogTest.getTrack_whenCacheCold_returnsFallbackWithoutAssetIo`**（R4 Scenario "冷缓存 getTrack(TFIC) 不触发 asset 读"）：

  - `FakeBlockingReplayTrackSource` 加 `loadReplayJsonCallCount` / `loadTrackVboCallCount` 计数
  - `ReplayAlignedTrackCatalog(fake, PresetTrackCatalog())` 刚构造，从未调 `getAllTracks`
  - `val track = catalog.getTrack("preset-tfic-lpcc")`
  - 断言 `track != null`（preset fallback 版）+ `track?.layoutName != "REAL_TRACK_REPLAY"`（不是 replay-aligned）
  - 断言 `fake.loadReplayJsonCallCount == 0` + `fake.loadTrackVboCallCount == 0`
- [x] 5.6 **新增测试 3：`ReplayAlignedTrackCatalogTest.getTrack_whenCacheWarm_returnsReplayAlignedTrack`**（R4 Scenario "热缓存 getTrack(TFIC) 返回 replay-aligned"）：

  - 先 `runTest { catalog.getAllTracks() }` 让 `cacheInitialized == true`
  - 再 `catalog.getTrack("preset-tfic-lpcc")`
  - 断言 `track?.layoutName == "REAL_TRACK_REPLAY"`
  - 断言调用次数相比 `getAllTracks` 后**不再增长**
- [x] 5.7 **新增测试 4：`ReplayAlignedTrackCatalogTest.getTrack_whenNonTficAndCold_doesNotTouchReplayAsset`**（R4 Scenario "冷缓存 getTrack(非 TFIC) 走 fallback"）：

  - 冷状态下调 `catalog.getTrack("some-other-track-id")`
  - 断言 `fake.loadReplayJsonCallCount == 0`
- [x] 5.8 **新增测试 5：`ReplayAlignedTrackCatalogTest.getAllTracks_cacheHit_doesNotRereadAssets`**（R3 Scenario "缓存命中 asset 不重复读"）：

  - 调 `getAllTracks` 两次，第二次相比第一次 `loadReplayJsonCallCount` / `loadTrackVboCallCount` 不增加
- [x] 5.9 **新增测试 6：`TestSessionViewModelTrackLoadingTest.init_availableTracksStartsEmptyThenLoadsAsync`**（新测试文件，R4 + R5 Scenario "构造期零 IO 触发" + "launch 完成后非空"）：

  - 路径：`feature/test/src/test/java/com/blazepush/feature/test/viewmodel/TestSessionViewModelTrackLoadingTest.kt`
  - 用 `FakeBlockingReplayTrackSource` 包装进 `ReplayAlignedTrackCatalog` 传给 ViewModel
  - 构造 ViewModel（runTest TestDispatcher，未 advance）
  - 立即读 `availableTracks.value` == `emptyList()`
  - 断言 `fake.loadReplayJsonCallCount == 0`（构造期零 IO 触发）
  - `runCurrent()` / `advanceUntilIdle()` 推进 launch
  - 断言 `availableTracks.value.isNotEmpty()` + 包含 TFIC
- [x] 5.10 **补源码断言**（R4 Scenario "launch 不指定 Dispatchers.IO"、R6 Scenario "Application 不预热" + "不使用 `LazyThreadSafetyMode.NONE`"）：
  - grep 生产代码 `viewModelScope.launch(Dispatchers.IO)` 在 `TestSessionViewModel.kt` 的 `_availableTracks` 附近应零命中
  - grep `by lazy` 在 `ReplayAlignedTrackCatalog.kt` 应零命中
  - grep `LazyThreadSafetyMode.NONE` / `lazy(NONE)` / `lazy(mode =` 在整个 change scope 应零命中
  - 这些通过新增 `TrackCatalogHotStartSourceAssertionTest` 放源码断言（仿照 Round 1 `FileLoggerTest` 的 R4 源码断言写法）
- [x] 5.11 **测试门槛**：
  - `./gradlew :feature:test:testDebugUnitTest --tests "*TrackCatalogTest*"` 全绿
  - `./gradlew :feature:test:testDebugUnitTest --tests "*ReplayAlignedTrackCatalogTest*"` 全绿
  - `./gradlew :feature:test:testDebugUnitTest --tests "*TestSessionViewModelTrackLoadingTest*"` 全绿
  - `./gradlew :feature:test:testDebugUnitTest --tests "*TestSessionViewModelTrackLapTest*"` 零回归

---

## 6. 合流门槛（non-negotiable）

- [x] 6.1 **Spec 验证**：`openspec validate fix-gps-stats-and-lazy-catalog-hot-start --strict` 返回 `Change ... is valid`。
- [x] 6.2 **`feature:test` 全测试绿**：`./gradlew :feature:test:testDebugUnitTest` BUILD SUCCESSFUL。
- [x] 6.3 **下游零回归**：
  - `./gradlew :core:bluetooth:testDebugUnitTest`（不涉及 parser 改动，应天然零回归；若有未提交 working-tree 改动先 stash 确认）
  - `./gradlew :core:domain:test`（新增 `DataQuality.Empty` 未改，零回归）
  - `./gradlew :app:compileDebugKotlin`（确认 app 模块 compile 不被 `TrackCatalog` BREAKING 波及）
- [x] 6.4 **E2E 契约全绿**：`./gradlew :feature:test:testDebugUnitTest --tests "*EndToEndLapTimingContractTest*"`。
- [x] 6.5 **backlog A28 迁 🟢**：`docs/superpowers/reviews/attack-backlog.md` 一节 `🔴 pending` 删除 A28 条目，三节 `🟢 pending_review` 新增 A28 条目 + 核销成果块，附录表格状态列更新。
- [x] 6.6 **backlog A37 迁 🟢**：同上，附"与 A28 合并实施"注记。
- [x] 6.7 **附录表格更新**：A28 / A37 行的状态列从 🔴 改为 🟢（commit 号此时先留空 `{pending commit}`，commit 落地后再回填）。
- [x] 6.8 **backlog 迁档 grep 自检**：`grep -nE "^### A28|^### A37|\| A28 \||\| A37 \|"` 应只命中 🟢 节 + 附录两处，🔴 节零命中。

---

## 7. Commit 策略

本 change scope 小（2 capability × 正交代码域，但两者都受 BREAKING 接口影响必须一次编译通过），**1 个代码 commit**：

- [x] 7.1 **commit**：`fix(perf): 战役 F Round 2 A28/A37 gps stats 透传 parser 滑窗 + TrackCatalog suspend 冷启动非阻塞`

  body 要点：
  - **A28**：`GpsDataViewModel.updateDataStats` 累计平均逻辑整体删除；`stats.frequency` 透传 `data.frequency`（parser 1 秒滑窗结果）；`packetLossRate` 从 `data.frequency` 反推 `expectedSampleInterval = 1000.0 / frequency`，适配 10Hz / 25Hz / 50Hz 设备；`init` 内自订阅 `connectionState.distinctUntilChanged().filter{DISCONNECTED}.onEach{resetStats()}`，`resetStats()` 回到 `DataQuality.Empty`
  - **A37 BREAKING**：`TrackCatalog.getAllTracks()` → `suspend fun`；`PresetTrackCatalog` 直返不需 IO；`ReplayAlignedTrackCatalog.getAllTracks` 顶层 `withContext(Dispatchers.IO)` + 去 `by lazy` + `@Volatile / synchronized` 双检锁；`getTrack(trackId)` 保持同步，冷缓存降级 fallback 不触 asset IO
  - **TestSessionViewModel** `_availableTracks` 初始化 `emptyList()` + `viewModelScope.launch { trackCatalog.getAllTracks() }` 不指定 Dispatchers.IO（IO 边界唯一防线在 catalog 实现侧）
  - 测试：A28 × 3 (`frequency_transparentlyMirrors...` / `packetLoss_derivedFromFrameFrequency...` / `resetStats_onConnectionStateDisconnected...`) + A37 × 6 (`getAllTracks_doesNotBlockCallerThread...` / `getTrack_whenCacheCold_returnsFallbackWithoutAssetIo` / `getTrack_whenCacheWarm_returnsReplayAlignedTrack` / `getTrack_whenNonTficAndCold...` / `getAllTracks_cacheHit_doesNotRereadAssets` / `init_availableTracksStartsEmptyThenLoadsAsync`) + 源码断言测试类
  - 合流门槛：`openspec validate --strict` / `:feature:test:testDebugUnitTest` / 下游 `:core:bluetooth :core:domain :app` / E2E 契约 全绿

  格式约束：
  - Conventional Commits
  - body 含 "A28" + "A37" 便于 grep
  - Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
  - **kt-check**：若触发 legacy 违规，按战役 G B 方案纪律评估加 `// @IgnoreFormatCheck` 或精确修到位；本 change 仅改 viewmodel / repository 层，legacy 违规面应远小于 Round 1
- [x] 7.2 **commit 后回填 backlog 附录表格 commit 号** A28 / A37 行的 `{pending commit}` 占位符替换成实际 commit hash。
