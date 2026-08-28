## Context

### 当前状态

**A28 `GpsDataViewModel.updateDataStats` 现状**（`feature/test/.../viewmodel/GpsDataViewModel.kt`）：
- line 34：`val connectionState: StateFlow<ConnectionState> = gpsDataRepository.connectionState`（**已存在**，但 init 未订阅用于 reset）
- line 82-120：`updateDataStats` 做三件事
  1. `dataCountStartTime`（首次置位永不重置）+ `dataCount++`，算 `frequency = dataCount / elapsedSeconds`（**累计平均**，line 100-101）
  2. `expectedInterval = 100L` 硬编码 10Hz 假设（line 104）
  3. `packetLossRate` 基于 `dataAge / expectedInterval` 计算
- line 164-169：`resetStats()` 清 `dataCountStartTime / dataCount / lastDataTime`，**无任何调用方**

**parser 侧真相**（`core/bluetooth/.../parser/RaceChronoParser.kt`）：
- line 29：`gpsDataTimestamps = Collections.synchronizedList(ArrayList<Long>())`（private，滑窗实现）
- line 33：`gpsFrequency` private，每帧更新为 1 秒窗内 ts 数
- line 219-225：每次新帧进来推入窗口、剔除 > 1s 旧 ts、重算 `gpsFrequency`
- line 259：`frequency = gpsFrequency` 写入 `GpsData`（消费方直接读 `data.frequency` 即可）

**A37 `ReplayAlignedTrackCatalog` 现状**（`feature/test/.../repository/ReplayAlignedTrackCatalog.kt`）：
- line 14-17：**`ReplayTrackSource` 接口已存在**（`loadReplayJson` / `loadTrackVbo`），`AssetReplayTrackSource` 是 Android 实现
- line 29-41：`replayAlignedTrack: Track? by lazy { runCatching { buildReplayAlignedTrack(...) }.getOrNull() }`—— 类字段级 `by lazy`（默认 SYNCHRONIZED），首次 `getAllTracks()` / `getTrack(TFIC_TRACK_ID)` 访问时触发 asset 读 + Gson parse
- line 43-52：`getAllTracks()` / `getTrack()` 均为同步方法

**`TestSessionViewModel.kt:80`**：`private val _availableTracks = MutableStateFlow(trackCatalog.getAllTracks())` 在构造期直接同步读 —— ViewModelProvider.get 通常在 Main 线程，导致冷启动阻塞。

### 约束

- RaceChrono BLE 协议不可变（`GpsData.frequency` 字段已存在，本 change 仅消费不改）
- 分层：ViewModel ↔ Repository ↔ BLE，BleConnection 不直接引用 ViewModel
- Koin DI（`feature/test/.../di/AppModule.kt`）
- 公共协议兼容性 N/A（本 change 不涉及）
- 双端 N/A（仅接收端）

## Goals / Non-Goals

**Goals**：
- A28：`stats.frequency` 响应性由"会话级累计平均"（小时级滞后）收敛到"1 秒滑窗"（parser 已有）
- A28：`packetLossRate` 口径摆脱硬编码采样率，支持 10Hz / 25Hz / 50Hz 设备不改代码
- A28：跨连接会话 stats 重置语义生效（`DISCONNECTED` → `resetStats()`）
- A37：`TrackCatalog.getAllTracks()` 首次访问**不阻塞调用方线程**，尤其是 `TestSessionViewModel` 构造期的 Main 线程
- A37：IO 边界契约清晰（实现侧 MUST `withContext(Dispatchers.IO)`，调用方额外保护可选但不替代）

**Non-Goals**：
- 不碰 A22 / A30 / A35（Round 3-5 处理）
- 不引入 App 启动级预热入口
- 不暴露 `RaceChronoParser.gpsFrequency` 为 public
- 不引入生产侧 `EXPECTED_SAMPLE_INTERVAL_MS` 常量
- 不改 `GpsData` / `ConnectionState` / BLE 协议字段
- 不在本 change 引入 `TrackLoadState` sealed class（加载态用空列表表达即可，避免新增类型）
- 不做 `ReplayAlignedTrackCatalog` 的缓存失效 / 热刷新语义（超出 scope）

## Decisions

### D1 · A28 订阅链形状：`connectionState.onEach { … }.launchIn(viewModelScope)` + `distinctUntilChanged`

**决策**：`GpsDataViewModel.init` 内部增加：

```kotlin
init {
    gpsDataRepository.gpsData
        .onEach { data -> updateDataStats(data) }
        .launchIn(viewModelScope)   // 已有

    // 新增：A28 DISCONNECTED 触发 resetStats，distinctUntilChanged 避免重复 reset
    connectionState
        .distinctUntilChanged()
        .filter { it == ConnectionState.DISCONNECTED }
        .onEach { resetStats() }
        .launchIn(viewModelScope)
}
```

**Rationale**：
- 复用已暴露的 `connectionState`（line 34），**不新增 public 字段**
- `distinctUntilChanged` 避免同状态重复 reset（StateFlow 理论上已去重，保险起见显式写出）
- `filter { it == DISCONNECTED }` 明示语义，避免 when-else 展开
- **ViewModel 自订阅**，不靠 TestSessionViewModel 跨层触发；`BleConnection` 零改动

**Alternatives considered**：
- (a) `TestSessionViewModel` 订阅 `connectionState` 调 `gpsDataViewModel.resetStats()` —— 拒收：跨 ViewModel 依赖、Round 2 mini review P1-1 已驳
- (b) `gpsDataRepository` 内部直接在 disconnect 回调里 reset —— 拒收：repository 不应持有 stats 状态（分层污染）
- (c) `BleConnection.disconnect()` 直接调 ViewModel —— 拒收：穿透分层、违反战役 G 闭环原则

### D2 · A28 `packetLossRate` 公式：从 `data.frequency` 反推采样周期

**决策**（proposal 已定锚，design 固化）：

```kotlin
// frequency ≤ 0 为暖启动 / 丢连 case，回退 0（语义：无窗口数据不做判断）
val expectedSampleInterval = if (data.frequency > 0.0) 1000.0 / data.frequency else 0.0
val packetLossRate = if (expectedSampleInterval > 0.0 && dataAge > expectedSampleInterval * 2) {
    ((dataAge - expectedSampleInterval) / expectedSampleInterval).coerceIn(0.0, 100.0)
} else {
    0.0
}
```

**覆盖 case**：

| data.frequency | dataAge | 期望 packetLossRate |
|---|---|---|
| 25.0 Hz（稳态） | 30ms | 0（dataAge < 80ms 阈值） |
| 25.0 Hz | 200ms | `(200-40)/40 = 4.0` → `coerceIn(0, 100)` = 4.0 |
| 10.0 Hz（低频设备） | 300ms | `(300-100)/100 = 2.0` |
| 0.0（暖启动 / 丢连） | 任何值 | 0 |
| parser 首帧后 data.frequency 可能 < 25.0（窗口未满） | 60ms | 短暂偏小期间阈值变严，短暂 false positive 可接受（1 秒后收敛） |

**Rationale**：
- 不硬编码采样率 → 自动适配 ESP32 25Hz / 10Hz 手机内置 GPS / 50Hz 高频设备
- `frequency ≤ 0` 回退 0 避免 NaN / 除零 / 冷启动误告警
- 2× 阈值门槛保留（与 v1 一致的容忍度），避免抖动触发

**Alternatives considered**：
- (a) 仍保留 `EXPECTED_SAMPLE_INTERVAL_MS` 生产常量 —— 拒收：proposal v2 已明确不把 40ms 提到生产侧（协议冻结嫌疑）
- (b) 在 ViewModel 内单独维护 `lastNFramesInterval` 移动平均 —— 拒收：重复 parser 已做的事

### D3 · A28 删除清单

需要一并移除的字段 / 常量：
- `private var dataCountStartTime = 0L`（line 64）
- `private var dataCount = 0`（推测存在，grep 应确认）
- `val expectedInterval = 100L`（line 104，方法内局部常量）
- `dataCount++` + `dataCountStartTime = now` 相关赋值（line 86-101）
- `resetStats()` 保留，但 body 只清 `lastDataTime` 即可（`dataCount*` 已不存在）

`_dataStats` 初始化值保持现有（`DataStats.EMPTY` 不存在则用构造 `DataStats(0L, 0.0, 0.0)` 等价），测试断言用"等价初始态"表述避免预设不存在的符号。

### D4 · A37 `TrackCatalog` 接口签名：`suspend fun getAllTracks()`

**决策**：

```kotlin
interface TrackCatalog {
    suspend fun getAllTracks(): List<Track>
    fun getTrack(trackId: String): Track?   // 保持同步：单个 track 查询已缓存，无 IO
}
```

**BREAKING 影响范围**：
- `PresetTrackCatalog` / `PresetTracks` 实现改 `suspend fun`，body 可直接 return（内存直返，不需 IO）
- `ReplayAlignedTrackCatalog.getAllTracks()` 改 `suspend fun` 并 `withContext(Dispatchers.IO)` 包裹 + `buildReplayAlignedTrack` 调用
- `TestSessionViewModel`（唯一消费方）改异步加载（见 D6）
- `TrackCatalogTest` 等现有测试改 `runTest { ... }` + `suspend` 调用

**保持 `getTrack(trackId)` 同步的理由**：
- 当前实现：`replayAlignedTrack` 首次触发时才走 IO，后续访问命中 `by lazy` 缓存
- 去掉 `by lazy`（见 D5）后 `getTrack` 走相同缓存路径
- suspend 化 `getTrack` 会波及 `LapTimingEngine` 路径上的多个同步消费方（scope 外溢）

**Alternatives considered**：
- (a) 全部接口 suspend 化 —— 拒收：scope 外溢、`getTrack` 无 IO 无需 suspend
- (b) `by lazy(mode = NONE) + App 预热` —— 拒收：proposal 已定"显式拒绝"，生命周期隐式、测试绕、与 ViewModel 时序耦合

### D5 · A37 `ReplayAlignedTrackCatalog` 实现改造

**决策**：去掉类字段级 `by lazy`，改用显式缓存 + suspend 首次加载：

```kotlin
class ReplayAlignedTrackCatalog(
    private val replayTrackSource: ReplayTrackSource,
    private val fallbackCatalog: TrackCatalog = PresetTrackCatalog()
) : TrackCatalog {

    // 显式缓存（替代 by lazy），首次 getAllTracks 在 IO 上填充
    @Volatile
    private var cachedReplayTrack: Track? = null
    @Volatile
    private var cacheInitialized = false

    override suspend fun getAllTracks(): List<Track> = withContext(Dispatchers.IO) {
        ensureReplayTrackLoaded()
        val fallbackTracks = fallbackCatalog.getAllTracks().filterNot { it.id == TFIC_TRACK_ID }
        val replayTrack = cachedReplayTrack ?: fallbackCatalog.getTrack(TFIC_TRACK_ID)
        if (replayTrack != null) fallbackTracks + replayTrack else fallbackTracks
    }

    override fun getTrack(trackId: String): Track? {
        if (trackId != TFIC_TRACK_ID) return fallbackCatalog.getTrack(trackId)
        // 若缓存未 init（getAllTracks 从未被调用），回退到 fallbackCatalog
        return if (cacheInitialized) cachedReplayTrack ?: fallbackCatalog.getTrack(trackId)
        else fallbackCatalog.getTrack(trackId)
    }

    private fun ensureReplayTrackLoaded() {
        if (cacheInitialized) return
        synchronized(this) {
            if (cacheInitialized) return
            cachedReplayTrack = runCatching {
                buildReplayAlignedTrack(
                    replayJson = replayTrackSource.loadReplayJson(),
                    vbo = replayTrackSource.loadTrackVbo()
                )
            }.getOrNull()
            cacheInitialized = true
        }
    }
    // … buildReplayAlignedTrack / parseReplaySamples 不变
}
```

**关键点**：
- `withContext(Dispatchers.IO)` 在 `getAllTracks()` 顶层，**IO 边界唯一防线在实现侧**
- `@Volatile + synchronized` 双检锁 —— 替代 `by lazy(SYNCHRONIZED)` 的等价语义，但显式放在 `ensureReplayTrackLoaded`（测试可断言"首次调用前 `cacheInitialized == false`"）
- `getTrack(trackId)` 保持同步：若从未调用过 `getAllTracks`（罕见，冷启动路径上 `TestSessionViewModel.init` 会触发一次），走 fallbackCatalog 降级

**fallback `PresetTrackCatalog` vs `PresetTracks`**：当前代码叫 `PresetTrackCatalog`（见 line 31），proposal 中提到的 `PresetTracks.kt` 文件需要核对实际文件名（tasks 阶段 grep 确认）。

**Alternatives considered**：
- (a) 保留 `by lazy`，`getAllTracks` 只 `withContext(IO) { replayAlignedTrack }` —— 半吊子：`by lazy` 首次访问本身在 `getAllTracks()` 的 IO 上下文里触发，语义 OK，但 `by lazy` 的 `synchronized` 锁是在首次访问线程上，如果多线程并发首次访问 IO 内 block，其他线程会 block 在 `by lazy` 的 monitor 上，IO 池线程竞争；拒收，用显式双检锁清晰
- (b) 用 `CompletableDeferred` + 启动时 launch —— 拒收：引入启动时副作用，与 "不新增 App 预热入口" 抵触

### D6 · A37 ViewModel 加载时序：`launch { _availableTracks.value = catalog.getAllTracks() }`

**决策**：`TestSessionViewModel` 改造：

```kotlin
// 构造期空列表（加载态用空列表表达，不新增 TrackLoadState sealed class）
private val _availableTracks = MutableStateFlow<List<Track>>(emptyList())
val availableTracks: StateFlow<List<Track>> = _availableTracks.asStateFlow()

init {
    // … 其他 init 逻辑 …
    viewModelScope.launch {
        _availableTracks.value = trackCatalog.getAllTracks()
    }
}
```

**关键点**：
- `viewModelScope.launch { … }` **不预设 dispatcher** —— IO 边界在 catalog 实现侧（D5），ViewModel 只关心"异步加载"；如果 catalog 是 `PresetTrackCatalog`（纯内存）调用也是合法的
- 空列表作为加载态：UI 层已有处理"无可用赛道"的 fallback（现有 `TestModeSelectionScreen` / `TrackSelectionCard` 等的防御性行为），不新增 `TrackLoadState`
- `viewModelScope` 取消时 launch 自动取消，无需手动清理

**Alternatives considered**：
- (a) `launch(Dispatchers.IO) { … }` —— 拒收：会把 dispatcher 约束重复固化在调用方，违反 "IO 边界唯一防线" 原则，catalog 如果是纯内存的 `PresetTrackCatalog` 也会被迫切上下文
- (b) `sealed class TrackLoadState { Loading / Loaded(List<Track>) / Error }` —— 拒收：UI 层要改，scope 外溢；空列表 + UI 防御性已足够表达加载态

### D7 · A37 测试可注入方案：复用已有 `ReplayTrackSource` 接口

**决策**：`ReplayTrackSource` 接口（line 14-17）**已存在且已可注入**，测试直接 fake：

```kotlin
class FakeBlockingReplayTrackSource : ReplayTrackSource {
    val callThread = CompletableDeferred<String>()
    override fun loadReplayJson(): String {
        callThread.complete(Thread.currentThread().name)
        return """{"samples": [...minimal...]}"""
    }
    override fun loadTrackVbo(): String = ""
}

@Test
fun getAllTracks_doesNotBlockCallerThreadOnFirstCall() = runTest {
    val fake = FakeBlockingReplayTrackSource()
    val catalog = ReplayAlignedTrackCatalog(fake, PresetTrackCatalog())

    // 从 Main 等价 dispatcher 调用（runTest 的 TestDispatcher 模拟 Main）
    catalog.getAllTracks()

    val thread = fake.callThread.await()
    assertTrue("asset 读取应在 IO 线程池执行，实际线程：$thread",
        thread.startsWith("DefaultDispatcher-worker") || thread.contains("IO"))
}
```

**不新增的东西**：无需引入 `AssetLoader` / 新接口 / 新构造器参数 —— `ReplayTrackSource` 已经覆盖这个抽象。

### D8 · Capability 拆分

- `gps-runtime-stats`（A28）：定义 `stats.frequency` 透传契约、`packetLossRate` 从 `data.frequency` 反推的公式契约、`DISCONNECTED` → `resetStats` 的触发契约
- `track-catalog-hot-start`（A37）：定义 `TrackCatalog.getAllTracks()` 的 suspend 契约、`ReplayAlignedTrackCatalog` IO 边界契约、`TestSessionViewModel` 异步加载的时序契约

## Risks / Trade-offs

| Risk | Mitigation |
|---|---|
| A28 parser 首帧后 `data.frequency` 可能 < 25.0（1 秒窗未满），短暂 packetLossRate 阈值偏严产生 1 秒内的短暂 false positive | 可接受 —— 1 秒后收敛；暖启动窗口本来诊断价值就低；测试场景避免断言首 1 秒行为 |
| A28 订阅 `connectionState` 用 `filter` 只关心 `DISCONNECTED`，如果新增 `RECONNECTING` / `ERROR` 等状态不会触发 reset | Non-goal —— 本 change 不扩展状态机；若未来加新状态，`filter` 覆盖一行改即可，风险低 |
| A37 `ReplayAlignedTrackCatalog` 双检锁 `synchronized(this)` 在多线程首次并发访问时，一条线程在 IO 上持锁、其他线程短暂等待 | 可接受 —— 冷启动路径通常只有 1 个调用方（ViewModel.init）；生产场景多线程并发首次访问极少；若未来出现场景，可换 `Mutex` 但会引入 suspend 污染 `getTrack` |
| A37 `getTrack(trackId)` 保持同步，首次在 `getAllTracks` 调用前触发会 fallback 到 `PresetTrackCatalog` 而非 replay-aligned | 可接受 —— 现有 `TestSessionViewModel.init` 先 launch getAllTracks；后续 `getTrack` 命中缓存；边缘场景仅在 init 竞态窗口（~几 ms）出现且结果是"普通赛道而非带 replay 对齐"的降级，不影响圈速几何正确性 |
| A37 空列表加载态使 UI 首屏可能瞬时无赛道可选 | 可接受 —— 加载在 IO 上通常几十 ms，UI 已有"无可用赛道"的 fallback 视觉；若未来体感差可补 loading UI（Non-goal） |

## Migration Plan

### 实施顺序（tasks 阶段分段）

1. **A28 独立段**（ViewModel 内部改动，不碰接口）
   - `updateDataStats` 重写（透传 `data.frequency` + 新 `packetLossRate` 公式）
   - `init` 加订阅 `connectionState.filter{DISCONNECTED}.onEach{resetStats()}`
   - 删除 `dataCountStartTime` / `dataCount` / `expectedInterval`
   - `resetStats()` body 裁剪
2. **A28 测试段**：新增 3 条 scenario（透传 / packetLossRate 反推 / DISCONNECTED reset）
3. **A37 接口段**（BREAKING）
   - `TrackCatalog.getAllTracks()` → `suspend fun`
   - `PresetTrackCatalog` / `PresetTracks`（待 grep 确认文件名）实现对齐
   - `ReplayAlignedTrackCatalog` 改造（去 `by lazy`、加 `withContext(IO)` + 双检锁）
4. **A37 ViewModel 段**：`TestSessionViewModel._availableTracks` 初始化为空 + `viewModelScope.launch { getAllTracks() }`
5. **A37 测试段**：新增 `FakeBlockingReplayTrackSource` + `doesNotBlockCallerThreadOnFirstCall` + `init_doesNotSynchronouslyReadCatalog`
6. **回归段**：`TrackCatalogTest` 现有测试改 `runTest`；`:feature:test:testDebugUnitTest` 全绿

### Rollback 策略

- 单 commit 实施 + 完整测试覆盖，rollback = `git revert` 整 commit
- 两个 capability 彼此正交，单独回滚任一不影响另一

## Open Questions

无未决问题。以下项已在上述 Decisions 中固化：
- ~~resetStats 订阅层~~ → D1（GpsDataViewModel 自订阅）
- ~~packetLossRate 公式~~ → D2（从 `data.frequency` 反推）
- ~~加载态表达~~ → D6（空列表，不新增 sealed class）
- ~~加载时序 dispatcher~~ → D6（`viewModelScope.launch { }` 不预设 IO）
- ~~AssetLoader 可注入~~ → D7（复用已有 `ReplayTrackSource`）

tasks 阶段需要 grep 确认的待核实项（不是设计决策）：
- `PresetTracks.kt` vs `PresetTrackCatalog.kt` 实际文件名
- `dataCount` 字段是否存在于 `GpsDataViewModel`（code 读只看到 `dataCountStartTime` 但 line 99 `dataCount++` 使用了，应该存在）
- `DataStats` / `DataQuality` 初始态的具体表达方式（避免 `DataStats.EMPTY` 这类不存在的符号）
