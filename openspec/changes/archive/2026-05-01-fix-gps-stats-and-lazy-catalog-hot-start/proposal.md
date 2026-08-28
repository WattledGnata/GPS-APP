## Why

战役 F 性能线遗留两条运行时口径 / 冷启动阻塞攻击点：(1) `GpsDataViewModel.updateDataStats` 用 `dataCount / (now - dataCountStartTime)` 做**会话级累计平均**，`dataCountStartTime` 仅首次置位且 `resetStats` 无调用方，设备连 1 小时后 `frequency` 对近期波动完全麻木，丢帧不显形；同时常量 `expectedInterval = 100L` 硬编码 10Hz 假设与实际 ESP32 25Hz 不一致；(2) `TrackCatalog.getAllTracks()` 非 suspend，`TestSessionViewModel` 构造期（通常在 Main）同步调用，底下 `ReplayAlignedTrackCatalog.by lazy` 首次触发 asset 读 + Gson parse，冷启动主线程阻塞数十毫秒、replay JSON 变大会线性放大。两条同属"运行时 / 冷启动的数据面卫生"主题，合并实施 scope 正交、回归面小。

## What Changes

### A28 · GPS 运行时 stats 口径收敛到 parser 1 秒滑窗

- `GpsDataViewModel.updateDataStats` 中的累计平均逻辑（`dataCount` 计数 + `dataCountStartTime` 锁定 + `dataCount / elapsedSeconds`）**整体删除**；`stats.frequency` 改为**直接透传** `data.frequency`（parser 已在每帧写入 1 秒滑窗计算结果）
- 删除 `dataCountStartTime` / `dataCount` 字段及其所有消费点
- 删除 ViewModel 内 `expectedInterval = 100L` 常量（10Hz 假设口径污染）；`packetLossRate` 改用**从 `data.frequency` 反推的采样周期**，公式定锚如下（Review v1 Open question 修补，进入 design/specs 前固化）：

  ```kotlin
  // A28 新公式：expectedSampleInterval 从 data.frequency 反推，不硬编码
  // data.frequency ≤ 0（parser 尚未算出 1 秒窗）时 packetLossRate 回退 0
  val expectedSampleInterval = if (data.frequency > 0.0) 1000.0 / data.frequency else 0.0
  val packetLossRate = if (expectedSampleInterval > 0.0 && dataAge > expectedSampleInterval * 2) {
      ((dataAge - expectedSampleInterval) / expectedSampleInterval).coerceIn(0.0, 100.0)
  } else {
      0.0
  }
  ```

  - 25Hz 设备：expectedSampleInterval = 40ms，dataAge > 80ms 才计丢
  - 10Hz 设备：expectedSampleInterval = 100ms，dataAge > 200ms 才计丢
  - 暖启动 / 丢连：`data.frequency == 0.0` 时直接 0，避免 NaN / 错误告警
- `resetStats()` 保留并激活调用链：**`GpsDataViewModel.init` 内自订阅自身已有的 `connectionState: StateFlow<ConnectionState>`**（line 34 已存在，来自 `gpsDataRepository.connectionState`），在状态迁入 `DISCONNECTED` 时触发 `resetStats()`，避免跨连接会话 stats 残留。**不走跨 ViewModel 路径**：`TestSessionViewModel` 只负责 A37 track 加载，**不介入** stats 重置；`BleConnection` 也不直接调 ViewModel，严守分层边界
- **Non-goal**：不暴露 `RaceChronoParser.gpsFrequency` 为 public，不新增 parser public mutable state；不把 `EXPECTED_SAMPLE_INTERVAL_MS = 40L` 提为生产常量（后续支持 10Hz / 50Hz 设备时避免协议层耦合）

### A37 · TrackCatalog 冷启动脱离主线程

- **BREAKING**：`TrackCatalog.getAllTracks(): List<Track>` → `suspend fun getAllTracks(): List<Track>`（接口签名变更，所有实现与消费方一并迁移）
- `PresetTracks.getAllTracks` 直接 return 内存列表（`suspend fun` 不强制 IO dispatcher，只为类型契约对齐）
- `ReplayAlignedTrackCatalog.getAllTracks` **MUST** 在内部 `withContext(Dispatchers.IO)` 包裹 asset 读 + Gson parse —— 这是 IO 边界的**唯一防线**，实现自负责不在 Main 上做阻塞 I/O，调用方是否使用 IO coroutine 只是额外保护、不能取代实现侧契约（Review v1 P2-1 修补：不能写"或调用方自带 IO context"作为可选项）
- `TestSessionViewModel` 初始化给空列表 / 加载态，然后 `viewModelScope.launch(Dispatchers.IO) { ... }` 异步加载 `_availableTracks`
- **Non-goal**：**拒收 App 启动级预热入口方案**（`by lazy(mode = NONE)` + 预热 coroutine），理由：生命周期太隐式、测试更绕、与 ViewModel 实例化时序耦合；不引入任何新的 App-scope hook

### 测试契约

- **A28**：新增 `GpsDataViewModelTest.frequency_transparentlyMirrorsLatestParserFrequency` —— 先喂若干帧 `data.frequency = 25.0`（稳态），再喂一帧 `data.frequency = 1.0`（掉到 1Hz），断言 `stats.frequency` 在最后一帧到达后**立即** = 1.0。硬区分 v1 累计平均：v1 会显示接近历史均值（> 10 Hz），v2 直接透传最新值。（Review v1 P1-2 修补：不依赖"后 10 秒没有 emission"的场景——updateDataStats 不会自驱动下降；改为"用一帧低频 data.frequency 驱动 updateDataStats"硬区分）
- **A28 补**：`frequency_packetLossRate_derivedFromFrameFrequencyNotHardcoded25Hz` —— 对 `data.frequency = 10.0`、`dataAge = 300ms` 的低频设备，断言 packetLossRate 基于 100ms 期望周期（而非硬编码 40/100ms）计算；对 `data.frequency = 25.0`、`dataAge = 30ms` 稳态，断言 packetLossRate = 0
- **A28 补**：`resetStats_onConnectionStateDisconnected_clearsStats` —— ViewModel `init` 后喂若干帧稳定 stats，发射 `ConnectionState.DISCONNECTED`，断言 `_dataStats.value` 回到等价初始态（具体常量 / data class 由 specs/tasks 阶段按当时代码形状定，不预设 `DataStats.EMPTY` 这类不存在的符号）；验证自订阅链路生效、分层正确
- **A37**：新增 `ReplayAlignedTrackCatalogTest.getAllTracks_doesNotBlockCallerThreadOnFirstCall` —— suspend fun 在 Main dispatcher 调用时不触发 asset 读（通过可注入 `AssetLoader` fake + 线程断言）
- **A37 补**：`TestSessionViewModelTrackLoadingTest.init_doesNotSynchronouslyReadCatalog` —— ViewModel 构造返回后 availableTracks 仍为空，`viewModelScope.launch` 完成后才有值

## Capabilities

### New Capabilities

- `gps-runtime-stats`：GPS 运行时统计口径契约。定义 `stats.frequency` 与 parser 1 秒滑窗输出的关系、`resetStats` 的触发时机（`ConnectionState → Disconnected`）、以及 stats 对"近期波动"的响应性下界
- `track-catalog-hot-start`：赛道目录冷启动非阻塞契约。定义 `TrackCatalog.getAllTracks()` 的 suspend 语义、`PresetTracks` / `ReplayAlignedTrackCatalog` 两种实现的 dispatcher 契约、以及 ViewModel 消费方异步加载的流程

### Modified Capabilities

无。`openspec/specs/` 当前为空，本 change 全部走 New Capabilities 新增路径。

## Impact

### 受影响模块路径

- `feature/test/src/main/java/com/blazepush/feature/test/repository/TrackCatalog.kt`（接口 `suspend` 签名变更）
- `feature/test/src/main/java/com/blazepush/feature/test/repository/PresetTracks.kt`（实现签名对齐）
- `feature/test/src/main/java/com/blazepush/feature/test/repository/ReplayAlignedTrackCatalog.kt`（`withContext(Dispatchers.IO)` 包裹）
- `feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt`（**A37 范围**：异步加载 `_availableTracks`；**不介入 A28**）
- `feature/test/src/main/java/com/blazepush/feature/test/viewmodel/GpsDataViewModel.kt`（**A28 范围**：删除累计平均、透传 `data.frequency`、`init` 内自订阅 `connectionState` 触发 `resetStats`、packetLossRate 改用 `data.frequency` 反推周期）
- `feature/test/src/test/java/com/blazepush/feature/test/viewmodel/GpsDataViewModelTest.kt`（新增 3 条 scenario：frequency 透传 + packetLossRate 反推采样周期 + DISCONNECTED 触发 resetStats）
- `feature/test/src/test/java/com/blazepush/feature/test/repository/ReplayAlignedTrackCatalogTest.kt`（新增 1 条 scenario，若文件不存在则新建）
- `feature/test/src/test/java/com/blazepush/feature/test/viewmodel/TestSessionViewModelTrackLoadingTest.kt`（新增测试文件 1 条 scenario）

### 不受影响的边界

- `core/bluetooth/src/main/java/com/blazepush/core/bluetooth/parser/RaceChronoParser.kt`：**零改动**（本 change 仅消费 parser 已写入的 `GpsData.frequency`，不改 parser 内部状态）
- `core/bluetooth/src/main/java/com/blazepush/core/bluetooth/BleConnection.kt`：**零改动**（`resetStats` 触发走 ViewModel 订阅 `ConnectionState`，不穿透分层）
- `core/domain/src/main/java/com/blazepush/core/domain/model/GpsData.kt`：**零改动**（`frequency` 字段已存在）

### 协议兼容性

**N/A** — 本 change 不涉及 `GpsData` 字段增删改，不涉及 BLE / RaceChrono 协议层改动，发射端 simulator 无需配合变更。

### 双端任务范围

**仅接收端（gps-app）** — 不涉及发射端 simulator 改动。

## Non-goals（scope 硬边界）

- **不碰 A22**（`LapDebugExecutionScreen` haversine），Round 3 处理
- **不碰 A30**（`AnomalyDetector` / `DataInterpolator` / `DataSmoothing` 孤岛），Round 4 处理
- **不碰 A35**（UI `currentLap` +1 与 Ready 冲突），Round 5 处理
- 不引入 App 启动级预热入口（A37 显式选 suspend 方案）
- 不暴露 `RaceChronoParser.gpsFrequency` 为 public（A28 消费 `GpsData.frequency`）
- 不新增生产侧 `EXPECTED_SAMPLE_INTERVAL_MS` 常量（避免把 RaceChrono 当前采样率冻结为全局协议）
- 不改 `GpsData` / `ConnectionState` / BLE 协议字段

## 验收门槛（进入 `/opsx:apply` 前）

- `openspec validate fix-gps-stats-and-lazy-catalog-hot-start --strict` 通过
- 下游零回归：`:feature:test:testDebugUnitTest` + `:core:bluetooth:testDebugUnitTest` + `:core:domain:test` + `:app:compileDebugKotlin`
- backlog A28 / A37 分别迁 🟢 `pending_review` + 附录表格状态列同步
