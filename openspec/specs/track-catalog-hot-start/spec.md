# track-catalog-hot-start Specification

## Purpose
TBD - created by archiving change fix-gps-stats-and-lazy-catalog-hot-start. Update Purpose after archive.
## Requirements
### Requirement: `TrackCatalog.getAllTracks()` 接口签名为 `suspend fun`

`TrackCatalog` 接口 `getAllTracks()` 方法 MUST 声明为 `suspend fun getAllTracks(): List<Track>`（破坏性变更）。`getTrack(trackId: String): Track?` 保持**同步**（非 suspend），理由是单 track 查询无 IO 成本、避免 suspend 污染传播到 `LapTimingEngine` 等同步消费链路。

- 业务目标：防止 `TestSessionViewModel` 构造期同步调用 `getAllTracks()` 阻塞 Main 线程
- BREAKING：所有实现（`PresetTrackCatalog`、`ReplayAlignedTrackCatalog`）MUST 对齐新签名；所有消费方（`TestSessionViewModel` 等）MUST 改为协程上下文调用
- `getTrack` 保持同步的约束：确保 `LapTimingEngine` 等引擎内部路径不需要 suspend context

#### Scenario: TrackCatalog.getAllTracks 是 suspend fun

- **GIVEN** 实施后 `TrackCatalog.kt` 源码
- **WHEN** 查看 `getAllTracks` 方法签名
- **THEN** 是 `suspend fun getAllTracks(): List<Track>`（带 `suspend` 关键字）

#### Scenario: TrackCatalog.getTrack 保持同步

- **GIVEN** 实施后 `TrackCatalog.kt` 源码
- **WHEN** 查看 `getTrack(trackId: String)` 方法签名
- **THEN** 是 `fun getTrack(trackId: String): Track?`（**不带** `suspend` 关键字）

### Requirement: `PresetTrackCatalog` 内存直返实现（suspend 不强制 IO）

`PresetTrackCatalog.getAllTracks()` MUST 实现为 suspend fun 但 body **不需要** `withContext(Dispatchers.IO)`，直接 return 内存中的预置赛道列表。`suspend` 关键字仅为对齐接口契约。

预置赛道列表 MUST 由 `mainPresets`（含 TFIC LPCC + XIC 厦门国际赛车场，所有 Build Variant 共用，定义在 main 源集）与 `extraPresetTracks(): List<Track>`（由 Android Gradle 互斥变体源集 `src/debug/` 与 `src/release/` 各提供一份实现）拼接而成：

- main 源集 MUST 在 `PresetTracks.kt` 中调用 `extraPresetTracks()`，但 MUST NOT 在 main 源集声明 `extraPresetTracks` 函数本体（否则 main+debug 合并编译时触发 `duplicate JVM declarations`）。
- release 源集 MUST 提供 `feature/test/src/release/.../repository/ExtraPresetTracksRelease.kt`，其中 `internal fun extraPresetTracks(): List<Track> = emptyList()`。
- debug 源集 MUST 提供 `feature/test/src/debug/.../repository/ExtraPresetTracksDebug.kt`，其中 `internal fun extraPresetTracks(): List<Track> = listOf(<天投泊寓环线 Track>)`。
- 两个变体源集文件的包名 MUST 一致（`com.blazepush.feature.test.repository`），文件名后缀 `Debug` / `Release` 仅作 IDE/grep 区分，不影响编译可见性。
- 拼接顺序 MUST 为 `mainPresets + extraPresetTracks()`，即 mainPresets[0] = TFIC，mainPresets[1] = XIC，debug variant 拼接后第 2 位为天投泊寓。
- release 构建 MUST NOT 编译进任何 debug 源集文件；release 包内 `getAllTracks()` 返回 `["preset-tfic-lpcc", "preset-xic-lpcc"]`。
- variant 区分 MUST 通过 Gradle 源集机制达成，MUST NOT 依赖 `BuildConfig.DEBUG` 或其它运行时 if-else 判断（否则 release 字节码会留死代码）。

- 业务目标：避免对纯内存查询强加上下文切换开销；debug 包额外获得真实小型环线赛道（boyu）用于圈速调试；XIC 作为第二条 main-variant preset 让所有 variant 用户都能跑厦门国际赛车场。
- 测试消费方：`TrackCatalogReleaseVariantTest`（仅 `:feature:test:testReleaseUnitTest`）断言 `["preset-tfic-lpcc", "preset-xic-lpcc"]`；`TrackCatalogDebugVariantTest`（仅 `:feature:test:testDebugUnitTest`）断言 `["preset-tfic-lpcc", "preset-xic-lpcc", "preset-boyu-loop"]`。

#### Scenario: PresetTrackCatalog 无 withContext 调用

- **GIVEN** 实施后 `PresetTrackCatalog.kt`（或等价文件名，tasks 阶段 grep 确认）源码
- **WHEN** grep `withContext` / `Dispatchers.IO` 在 `getAllTracks` body 内
- **THEN** 零匹配（纯 return 内存列表）

#### Scenario: PresetTrackCatalog.getAllTracks 返回预置赛道

- **GIVEN** `PresetTrackCatalog` 实例
- **WHEN** `runTest { catalog.getAllTracks() }`
- **THEN** 返回非空 `List<Track>`，第 0 位 MUST 为 TFIC LPCC（`id == "preset-tfic-lpcc"`），第 1 位 MUST 为 XIC（`id == "preset-xic-lpcc"`）

#### Scenario: release variant 含 TFIC + XIC

- **GIVEN** `:feature:test:testReleaseUnitTest` 任务（即 `main + release + test + testRelease` 源集组合）
- **WHEN** `runTest { PresetTrackCatalog().getAllTracks() }`
- **THEN** 返回 `List<Track>` 的 `map { it.id }` 严格等于 `listOf("preset-tfic-lpcc", "preset-xic-lpcc")`

#### Scenario: debug variant 额外含天投泊寓（三条赛道顺序锁定）

- **GIVEN** `:feature:test:testDebugUnitTest` 任务（即 `main + debug + test + testDebug` 源集组合）
- **WHEN** `runTest { PresetTrackCatalog().getAllTracks() }`
- **THEN** 返回 `List<Track>` 的 `map { it.id }` 严格等于 `listOf("preset-tfic-lpcc", "preset-xic-lpcc", "preset-boyu-loop")`

#### Scenario: extraPresetTracks 在 release 源集返回 emptyList

- **GIVEN** release 源集下 `feature/test/src/release/.../repository/ExtraPresetTracksRelease.kt`
- **WHEN** 阅读 `extraPresetTracks` 函数 body
- **THEN** 函数返回 `emptyList()`，且文件**不**包含天投泊寓 Track 数据或任何 import `com.blazepush.feature.test.model.track.*` 之外的赛道相关引用

#### Scenario: extraPresetTracks 在 debug 源集返回天投泊寓

- **GIVEN** debug 源集下 `feature/test/src/debug/.../repository/ExtraPresetTracksDebug.kt`
- **WHEN** `extraPresetTracks()` 被调用
- **THEN** 返回单元素列表，唯一元素 `id == "preset-boyu-loop"`

#### Scenario: 反例——XIC MUST NOT 进 extraPresetTracks（debug-only）

- **GIVEN** 实施后 `ExtraPresetTracksDebug.kt` 与 `ExtraPresetTracksRelease.kt`
- **WHEN** grep `preset-xic-lpcc` 在两文件中
- **THEN** 零匹配（XIC 仅在 main 源集 PresetTracks.kt mainPresets 中声明）
- **AND** 若实现把 XIC 放进 debug ExtraPresetTracksDebug.kt，release variant 包内 PresetTrackCatalog().getAllTracks() 不含 XIC → "release variant 含 TFIC + XIC" scenario fail

### Requirement: `ReplayAlignedTrackCatalog.getAllTracks()` 实现侧 MUST `withContext(Dispatchers.IO)`

`ReplayAlignedTrackCatalog.getAllTracks()` 实现 MUST 在方法顶层用 `withContext(Dispatchers.IO) { ... }` 包裹 asset 读取 + Gson parse 路径。这是 IO 边界的**唯一防线**：调用方是否使用 IO coroutine 只是额外保护、MUST NOT 被用作替代实现侧契约的理由。

- 调用方（如 `TestSessionViewModel.viewModelScope.launch { ... }`）可以不指定 dispatcher
- 首次触发 + 后续触发都必须在 IO 上执行（通过显式双检锁缓存命中后 `withContext` 开销仍然很低，但契约要求统一入口）
- 禁止在 `getAllTracks` body 之外通过 `by lazy(SYNCHRONIZED)` 类字段提前触发 IO（该模式在 v1 存在，已被 design D5 替代）

#### Scenario: ReplayAlignedTrackCatalog.getAllTracks 源码包含 withContext(Dispatchers.IO)（源码断言）

- **GIVEN** 实施后 `ReplayAlignedTrackCatalog.kt` 源码
- **WHEN** grep `withContext(Dispatchers.IO)` 在 `getAllTracks` 方法内
- **THEN** 命中至少一处，且该 `withContext` 包裹 `ensureReplayTrackLoaded()` + `fallbackCatalog.getAllTracks()` 的路径（源码契约锁死 IO 边界实现位置，Review v2 P2 修补：不再依赖脆弱的线程名断言）

#### Scenario: 首次调用 asset 读取切出调用方 / Main / Test 线程（runtime 断言）

- **GIVEN** `FakeBlockingReplayTrackSource` 实现 `ReplayTrackSource`，在 `loadReplayJson()` 内捕获 `Thread.currentThread()`
- **AND** 测试记录调用方 / Main / TestScheduler 所用线程的引用
- **AND** `ReplayAlignedTrackCatalog(fake, PresetTrackCatalog())` 实例
- **WHEN** 从 caller 线程 `runTest { }` 调用 `catalog.getAllTracks()`
- **THEN** fake 捕获的线程 **不等于** 调用方线程、**不等于** Main Looper 线程、**不等于** TestScheduler 的 immediate dispatcher 线程
- **AND** 线程名不做精确字面量断言（避免耦合 kotlinx-coroutines 内部命名，如 `DefaultDispatcher-worker-N` vs `CommonPool-worker-N` 等随版本变化的细节）

#### Scenario: 缓存命中 asset 不重复读

- **GIVEN** `ReplayAlignedTrackCatalog` 已完成一次 `getAllTracks()` 调用，`cacheInitialized == true`
- **WHEN** 再次调用 `getAllTracks()`
- **THEN** `ReplayTrackSource.loadReplayJson()` / `loadTrackVbo()` 的调用次数相比首次调用**不增加**（测试用 spy / counter 验证）

#### Scenario: 类字段级 by lazy 被显式缓存替代

- **GIVEN** 实施后 `ReplayAlignedTrackCatalog.kt` 源码
- **WHEN** grep `by lazy` 在文件内
- **THEN** 零匹配（v1 的 `replayAlignedTrack: Track? by lazy { ... }` 已被 `@Volatile var cachedReplayTrack` + `synchronized` 双检锁 + `cacheInitialized` 标志替代）

#### Scenario: asset 解析失败降级 fallbackCatalog

- **GIVEN** `FakeReplayTrackSource` 的 `loadReplayJson()` 抛 `RuntimeException` / 返回恶意 JSON
- **AND** `fallbackCatalog = PresetTrackCatalog()`
- **WHEN** `runTest { catalog.getAllTracks() }`
- **THEN** 返回 `PresetTrackCatalog` 的赛道集合（TFIC 为 preset fallback 版，不带 replay 对齐 path），**不抛异常**

### Requirement: `ReplayAlignedTrackCatalog.getTrack(trackId)` 冷缓存时不触发 IO（走 fallback 降级）

`ReplayAlignedTrackCatalog.getTrack(trackId)` 保持同步（非 suspend）。在 `cacheInitialized == false`（从未调用过 `getAllTracks()`）的冷状态下，`getTrack(TFIC_TRACK_ID)` MUST **不触发** `ReplayTrackSource.loadReplayJson()` / `loadTrackVbo()` 任何 asset 读，MUST **直接降级** 返回 `fallbackCatalog.getTrack(TFIC_TRACK_ID)`（预置 preset fallback 版，不带 replay 对齐 path）。

- 业务目标：A37 `by lazy` 被显式缓存替代后，`getTrack` 作为唯一剩下的同步 API 不能再偷偷触发 IO；否则冷启动阻塞入口从 `getAllTracks` 漏到 `getTrack`
- Review v2 P1-2 修补：design D5 已定方向（"若从未调用过 `getAllTracks`，走 fallbackCatalog 降级"），spec 明确契约 + counter 场景
- 只有 `suspend getAllTracks()` 是唯一可以触发 replay asset 加载的入口；`getTrack` 命中缓存才返回 replay-aligned，未命中就降级，保持"冷启动零 IO"

#### Scenario: 冷缓存 getTrack(TFIC) 不触发 asset 读

- **GIVEN** `FakeBlockingReplayTrackSource` spy 记录 `loadReplayJson` / `loadTrackVbo` 调用次数
- **AND** `ReplayAlignedTrackCatalog(fake, PresetTrackCatalog())` 刚构造完成，`getAllTracks` 从未被调用
- **WHEN** 调用 `catalog.getTrack("preset-tfic-lpcc")`
- **THEN** 返回 `PresetTrackCatalog` 的 TFIC 赛道（preset fallback 版，不是 replay-aligned）
- **AND** `loadReplayJson` 调用次数 == 0
- **AND** `loadTrackVbo` 调用次数 == 0

#### Scenario: 热缓存 getTrack(TFIC) 返回 replay-aligned 版

- **GIVEN** `ReplayAlignedTrackCatalog` 已通过 `runTest { catalog.getAllTracks() }` 完成一次加载，`cacheInitialized == true`
- **WHEN** 调用 `catalog.getTrack("preset-tfic-lpcc")`
- **THEN** 返回 `cachedReplayTrack`（replay-aligned 版，`layoutName == "REAL_TRACK_REPLAY"`）
- **AND** `loadReplayJson` / `loadTrackVbo` 调用次数相比 `getAllTracks` 首次调用**不增加**

#### Scenario: 冷缓存 getTrack(非 TFIC) 走 fallback 路径不读 asset

- **GIVEN** `FakeBlockingReplayTrackSource` + `ReplayAlignedTrackCatalog` 冷缓存
- **WHEN** 调用 `catalog.getTrack("some-other-track-id")`
- **THEN** 返回 `fallbackCatalog.getTrack("some-other-track-id")` 的结果（`null` 或对应 preset）
- **AND** `loadReplayJson` 调用次数 == 0（非 TFIC 路径不触碰 replay cache）

### Requirement: `TestSessionViewModel._availableTracks` 构造期不阻塞调用线程

`TestSessionViewModel.init` MUST 将 `_availableTracks` 初始化为空列表 `MutableStateFlow<List<Track>>(emptyList())`，在 `init` block 内用 `viewModelScope.launch { _availableTracks.value = trackCatalog.getAllTracks() }` 异步填充。调用方（ViewModelProvider.get 通常在 Main 线程）MUST NOT 在 ViewModel 构造返回前触发任何 asset IO。

- `viewModelScope.launch { ... }` MUST NOT 显式指定 `Dispatchers.IO`：IO 边界唯一防线在 catalog 实现侧，ViewModel 只负责"异步"
- 加载态 MUST 用空列表表达，MUST NOT 新增 `TrackLoadState` sealed class（避免 UI scope 外溢）

#### Scenario: 构造期不触发 catalog.getAllTracks 同步读

- **GIVEN** `FakeBlockingReplayTrackSource` + `ReplayAlignedTrackCatalog` 组合注入 `TestSessionViewModel`
- **WHEN** 调用 `TestSessionViewModel(...)` 构造（runTest 的 TestScope / TestDispatcher 中）
- **AND** 在 `viewModelScope` 的任何 launch 被调度前立即读取 `_availableTracks.value`
- **THEN** `_availableTracks.value == emptyList<Track>()`（加载态）
- **AND** `FakeBlockingReplayTrackSource.loadReplayJson` 调用计数 == 0（构造期零 IO 触发）

#### Scenario: viewModelScope.launch 完成后 availableTracks 非空

- **GIVEN** `TestSessionViewModel` 构造后，`viewModelScope` 协程被调度执行
- **WHEN** 测试线程 advance TestScheduler / `runCurrent()` 让 launch 完成
- **THEN** `_availableTracks.value.isNotEmpty() == true`（包含至少 TFIC 赛道）

#### Scenario: launch 不指定 Dispatchers.IO

- **GIVEN** 实施后 `TestSessionViewModel.kt` 源码
- **WHEN** grep `viewModelScope.launch(Dispatchers.IO)` 在 `_availableTracks` 加载路径附近
- **THEN** 零匹配（catalog 实现侧自负 IO，ViewModel 只写 `viewModelScope.launch { ... }`）

### Requirement: 不引入 App 启动级预热入口

本 change MUST NOT 引入任何 Application 级别的预热机制，包括但不限于 `Application.onCreate` 启动 coroutine 预热 `trackCatalog`、`by lazy(mode = LazyThreadSafetyMode.NONE) + 预热线程`、以及任何 App-scope hook。

- 理由：预热方案被 proposal 显式拒收（生命周期隐式、测试绕、ViewModel 时序耦合）
- 所有异步加载通过 `viewModelScope.launch` 驱动，生命周期与 ViewModel 一致

#### Scenario: Application 子类不新增预热代码

- **GIVEN** 实施后 `app/` 模块的 `Application` 子类源码（若存在）
- **WHEN** grep `trackCatalog` / `TrackCatalog` / `preheatCatalog` 等
- **THEN** 零匹配（没有预热 hook）

#### Scenario: ReplayAlignedTrackCatalog 不使用 LazyThreadSafetyMode.NONE

- **GIVEN** 实施后 `ReplayAlignedTrackCatalog.kt` 源码
- **WHEN** grep `LazyThreadSafetyMode.NONE` / `lazy(mode =` / `lazy(NONE)`
- **THEN** 零匹配

