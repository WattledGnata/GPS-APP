# GPS 数据读取与过滤链路 Review（2026-04-22）

> 分支：`feature_ctg_20260405_laptime_mainline`
>
> 目的：把 GPS 从 BLE 设备到 ViewModel 的采集、解析、过滤链路梳理一遍，作为后续稳定性评审与调优决策的依据。所有陈述都带代码证据。

---

## 一、链路全景

```
ESP32 M9N GPS        ── BLE Notify ──▶  BleConnection
(RaceChrono 协议)                          │
                                           │ (UUID, ByteArray)
                                           ▼
                                    BluetoothDataSource
                                           │
                                           │ route by UUID → RaceChronoParser
                                           │   · 00000003  → parseGpsData  (20 字节主包)
                                           │   · 00000004  → parseGpsTimeData (3 字节时间包)
                                           ▼
                                    _dataFlow: StateFlow<GpsData>
                                           │
                                           ▼
                                    GpsDataRepository
                                           │
                                           ▼
                                    GpsDataViewModel
                                           │   ┌────────────────────────────┐
                                           │   │ updateDataStats → DataQualityEvaluator
                                           │   └────────────────────────────┘
                                           ▼
                                    TestSessionViewModel
                                           │
                                           ├── gpsDataFilter.process(gpsData)  → FilteredGpsData
                                           │      (异常判定 / 中位数滤波 / 一致性因子)
                                           │      │
                                           │      ├── preTriggerBuffer（2s 滑窗）
                                           │      ├── 加速/刹车触发判定 (processFilteredData)
                                           │      └── TestSession（加减速测试）
                                           │
                                           └── bridgeGpsToLapTiming(gpsData)
                                                  → LapTimingEngine
                                                  （圈速走的是原始 GpsData，**不经过 GpsDataFilter**）
```

关键设计意图：

- **一个数据源点**：`BluetoothDataSource._dataFlow` 是整个 app 唯一的 GPS 发射口（`BluetoothDataSource.kt:33-34`，注释"唯一的数据输出口"）。
- **解析与过滤分离**：协议解析在 `RaceChronoParser`，异常修正/平滑在 `GpsDataFilter`，二者不共享状态。
- **圈速不走滤波**：`TestSessionViewModel.bridgeGpsToLapTiming(gpsData)` 直接使用上游 `gpsData`，不消费 `FilteredGpsData`。这是刻意保留，避免滤波后的坐标偏移影响门判定。

---

## 二、硬件/协议层

参考文档：`docs/RaceChrono_BLE_Protocol.md` + `docs/RaceChrono_ESP32_M9N.ino` + `docs/esp32-test-device.md`

- **发射端**：ESP32 + u-blox M9N，固件直接实现 RaceChrono 的 BLE profile。
- **服务 UUID**：`00001ff8-0000-1000-8000-00805f9b34fb`（`BleConnection.kt:29`）
- **两个 characteristic**：
  - `00000003-...` → GPS 主包（20 字节）
  - `00000004-...` → GPS 时间包（3 字节）
- **CCCD**：`00002902-...`，启用 notify。
- **MTU**：主动请求 31 字节（`BleConnection.kt:85`）以确保 20 字节主包可一次读完；失败也会继续 enable notify（`BleConnection.kt:93-101`）。

### GPS 主包（20 字节，big endian，`RaceChronoParser.kt:110-200`）

| 字节 | 字段 | 编码 |
|---|---|---|
| 0 | `syncBits[7:5] \| timeSinceHourStart[20:16]` | bit7-5 同步位，低 5 位为时间高位 |
| 1–2 | `timeSinceHourStart[15:0]` | **每单位 = 2ms**（`RaceChronoParser.kt:153`） |
| 3 | `fixQuality[7:6] \| satellites[5:0]` | 定位状态 + 卫星数 |
| 4–7 | latitude（int32，度 × 1e7） | `RaceChronoParser.kt:164` |
| 8–11 | longitude（int32，度 × 1e7） | `RaceChronoParser.kt:171` |
| 12–13 | 海拔 | bit15=0: `raw/100 − 500`；bit15=1: `(raw&0x7FFF)×10/100 − 500`（`RaceChronoParser.kt:173-181`） |
| 14–15 | 速度 | 同海拔，支持双精度切换（`RaceChronoParser.kt:183-189`） |
| 16–17 | bearing（uint16，度 × 100） | `RaceChronoParser.kt:192` |
| 18 | HDOP × 0.1 | `RaceChronoParser.kt:196` |
| 19 | VDOP × 0.1 | `RaceChronoParser.kt:199` |

### GPS 时间包（3 字节，`RaceChronoParser.kt:57-107`）

- `dateAndHour = (year-2000)×8928 + (month-1)×744 + (day-1)×24 + hour`
- 首字节 bit7-5 存 `syncBits`，供主包对齐；时间包设置 `protocolTimeReference = (syncBits, hourStartMillis)`。
- 主包时间戳还原：`protocolTimeRef.hourStartMillis + timeSinceHourStart`（`RaceChronoParser.kt:257-261`）。
- **若 `syncBits` 不匹配或尚未收到时间包** → 回落到 `System.currentTimeMillis()`（`RaceChronoParser.kt:257-261`）。这是本链路的一个真实裂缝：
  - 回落后的时间戳**与 GPS 真实采样时刻有几十到几百毫秒漂移**，会直接污染 `GpsDataFilter` 的 `dt` 计算和 `LapTimingEngine` 的圈时。

---

## 三、BLE 传输层

### 3.1 `BleConnection`（`core/bluetooth/src/main/java/com/blazepush/core/bluetooth/BleConnection.kt`）

单设备 GATT 生命周期。要点：

- **串行启用通知**：`pendingCharacteristics` 队列 + `isWritingDescriptor` 互斥（`BleConnection.kt:183-242`）。避免并发写 descriptor 导致的 `GATT_BUSY`。
- **两段超时**：
  - 连接超时 `CONNECTION_TIMEOUT_MS = 15_000`（`BleConnection.kt:35, 159-165`）
  - 数据超时 `DATA_TIMEOUT_MS = 10_000`（`BleConnection.kt:36, 244-252`）——**注意这段代码只把 `_connectionState` 置回 `DISCONNECTED`，不主动 disconnect GATT，也不清理 `bluetoothGatt` 引用**。
- **连接认证时机**：`_connectionState` 在收到第一条数据时才升 `CONNECTED`（`BleConnection.kt:134-137`），而不是 `STATE_CONNECTED`。这条规则是故意的——解决部分厂商"连上但没数据"的假连接。
- **已废弃 API 兼容**：`onCharacteristicChanged` 同时实现了新签名（带 value）和旧签名（无 value，内部取 `characteristic.value`），应对 API 33 迁移（`BleConnection.kt:110-124`）。

### 3.2 `BluetoothDataSource`（`core/bluetooth/src/main/java/com/blazepush/core/bluetooth/BluetoothDataSource.kt`）

- 持有唯一 `_dataFlow: MutableStateFlow<GpsData>`（初值 `GpsData.Empty`）。
- `onDataReceived(uuid, rawData)` 回调里按 UUID 路由到 parser 的两个入口（`BluetoothDataSource.kt:51-55`）。
- 每次解析后强制 `copy(isConnected = true)`（`BluetoothDataSource.kt:56`）——哪怕 parser 出错也会把 `isConnected` 置 true。⚠️ 这意味着 `GpsData.isConnected` 只代表"曾经收到 BLE 回调"，不代表当前数据有效。
- 观察 `BleConnection.connectionState` 单独一个 collect job，桥接到自己的 `_connectionState`（`BluetoothDataSource.kt:60-68`），断连时取消 `connectionCollectJob`（`BluetoothDataSource.kt:85-87`）。

### 3.3 `BleDeviceManager`（`core/bluetooth/src/main/java/com/blazepush/core/bluetooth/BleDeviceManager.kt`）

- 组合 `BluetoothDataSource` + `BleDeviceScanner`。
- 启动时调 `autoReconnectLastDevice()` —— ⚠️ `lastDeviceAddress` 目前硬编码为 `null`（`BleDeviceManager.kt:59`），TODO 未落地，自动重连实际不工作，会直接 fallback 到 `startScan()`。
- 权限闸门：`startScan()` 前先 `PermissionChecker.hasAllRequiredPermissions(context)`（`BleDeviceManager.kt:102`）。

### 3.4 `ConnectionManager`（`core/bluetooth/src/main/java/com/blazepush/core/bluetooth/ConnectionManager.kt`）

- **假连接检测**：5 秒周期扫描，超过 `INACTIVE_THRESHOLD = 10_000ms` 无数据 → 置 `isFakeConnection=true`，并调 `triggerReconnect()`（`ConnectionManager.kt:88-127`）。
- 自己维护 `currentDeviceAddress`，外部通过 `setCurrentDevice(address)` 注入（`ConnectionManager.kt:65-67`）。
- ⚠️ **注意**：这套逻辑与 `BleConnection` 内置的 10s `DATA_TIMEOUT_MS` 有重叠，两层都在做无数据检测，但行为不同：
  - `BleConnection` 超时：只改 `_connectionState`。
  - `ConnectionManager` 超时：主动 `disconnect() + reconnect()`。
  - 二者不互相感知，可能互相打架（先被 `BleConnection` 改成 `DISCONNECTED`，`ConnectionManager.checkForFakeConnection` 的 `currentState == CONNECTED` 判断就失效）。

---

## 四、解析层：`RaceChronoParser`

文件：`core/bluetooth/src/main/java/com/blazepush/core/bluetooth/parser/RaceChronoParser.kt`

### 4.1 职责边界

- **有状态**：频率窗（`gpsDataTimestamps`，1 秒窗，每 500ms 刷一次 `gpsFrequency`）、累计距离（`totalDistance`）、时间基准（`protocolTimeReference`）。
- 必须在同一连接内复用同一实例。`BluetoothDataSource` 构造时注入（`BluetoothDataSource.kt:23`）。
- `reset()` 在新连接/新会话时清空所有状态（`RaceChronoParser.kt:46-55`）。

### 4.2 非关键路径容错

- **Frequency 计算**：整段包在 try/catch 内，失败只记 log（`RaceChronoParser.kt:201-216`）。
- **Tracking 计算**：同上，且只在 `fixQuality > 0 && satellites >= 3` 时推进（`RaceChronoParser.kt:219-255`）。
- ⚠️ 这两段是"非 critical"容错，但 `isTestReady = satellites >= 6 && hdop < 2.0`（`RaceChronoParser.kt:275`）是直接写进 `GpsData` 的——没有做额外容错，有异常会穿透到业务层。

### 4.3 已知陷阱

- **20 字节包长度校验**：`data.size < 20` 静默返回上一条 `currentData`（`RaceChronoParser.kt:136-140`），同时日志被注释掉（说明 25Hz 刷屏）。短包会产生"幽灵重复数据"。
- **时间戳落回 `System.currentTimeMillis()`**：`protocolTimeReference == null` 或 `syncBits` 不匹配都会落回（`RaceChronoParser.kt:257-261`）。开机后**第一个主包一定会落回**，因为时间包还没到。

---

## 五、数据仓库与模型

### 5.1 `GpsDataRepository`（`core/bluetooth/src/main/java/com/blazepush/core/bluetooth/GpsDataRepository.kt`）

只做纯代理：`gpsDataFlow` / `connectionState` 转发，`connect/disconnect` 转发。无逻辑。

### 5.2 `GpsData`（`core/domain/src/main/java/com/blazepush/core/domain/model/GpsData.kt`）

```
timestamp, speed (km/h), latitude, longitude, altitude, bearing,
satelliteCount, hdop, vdop, frequency (Hz),
isConnected, isTestReady, errorMessage, fixQuality
```

- `GpsData.Empty`（`GpsData.kt:35-50`）用作 StateFlow 初值。
- `toGcj02()`（`GpsData.kt:29-32`）单独返回一份火星坐标副本，仅用于地图展示；**不存回 `_dataFlow`**——原始流里永远是 WGS84。

### 5.3 `CoordTransform`（`core/domain/src/main/java/com/blazepush/core/domain/CoordTransform.kt`）

标准国测局 WGS84↔GCJ-02，`isInChina(lat in 18.0..54.0 && lon in 72.0..135.0)` 外直接返回原值。纯函数，无状态。

---

## 六、`GpsDataViewModel`（`feature/test/src/main/java/com/blazepush/feature/test/viewmodel/GpsDataViewModel.kt`）

- 全局单例，所有页面共享（`GpsDataViewModel.kt:19-20`）。
- **向下游暴露的是原始 `gpsData`**（未经 `GpsDataFilter`）：
  ```
  val gpsData: StateFlow<GpsData> = gpsDataRepository.gpsDataFlow  // GpsDataViewModel.kt:32
  ```
- `updateDataStats(data)`（`GpsDataViewModel.kt:82-120`）：
  - `dataAge = now - data.timestamp`（如果 `timestamp > 0`，否则用 `now - lastDataTime`）
  - `frequency` 是从 ViewModel 启动开始累计 `dataCount / elapsedSeconds`——与 `RaceChronoParser` 里的 1 秒滑窗频率是**两套独立计数**，数值会不同。
  - `packetLossRate` 用单次间隔粗估（`(dataAge - expectedInterval) / expectedInterval`），`expectedInterval = 100L`（暗含期望 10Hz，但实际 ESP32 是 25Hz，这会把正常数据误判为丢包）。⚠️
  - 最终丢给 `DataQualityEvaluator.calculateQuality` 产 `_dataQuality` 状态。
- **无 filter 消费**：GpsDataViewModel 里仅用 `DataSmoothing` 的引用（`resetStats()` 调 `dataSmoothing.reset()`），但没看到任何实际的 smooth 调用。`DataSmoothing` 在这一层是**闲置**的。

---

## 七、过滤主路径：`GpsDataFilter`

文件：`core/domain/src/main/java/com/blazepush/core/domain/usecase/GpsDataFilter.kt`

规范：`docs/superpowers/specs/2026-03-21-gps-data-filter-design.md`（头部注释指向）

### 7.1 构造参数

```
windowSize = 9
maxAcceleration = 25.0   // 2.5G
maxDeceleration = 30.0   // 3.0G
```

### 7.2 核心流程（`GpsDataFilter.process`，`GpsDataFilter.kt:30-98`）

1. `calculateAcceleration(raw)`：基于 `previousRaw` 算 `dv/dt`（`GpsDataFilter.kt:117-127`）。
2. `isPhysicalConstraintViolation(raw)`：速度变化超过 `maxAccel/maxDecel × dt × 3.6` → `isAnomaly = true`（`GpsDataFilter.kt:132-152`）。
3. **信号丢失重置**：`dtFromPrevious > 0.2s` → `previousRaw = null`，下一帧作为新基准（`GpsDataFilter.kt:38-42`）。
4. `checkPositionVelocityConsistency(raw)`：见 7.4。
5. 四个独立滚动窗口：`speedWindow` / `latWindow` / `lonWindow` / `bearingWindow`（`GpsDataFilter.kt:18-21`）。同步滑动，大小超过 9 后丢掉最老的。
6. 输出：
   - speed/lat/lon：窗口 ≥3 → 中位数，否则原值
   - bearing：窗口 ≥3 → `circularMedian()`（0/360 循环正确），否则原值
7. `calculateConfidence(isAnomaly, hdop, consistencyFactor)` → `FilteredGpsData.confidence`
8. 更新 `previousRaw` / `previousPosition`

### 7.3 `circularMedian`（`GpsDataFilter.kt:253-270`）

- 将每个角度投影为单位向量 `(sin, cos)` 求和，`atan2(sumSin, sumCos)` 得平均方向，再规范化到 `[0, 360)`。
- 头部注释注明：左转 350°→10° 时普通中位数会给 40°（错），这里给 ~20°（对）。
- ⚠️ 严格来说这是**向量均值**而非中位数。对称分布没问题，非对称分布（长尾）会被拉偏，但比线性中位数好。

### 7.4 位置-速度一致性检验（`GpsDataFilter.kt:177-221`）

- `dt = (current.ts - prev.ts) / 1000`；`dt ≤ 0 || dt > 0.2` → factor=1.0，不做检查。
- 平面近似位移：
  ```
  deltaLatM = |Δlat| * 111320
  deltaLonM = |Δlon| * 111320 * cos(lat)
  distanceM = hypot(deltaLatM, deltaLonM)
  ```
- ⚠️ `|Δlat|`+`|Δlon|` 后再 hypot 是正确的（绝对值只影响各分量），但**不适合跨经度/纬度带的长位移**。当前用于单帧位移（<0.2s × 飞行速度）OK。
- `distanceM < 0.5m` → 跳过（GPS 噪声会淹没）。
- `vImpliedKmh = (distanceM / dt) * 3.6`，与 `current.speed` 比较。
- 容差（`getConsistencyTolerance`）：速度 <5 → 3 km/h，<60 → 5 km/h，≥60 → 10 km/h。
- `ratio = speedDiff / tolerance`：
  - ≤1 → factor=1.0
  - ≤2 → factor=0.8
  - ≤3 → factor=0.6
  - \>3 → factor=0.3 且 `isPositionAnomaly = true`
- bearing 变化 >30°/s → 降权 × 0.8；HDOP >3.0 → 降权 × 0.5。

### 7.5 输出：`FilteredGpsData`（`GpsDataFilter.kt:276-290`）

```
speed, latitude, longitude, altitude, bearing,
acceleration,
confidence, isAnomaly,
isTestTriggered = false,   // 占位，当前 filter 不置位
timestamp,
raw: GpsData,              // 原始引用留着
consistencyFactor,
isPositionAnomaly
```

- `isTestTriggered` 字段默认 false，且 filter 里**没有任何写入**——这是给下游 ViewModel 的触发判定预留的位，但目前下游没写回（`TestSessionViewModel` 自己维持 `consecutiveTriggerCount`）。

---

## 八、辅助 usecase（domain 层）

| 文件 | 实际消费者 | 状态 |
|---|---|---|
| `AnomalyDetector.kt` | **无调用者** | 独立类，定义了 stale/jump/range/少星/零坐标 5 类异常，但没有任何 ViewModel 或 filter 消费。⚠️ **孤立代码**。 |
| `DataQualityEvaluator.kt` | `GpsDataViewModel.updateDataStats` | 用于 UI 质量灯。加权：sat 25% / hdop 25% / age 20% / 丢包 15% / 频率 15%（`DataQualityEvaluator.kt:131-137`）。 |
| `DataSmoothing.kt` | `GpsDataViewModel` 构造注入但仅调 `reset()` | 5 点移动平均，**实际未在流上使用**。⚠️ 孤立实现。 |
| `DataInterpolator.kt` | **无调用者** | 线性插值生成缺失采样。⚠️ 孤立实现。 |
| `GpsDataFilter.kt` | `TestSessionViewModel.processFilteredData` | 主过滤入口。 |
| `SmartTestLauncher.kt` | `TestSessionViewModel` | 启动条件检查（加减速测试用）。 |
| `CalculateResultUseCase.kt` | 测试完成后结果计算 | 不在本 review 范围。 |

---

## 九、下游分发：`TestSessionViewModel`

文件：`feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt`

`init` 块里唯一的 `gpsData.collect`（`TestSessionViewModel.kt:112-123`）：

```kotlin
gpsDataViewModel.gpsData.collect { gpsData ->
    val filteredData = gpsDataFilter.process(gpsData)  // ① 过滤
    lastDataTime = gpsData.timestamp
    updatePreTriggerBuffer(filteredData)               // ② 2s 预触发滑窗
    updateLaunchStatus(gpsData)                        // ③ 启动条件状态
    processFilteredData(filteredData)                  // ④ 加减速测试状态机
    bridgeGpsToLapTiming(gpsData)                      // ⑤ 圈速（用原始 gpsData）
}
```

**关键点**：
- **加减速测试链路消费 `FilteredGpsData`**（经过滤/一致性校验），触发阈值见 `TestSessionViewModel.kt:57-60`：
  - `TRIGGER_ACCELERATION_THRESHOLD = 1.0 m/s²`
  - `TRIGGER_CONFIRMATION_COUNT = 5`
  - `STANDSTILL_SPEED_THRESHOLD = 3.0 km/h`
  - `STANDSTILL_CONFIRMATION_COUNT = 3`
- **圈速链路消费原始 `gpsData`**（`TestSessionViewModel.kt:120, 301-335`），**不经过 filter**——刻意保留，避免滤波坐标污染 gate 判定。
- `preTriggerBuffer` 是 `FilteredGpsData` 列表，保留 `PRE_TRIGGER_DURATION_MS = 2000L` 内的所有样本，触发时整个打包塞进 `TestSession`（`TestSessionViewModel.kt:197-203, 272-281`）。

---

## 十、测试矩阵

| 测试文件 | 用例数 | 状态/备注 |
|---|---|---|
| `app/src/test/java/com/blazepush/data/service/parser/RaceChronoParserTest.kt` | 40 | RaceChrono 协议解析覆盖 |
| `core/bluetooth/src/test/java/com/blazepush/core/bluetooth/parser/RaceChronoParserProtocolTimeTest.kt` | 2 | 协议时间包专项 |
| **`app/src/test/java/com/blazepush/domain/usecase/GpsDataFilterTest.kt`** | **27** | **⚠️ 编译失败** — package 仍在 `com.blazepush.domain.usecase`，import 也是 `com.blazepush.domain.model.GpsData`，但实际源码已迁到 `com.blazepush.core.domain.*`。`./gradlew :app:testDebugUnitTest --tests "com.blazepush.domain.usecase.GpsDataFilterTest"` 直接 fail 在 `compileDebugUnitTestKotlin`。 |

- **`GpsDataFilterTest` 死测试**是最大的隐患：27 个用例（信号丢失重置、异常检测、滤波、加速度、一致性等）全部**处于无法运行状态**，任何 filter 行为改动都不会被任何自动化测试发现。
- `AnomalyDetector` / `DataSmoothing` / `DataInterpolator` / `DataQualityEvaluator` 全部**无单测**（也无调用者，见第八节）。
- BLE 层（`BleConnection` / `BluetoothDataSource` / `ConnectionManager` / `BleDeviceManager`）全部**无单测**。

---

## 十一、已知裂缝（Review 重点）

### 11.1 `GpsDataFilterTest` 未随 package 迁移

- **现象**：测试在旧 package `com.blazepush.domain.usecase`，源码在 `com.blazepush.core.domain.usecase`。编译即 fail。
- **影响**：filter 回归保护为 0。
- **修法**：把文件迁到 `core/domain/src/test/java/com/blazepush/core/domain/usecase/`，import 改为 `com.blazepush.core.domain.*`，并进入主线回归。
- **⚠️ 补遗（2026-04-22 对抗 review 2.8 / 3.3）**：同一原因，`app/src/test/java/com/blazepush/viewmodel/TestSessionViewModelTest.kt` 也处于不可编译状态（package 仍在 `com.blazepush.viewmodel`，import 仍引用 `com.blazepush.domain.*`）。一起迁移，否则只修 `GpsDataFilterTest` 仍会在 `:app:compileDebugUnitTestKotlin` 失败。

### 11.2 `AnomalyDetector` / `DataInterpolator` / `DataSmoothing` 是孤岛

- 只有定义，没有任何生产者调用。
- **决策**：要么接线（`GpsDataFilter` 里组合 `AnomalyDetector` 给更精细的异常原因，`DataInterpolator` 在信号丢失时补点），要么按"避免半成品"删掉。

### 11.3 `BleConnection` 数据超时不清理 GATT

- `BleConnection.kt:244-252` 数据超时只改 `_connectionState`，不 `disconnect()`/`close()`。
- **副作用**：`BluetoothGatt` 引用泄露；外层 `ConnectionManager` 的重连路径要先 `disconnect()` 才能释放底层，二者时序不匹配。
- **修法**：超时时应一并 `bluetoothGatt?.disconnect()` + `close()` + `bluetoothGatt = null`。

### 11.4 `BluetoothDataSource` 把失败解析也标 `isConnected = true`

- `BluetoothDataSource.kt:56` 无条件 `copy(isConnected = true)`。
- **副作用**：`GpsData.isConnected` 不能作为"当前数据可信"的闸门。下游若依赖这个字段做条件（目前 `ConnectionManager` 就是这么做的，`ConnectionManager.kt:42`），会在短包/解析异常时产生误判。

### 11.5 假连接恢复未实现（ConnectionManager 已删）

> **战役 G `fix-ble-connection-lifecycle`（2026-04-24）已采纳方案 B 整体删除
> `ConnectionManager.kt`**。原节叙述的"两层超时互相打架"假设与代码事实相反
> —— `ConnectionManager` 从未被 DI 注册 / 实例化。2026-04-22 对抗 review
> 2.1 / 3.1 指出后，战役 G proposal Alternatives § A 再次拒收方案 A 接线，
> 明确以方案 B 收敛连接职责。本节已重写反映战役 G 落地后状态。

- **删除结论**：`ConnectionManager.kt`（145 行）整文件移除；`AppModule.kt` 零
  引用；全仓 grep 零命中；A42（`ConnectionManager.init` 空 collect 占 IO
  协程）随文件删除自动闭环。
- **战役 G 后的职责矩阵**：GATT 连接 / 数据超时检测 / GATT 释放 =
  `BleConnection`（唯一持有 `BluetoothGatt`）；数据聚合 + `isConnected` 语义 +
  设备切换清旧 = `BluetoothDataSource`；冷启动自动重连 / 扫描 = `BleDeviceManager`。
  不再存在"假连接恢复层"类。
- **统一释放路径**（A40）：`BleConnection.disconnect()` 只触发 `gatt.disconnect()`
  异步；`close() + null` 收敛到 `onConnectionStateChange(STATE_DISCONNECTED)`
  回调 —— 主动 disconnect、数据超时、远端断连三条路径共用一条释放流程。
- **未完成的完整自愈**：ESP32 拔电源后"15 秒自动重连"端到端链路依赖
  `BluetoothDeviceRepository` 持久化 `lastDeviceAddress` + `BleDeviceManager`
  自愈循环，两者属于下一战役 `fix-ble-reconnection-layer` 范围。战役 G 收敛为
  "DISCONNECTED 信号可靠传导 + GATT 资源干净释放"，为自愈层铺路（见
  `docs/superpowers/reviews/attack-backlog.md` A23 核销条件修订记录）。

### 11.6 autoReconnectLastDevice 实质未实现，且 `else` 分支原先不 fallback

> **战役 G `fix-ble-connection-lifecycle`（2026-04-24）已修复 A29**：`else`
> 分支改为 `startScan()`，冷启动行为与直觉一致。原叙述"冷启动走扫描路径"在
> 战役 G 前与事实相反，基于 2026-04-22 对抗 review 2.10 / 3.2 修正后，战役 G
> 再次重写反映 A29 修复状态。

- **战役 G 前**：`BleDeviceManager.kt:85-87` 的 `else` 分支只
  `Log.d(..., "没有上次连接的设备记录")`，**不调** `startScan()`；冷启动后 app
  不自动扫描，用户必须手动点"扫描"按钮才能看到设备列表。
- **战役 G 后**：`else` 分支追加 `startScan()` 并改 log 为
  `"没有上次连接的设备记录，fallback 到扫描"`；冷启动后 app 自动扫描。
- **代码行为等价性**：原"`if (lastDeviceAddress != null) { ... 超时未连 } ...`"
  分支（line 84）已有 `startScan()` 调用；本次修复让 `else` 分支与该路径对称，
  扫描耗电等价（未新增扫描触发次数，只是让之前"沉默不扫描"变"正常扫描"）。
- **未完成的 TODO**：`lastDeviceAddress` 当前仍硬编码 `null` —— 接入
  `BluetoothDeviceRepository`（`core/data/` 下已有）读 last device address 才能
  实现"上次设备优先 + 失败 fallback 扫描"，属于下一战役
  `fix-ble-reconnection-layer` 范围。

### 11.7 `RaceChronoParser` 开机首包必落回 `System.currentTimeMillis()`

- 时间包到达前，`protocolTimeReference == null`，主包时间戳用系统时钟。
- **副作用**：第一帧 `dt = currentTs - 0`（`previousRaw == null`，filter 会 early return），看似 OK，但**第二帧若仍未收到时间包，`dt` 就是两次系统时钟之差而非协议时刻差**，会把 BLE 抖动误记入 GPS 运动。
- **修法**：`isTestReady` 与 `isTimeSynced`（新字段）分开；未同步前下游明确跳过时间敏感计算（如 filter 的加速度/一致性）。

### 11.8 `RaceChronoParser` 20 字节包短包静默回退

- `RaceChronoParser.kt:136-140` 数据长度不足时直接返回 `currentData`，且日志被注释。
- **副作用**：下游会连续收到两个相同 `timestamp` 的帧，`filter` 里 `dt == 0` 会跳过一致性检查，但 `acceleration = 0` 会被记为真值，影响启动判定。
- **修法**：短包要独立记录 counter 并暴露为 `DataQuality` 里的一个健康指标。

### 11.9 `GpsDataViewModel.packetLossRate` 期望值错误

- `GpsDataViewModel.kt:104-109` 写死 `expectedInterval = 100L`（10Hz），而实际协议 25Hz（≈40ms）。
- **副作用**：正常 25Hz 数据会被判成"正常"（`dataAge > 200ms` 才记丢包），但 `DataQualityEvaluator` 里频率评分又是 10Hz 就满分——两边口径不一致。
- **修法**：把期望频率集中成一个常量，parser/viewmodel/evaluator 共享。

### 11.10 `DataSmoothing` 在 `GpsDataViewModel` 里只 `reset()` 不 smooth

- `GpsDataViewModel.kt:165-169` 只在 `resetStats` 调 `dataSmoothing.reset()`。
- 说明历史上有打算把 smooth 接到 UI 展示流上但没落地。
- **决策**：接线或删除。

### 11.11 `GpsDataFilter` 的 `isTestTriggered` 永远是 false

- `GpsDataFilter.kt:285` 字段定义为 `false` 默认，filter 不写。
- 下游 `TestSessionViewModel` 自己维护触发计数。
- **决策**：把 `isTestTriggered` 从 `FilteredGpsData` 删掉（目前是"画饼字段"）；或让 filter 根据 `consistencyFactor + acceleration` 出一个弱触发 hint。

### 11.12 `GpsDataFilter` 对 lat/lon 做中位数滤波

- `GpsDataFilter.kt:73-74` 对 `latitude` / `longitude` 独立取中位数。
- **副作用**：lat 和 lon 独立取中位数**不等于位置的中位数**——两者分别选了不同时刻的值，有可能产出"轨迹上不存在的点"。在圈速 gate 判定不经过 filter，这里不受影响；但加减速 preTriggerBuffer 里存的坐标会是这种合成点。
- **修法**：按位置向量整体找"最中心样本"，或只对位置做时间加权均值。

---

## 十二、Review 时要追问

- [ ] `GpsDataFilterTest` 为何滞留在 `app` 模块旧 package？是 11.1 遗留还是有意保留？
- [ ] `AnomalyDetector` / `DataInterpolator` / `DataSmoothing` 要接线还是删除？
- [ ] `BleConnection` 和 `ConnectionManager` 的超时策略是否要合并？若合并，保留哪一层的行为？
- [ ] `BluetoothDataSource.isConnected = true` 的无条件置位是否要改为"仅解析成功时"？
- [ ] `RaceChronoParser` 是否需要一个 `isTimeSynced` 字段，替代目前"静默落回系统时钟"？
- [ ] 25Hz vs 10Hz 的期望频率口径要不要在 domain 层定成常量？
- [ ] `GpsDataFilter` 对 lat/lon 独立取中位数要不要换成向量方式？
