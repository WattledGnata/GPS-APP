# GPS测试应用实现计划

**日期**: 2026-03-17
**版本**: 1.0
**基于规范**: `/docs/superpowers/specs/2026-03-17-gps-test-app-redesign.md`

## 目录

- [1. 实现概述](#1-实现概述)
- [2. Chunk 1: 基础架构重构](#2-chunk-1-基础架构重构)
- [3. Chunk 2: 测试核心逻辑](#3-chunk-2-测试核心逻辑)
- [4. Chunk 3: 数据持久化](#4-chunk-3-数据持久化)
- [5. Chunk 4: UI实现](#5-chunk-4-ui实现)
- [6. 测试与验证](#6-测试与验证)
- [7. 提交策略](#7-提交策略)

---

## 1. 实现概述

### 1.1 总体目标

基于设计规范，重构GPS测试应用，解决数据流向不统一问题，实现完整的0-100加速和100-0刹车测试功能。

### 1.2 实现原则

- **单一数据源**：所有UI组件从同一个数据流获取GPS数据
- **分层架构**：UI → ViewModel → UseCase → Repository → DataSource
- **模块化设计**：每个功能独立，便于测试和维护
- **渐进式实现**：按Chunk逐步实现，每个Chunk可独立测试

### 1.3 技术栈

- **语言**: Kotlin
- **UI**: Jetpack Compose
- **依赖注入**: Koin
- **数据库**: Room
- **协程**: Kotlin Coroutines + Flow
- **蓝牙**: Android BLE API

### 1.4 实现顺序

1. **Chunk 1**: 基础架构重构（Bluetooth层 + 数据模型）
2. **Chunk 2**: 测试核心逻辑（TestTemplate + TestSession + UseCases）
3. **Chunk 3**: 数据持久化（文件存储 + 数据库迁移）
4. **Chunk 4**: UI实现（测试执行 + 结果详情 + 历史记录）

---

## 2. Chunk 1: 基础架构重构

### 2.1 目标

- 移除Android Service概念，改为普通Kotlin类
- 统一数据模型（BluetoothData → GpsData）
- 实现单一数据源（BluetoothDataSource）
- 重构包结构（service/ → bluetooth/）

### 2.2 任务清单

#### 2.2.1 创建新的数据模型

- [ ] 创建 `GpsData` 数据类
  - 文件路径: `app/src/main/java/com/race/gps/domain/model/GpsData.kt`
  - 包含所有GPS字段（速度、位置、卫星数、HDOP等）
  - 添加连接状态和测试就绪状态字段
  - 提供 `Empty` 伴生对象作为默认值

```kotlin
package com.race.gps.domain.model

data class GpsData(
    val timestamp: Long,
    val speed: Double,           // km/h
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val bearing: Double,
    val satelliteCount: Int,
    val hdop: Double,
    val vdop: Double,
    val frequency: Double,       // Hz
    val isConnected: Boolean,    // 连接状态
    val isTestReady: Boolean,    // 测试就绪状态（卫星数>=6, HDOP<2.0）
    val errorMessage: String?    // 错误信息
) {
    companion object {
        val Empty = GpsData(
            timestamp = 0L,
            speed = 0.0,
            latitude = 0.0,
            longitude = 0.0,
            altitude = 0.0,
            bearing = 0.0,
            satelliteCount = 0,
            hdop = 0.0,
            vdop = 0.0,
            frequency = 0.0,
            isConnected = false,
            isTestReady = false,
            errorMessage = null
        )
    }
}
```

#### 2.2.2 重构Bluetooth层

- [ ] 创建 `BluetoothDataSource` 接口
  - 文件路径: `app/src/main/java/com/race/gps/data/bluetooth/BluetoothDataSource.kt`
  - 暴露 `gpsDataFlow: Flow<GpsData>` 单一数据流
  - 提供 `connect(device: BluetoothDevice)` 和 `disconnect()` 方法

```kotlin
package com.race.gps.data.bluetooth

import android.bluetooth.BluetoothDevice
import com.race.gps.domain.model.GpsData
import kotlinx.coroutines.flow.Flow

interface BluetoothDataSource {
    val gpsDataFlow: Flow<GpsData>
    suspend fun connect(device: BluetoothDevice)
    fun disconnect()
    fun isConnected(): Boolean
}
```

- [ ] 实现 `BleBluetoothDataSource`
  - 文件路径: `app/src/main/java/com/race/gps/data/bluetooth/impl/BleBluetoothDataSource.kt`
  - 将现有 `BleBluetoothServiceImpl` 逻辑迁移至此
  - 使用 `MutableSharedFlow` 发射 `GpsData`
  - 移除 Service 继承，改为普通类

```kotlin
package com.race.gps.data.bluetooth.impl

import android.bluetooth.BluetoothDevice
import com.race.gps.data.bluetooth.BluetoothDataSource
import com.race.gps.data.parser.RaceChronoParser
import com.race.gps.domain.model.GpsData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class BleBluetoothDataSource(
    private val parser: RaceChronoParser
) : BluetoothDataSource {

    private val _gpsDataFlow = MutableSharedFlow<GpsData>(replay = 1)
    override val gpsDataFlow: Flow<GpsData> = _gpsDataFlow.asSharedFlow()

    private var connected = false

    override suspend fun connect(device: BluetoothDevice) {
        connected = true
        // BLE连接逻辑（从BleBluetoothServiceImpl迁移）
    }

    override fun disconnect() {
        connected = false
        // 断开BLE连接
    }

    override fun isConnected(): Boolean = connected
}
```

- [ ] 实现 `MockBluetoothDataSource`（用于开发测试）
  - 文件路径: `app/src/main/java/com/race/gps/data/bluetooth/impl/MockBluetoothDataSource.kt`
  - 模拟0-100加速数据序列
  - 支持可配置的模拟模式（加速/刹车/静止）

```kotlin
package com.race.gps.data.bluetooth.impl

import android.bluetooth.BluetoothDevice
import com.race.gps.data.bluetooth.BluetoothDataSource
import com.race.gps.domain.model.GpsData
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class MockBluetoothDataSource : BluetoothDataSource {

    private val _gpsDataFlow = MutableSharedFlow<GpsData>(replay = 1)
    override val gpsDataFlow: Flow<GpsData> = _gpsDataFlow.asSharedFlow()

    private var simulationJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override suspend fun connect(device: BluetoothDevice) {
        startSimulation()
    }

    override fun disconnect() {
        simulationJob?.cancel()
    }

    override fun isConnected(): Boolean = simulationJob?.isActive == true

    private fun startSimulation() {
        simulationJob = scope.launch {
            var speed = 0.0
            var time = 0L
            while (isActive) {
                speed = (speed + 2.5).coerceAtMost(120.0)
                _gpsDataFlow.emit(GpsData(
                    timestamp = System.currentTimeMillis(),
                    speed = speed,
                    latitude = 39.9042,
                    longitude = 116.4074,
                    altitude = 50.0,
                    bearing = 0.0,
                    satelliteCount = 8,
                    hdop = 1.2,
                    vdop = 1.5,
                    frequency = 10.0,
                    isConnected = true,
                    isTestReady = true,
                    errorMessage = null
                ))
                delay(100L)
                time += 100
            }
        }
    }
}
```

#### 2.2.3 创建 GpsDataRepository

- [ ] 创建 `GpsDataRepository` 接口
  - 文件路径: `app/src/main/java/com/race/gps/domain/repository/GpsDataRepository.kt`

```kotlin
package com.race.gps.domain.repository

import android.bluetooth.BluetoothDevice
import com.race.gps.domain.model.GpsData
import kotlinx.coroutines.flow.Flow

interface GpsDataRepository {
    val gpsDataFlow: Flow<GpsData>
    suspend fun connectDevice(device: BluetoothDevice)
    fun disconnectDevice()
    fun isConnected(): Boolean
}
```

- [ ] 实现 `GpsDataRepositoryImpl`
  - 文件路径: `app/src/main/java/com/race/gps/data/repository/GpsDataRepositoryImpl.kt`
  - 委托给 `BluetoothDataSource`

```kotlin
package com.race.gps.data.repository

import android.bluetooth.BluetoothDevice
import com.race.gps.data.bluetooth.BluetoothDataSource
import com.race.gps.domain.model.GpsData
import com.race.gps.domain.repository.GpsDataRepository
import kotlinx.coroutines.flow.Flow

class GpsDataRepositoryImpl(
    private val dataSource: BluetoothDataSource
) : GpsDataRepository {

    override val gpsDataFlow: Flow<GpsData> = dataSource.gpsDataFlow

    override suspend fun connectDevice(device: BluetoothDevice) {
        dataSource.connect(device)
    }

    override fun disconnectDevice() {
        dataSource.disconnect()
    }

    override fun isConnected(): Boolean = dataSource.isConnected()
}
```

#### 2.2.4 重构 GpsDataViewModel

- [ ] 重构 `MainViewModel` → `GpsDataViewModel`
  - 文件路径: `app/src/main/java/com/race/gps/viewmodel/GpsDataViewModel.kt`
  - 从 `GpsDataRepository` 收集数据
  - 暴露 `StateFlow<GpsData>` 给UI

```kotlin
package com.race.gps.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.race.gps.domain.model.GpsData
import com.race.gps.domain.repository.GpsDataRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class GpsDataViewModel(
    private val repository: GpsDataRepository
) : ViewModel() {

    private val _gpsData = MutableStateFlow(GpsData.Empty)
    val gpsData: StateFlow<GpsData> = _gpsData.asStateFlow()

    init {
        viewModelScope.launch {
            repository.gpsDataFlow.collect { data ->
                _gpsData.value = data
            }
        }
    }
}
```

#### 2.2.5 配置 Koin 依赖注入

- [ ] 创建 `BluetoothModule`
  - 文件路径: `app/src/main/java/com/race/gps/di/BluetoothModule.kt`

```kotlin
package com.race.gps.di

import com.race.gps.data.bluetooth.BluetoothDataSource
import com.race.gps.data.bluetooth.impl.BleBluetoothDataSource
import com.race.gps.data.bluetooth.impl.MockBluetoothDataSource
import com.race.gps.data.parser.RaceChronoParser
import com.race.gps.data.repository.GpsDataRepositoryImpl
import com.race.gps.domain.repository.GpsDataRepository
import com.race.gps.viewmodel.GpsDataViewModel
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

val bluetoothModule = module {
    single<BluetoothDataSource> {
        // 根据BuildConfig切换真实/模拟实现
        if (com.race.gps.BuildConfig.USE_MOCK_BLE) {
            MockBluetoothDataSource()
        } else {
            BleBluetoothDataSource(get())
        }
    }
    single { RaceChronoParser() }
    single<GpsDataRepository> { GpsDataRepositoryImpl(get()) }
    viewModel { GpsDataViewModel(get()) }
}
```

### 2.3 测试步骤

1. 运行应用，确认 `GpsDataViewModel` 能正常收集数据
2. 在 `TestActivity` 中观察 `gpsData` StateFlow 更新
3. 验证 Mock 模式下速度从0逐渐增加到120 km/h
4. 验证 `isTestReady` 在卫星数>=6且HDOP<2.0时为 `true`

### 2.4 Git提交

```bash
git add app/src/main/java/com/race/gps/domain/model/GpsData.kt
git add app/src/main/java/com/race/gps/data/bluetooth/
git add app/src/main/java/com/race/gps/domain/repository/GpsDataRepository.kt
git add app/src/main/java/com/race/gps/data/repository/GpsDataRepositoryImpl.kt
git add app/src/main/java/com/race/gps/viewmodel/GpsDataViewModel.kt
git add app/src/main/java/com/race/gps/di/BluetoothModule.kt
git commit -m "refactor(bluetooth): 重构蓝牙层为DataSource模式，统一GpsData数据模型"
```

---

## 3. Chunk 2: 测试核心逻辑

### 3.1 目标

- 定义测试模板（TestTemplate）：描述测试类型和触发条件
- 实现测试会话（TestSession）：管理单次测试的生命周期
- 实现测试状态机（TestState）：IDLE → WAITING → RUNNING → FINISHED
- 实现结果计算用例（CalculateResultUseCase）
- 实现 `TestSessionViewModel`

### 3.2 任务清单

#### 3.2.1 定义 TestTemplate

- [ ] 创建 `TestTemplate` 密封类
  - 文件路径: `app/src/main/java/com/race/gps/domain/model/TestTemplate.kt`
  - 包含 `Acceleration0To100`、`Braking100To0`、`Custom` 子类
  - 每个模板定义触发速度、结束速度、超时时间

```kotlin
package com.race.gps.domain.model

sealed class TestTemplate(
    val name: String,
    val startSpeedKmh: Double,   // 触发速度（km/h）
    val endSpeedKmh: Double,     // 结束速度（km/h）
    val timeoutMs: Long          // 超时时间（ms）
) {
    object Acceleration0To100 : TestTemplate(
        name = "0-100 加速",
        startSpeedKmh = 5.0,     // 超过5km/h触发
        endSpeedKmh = 100.0,
        timeoutMs = 30_000L
    )

    object Braking100To0 : TestTemplate(
        name = "100-0 刹车",
        startSpeedKmh = 95.0,    // 超过95km/h触发
        endSpeedKmh = 5.0,       // 低于5km/h结束
        timeoutMs = 15_000L
    )

    data class Custom(
        val customName: String,
        val start: Double,
        val end: Double,
        val timeout: Long
    ) : TestTemplate(customName, start, end, timeout)
}
```

#### 3.2.2 定义 TestState 状态机

- [ ] 创建 `TestState` 密封类
  - 文件路径: `app/src/main/java/com/race/gps/domain/model/TestState.kt`
  - 状态：IDLE → WAITING → RUNNING → FINISHED / TIMEOUT / ERROR

```kotlin
package com.race.gps.domain.model

sealed class TestState {
    /** 空闲，等待用户选择测试类型 */
    object Idle : TestState()

    /** 等待触发条件（等待速度达到startSpeed） */
    data class Waiting(
        val template: TestTemplate,
        val currentSpeed: Double
    ) : TestState()

    /** 测试进行中 */
    data class Running(
        val template: TestTemplate,
        val startTime: Long,
        val startSpeed: Double,
        val dataPoints: List<GpsData>,
        val elapsedMs: Long
    ) : TestState()

    /** 测试完成 */
    data class Finished(
        val template: TestTemplate,
        val result: TestResult
    ) : TestState()

    /** 超时 */
    data class Timeout(val template: TestTemplate) : TestState()

    /** 错误 */
    data class Error(val message: String) : TestState()
}
```

#### 3.2.3 定义 TestResult

- [ ] 创建 `TestResult` 数据类
  - 文件路径: `app/src/main/java/com/race/gps/domain/model/TestResult.kt`
  - 包含时间、距离、最大速度、数据点列表

```kotlin
package com.race.gps.domain.model

data class TestResult(
    val id: String = java.util.UUID.randomUUID().toString(),
    val templateName: String,
    val startTime: Long,
    val endTime: Long,
    val durationMs: Long,           // 测试耗时（ms）
    val distanceMeters: Double,     // 测试距离（m）
    val maxSpeedKmh: Double,        // 最大速度
    val startSpeedKmh: Double,      // 起始速度
    val endSpeedKmh: Double,        // 结束速度
    val dataPoints: List<GpsData>,  // 原始数据点
    val averageHdop: Double,        // 平均HDOP
    val minSatelliteCount: Int      // 最少卫星数
)
```

#### 3.2.4 实现 CalculateResultUseCase

- [ ] 创建 `CalculateResultUseCase`
  - 文件路径: `app/src/main/java/com/race/gps/domain/usecase/CalculateResultUseCase.kt`
  - 从数据点列表计算距离、最大速度、平均HDOP

```kotlin
package com.race.gps.domain.usecase

import com.race.gps.domain.model.GpsData
import com.race.gps.domain.model.TestResult
import com.race.gps.domain.model.TestTemplate
import kotlin.math.*

class CalculateResultUseCase {

    operator fun invoke(
        template: TestTemplate,
        startTime: Long,
        endTime: Long,
        dataPoints: List<GpsData>
    ): TestResult {
        val durationMs = endTime - startTime
        val distanceMeters = calculateDistance(dataPoints)
        val maxSpeed = dataPoints.maxOfOrNull { it.speed } ?: 0.0
        val avgHdop = dataPoints.map { it.hdop }.average()
        val minSat = dataPoints.minOfOrNull { it.satelliteCount } ?: 0

        return TestResult(
            templateName = template.name,
            startTime = startTime,
            endTime = endTime,
            durationMs = durationMs,
            distanceMeters = distanceMeters,
            maxSpeedKmh = maxSpeed,
            startSpeedKmh = dataPoints.firstOrNull()?.speed ?: 0.0,
            endSpeedKmh = dataPoints.lastOrNull()?.speed ?: 0.0,
            dataPoints = dataPoints,
            averageHdop = avgHdop,
            minSatelliteCount = minSat
        )
    }

    /** 使用Haversine公式计算轨迹总距离 */
    private fun calculateDistance(points: List<GpsData>): Double {
        if (points.size < 2) return 0.0
        var total = 0.0
        for (i in 1 until points.size) {
            total += haversine(
                points[i - 1].latitude, points[i - 1].longitude,
                points[i].latitude, points[i].longitude
            )
        }
        return total
    }

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0 // 地球半径（米）
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}
```

#### 3.2.5 实现 TestSessionViewModel

- [ ] 创建 `TestSessionViewModel`
  - 文件路径: `app/src/main/java/com/race/gps/viewmodel/TestSessionViewModel.kt`
  - 管理测试状态机转换
  - 收集 `GpsDataRepository` 数据流
  - 暴露 `StateFlow<TestState>` 给UI

```kotlin
package com.race.gps.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.race.gps.domain.model.*
import com.race.gps.domain.repository.GpsDataRepository
import com.race.gps.domain.usecase.CalculateResultUseCase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class TestSessionViewModel(
    private val repository: GpsDataRepository,
    private val calculateResult: CalculateResultUseCase
) : ViewModel() {

    private val _testState = MutableStateFlow<TestState>(TestState.Idle)
    val testState: StateFlow<TestState> = _testState.asStateFlow()

    private var currentTemplate: TestTemplate? = null
    private var startTime: Long = 0L
    private val dataPoints = mutableListOf<GpsData>()
    private var timeoutJob: Job? = null

    fun selectTemplate(template: TestTemplate) {
        currentTemplate = template
        _testState.value = TestState.Waiting(template, 0.0)
        startListening()
    }

    fun cancelTest() {
        timeoutJob?.cancel()
        dataPoints.clear()
        _testState.value = TestState.Idle
    }

    private fun startListening() {
        viewModelScope.launch {
            repository.gpsDataFlow.collect { gpsData ->
                handleGpsData(gpsData)
            }
        }
    }

    private fun handleGpsData(gpsData: GpsData) {
        val template = currentTemplate ?: return
        when (val state = _testState.value) {
            is TestState.Waiting -> {
                _testState.value = state.copy(currentSpeed = gpsData.speed)
                // 检查触发条件
                val triggered = when (template) {
                    is TestTemplate.Acceleration0To100 -> gpsData.speed >= template.startSpeedKmh
                    is TestTemplate.Braking100To0 -> gpsData.speed >= template.startSpeedKmh
                    is TestTemplate.Custom -> gpsData.speed >= template.startSpeedKmh
                }
                if (triggered) startTest(template, gpsData)
            }
            is TestState.Running -> {
                dataPoints.add(gpsData)
                val elapsed = System.currentTimeMillis() - startTime
                _testState.value = state.copy(
                    dataPoints = dataPoints.toList(),
                    elapsedMs = elapsed
                )
                // 检查结束条件
                val finished = when (template) {
                    is TestTemplate.Acceleration0To100 -> gpsData.speed >= template.endSpeedKmh
                    is TestTemplate.Braking100To0 -> gpsData.speed <= template.endSpeedKmh
                    is TestTemplate.Custom -> gpsData.speed >= template.endSpeedKmh
                }
                if (finished) finishTest(template)
            }
            else -> {}
        }
    }

    private fun startTest(template: TestTemplate, firstPoint: GpsData) {
        startTime = System.currentTimeMillis()
        dataPoints.clear()
        dataPoints.add(firstPoint)
        _testState.value = TestState.Running(
            template = template,
            startTime = startTime,
            startSpeed = firstPoint.speed,
            dataPoints = listOf(firstPoint),
            elapsedMs = 0L
        )
        // 启动超时计时器
        timeoutJob = viewModelScope.launch {
            delay(template.timeoutMs)
            if (_testState.value is TestState.Running) {
                _testState.value = TestState.Timeout(template)
            }
        }
    }

    private fun finishTest(template: TestTemplate) {
        timeoutJob?.cancel()
        val endTime = System.currentTimeMillis()
        val result = calculateResult(template, startTime, endTime, dataPoints.toList())
        _testState.value = TestState.Finished(template, result)
    }
}
```

#### 3.2.6 更新 Koin 模块

- [ ] 在 `BluetoothModule` 中添加 `TestSessionViewModel` 和 `CalculateResultUseCase`

```kotlin
// 在 bluetoothModule 中追加：
factory { CalculateResultUseCase() }
viewModel { TestSessionViewModel(get(), get()) }
```

### 3.3 测试步骤

1. 选择 "0-100 加速" 模板，确认状态变为 `Waiting`
2. Mock数据速度超过5 km/h时，确认状态变为 `Running`
3. Mock数据速度超过100 km/h时，确认状态变为 `Finished`
4. 验证 `TestResult.durationMs` 计算正确
5. 验证 `TestResult.distanceMeters` 使用Haversine公式计算

### 3.4 Git提交

```bash
git add app/src/main/java/com/race/gps/domain/model/
git add app/src/main/java/com/race/gps/domain/usecase/
git add app/src/main/java/com/race/gps/viewmodel/TestSessionViewModel.kt
git commit -m "feat(test-session): 实现测试状态机、结果计算和TestSessionViewModel"
```

---

## 4. Chunk 3: 数据持久化

### 4.1 目标

- 实现测试结果的文件存储（JSON格式）
- 添加 Room 数据库实体 `SpeedSegmentEntity`
- 执行数据库迁移（版本升级）
- 实现 `TestResultRepository`

### 4.2 任务清单

#### 4.2.1 实现文件存储

- [ ] 创建 `TestResultFileStorage`
  - 文件路径: `app/src/main/java/com/race/gps/data/storage/TestResultFileStorage.kt`
  - 将 `TestResult` 序列化为JSON并保存到外部存储
  - 支持按日期分目录存储

```kotlin
package com.race.gps.data.storage

import android.content.Context
import com.google.gson.Gson
import com.race.gps.domain.model.TestResult
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class TestResultFileStorage(private val context: Context) {

    private val gson = Gson()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    fun save(result: TestResult): File {
        val dir = getStorageDir()
        val fileName = "${result.templateName.replace(" ", "_")}_${result.id}.json"
        val file = File(dir, fileName)
        file.writeText(gson.toJson(result))
        return file
    }

    fun loadAll(): List<TestResult> {
        val dir = getStorageDir()
        return dir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { runCatching { gson.fromJson(it.readText(), TestResult::class.java) }.getOrNull() }
            ?: emptyList()
    }

    private fun getStorageDir(): File {
        val today = dateFormat.format(Date())
        val dir = File(context.getExternalFilesDir(null), "test_results/$today")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }
}
```

#### 4.2.2 创建 SpeedSegmentEntity

- [ ] 创建 `SpeedSegmentEntity` Room实体
  - 文件路径: `app/src/main/java/com/race/gps/data/db/entity/SpeedSegmentEntity.kt`
  - 存储每次测试的关键指标（不存储原始数据点，节省空间）

```kotlin
package com.race.gps.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "speed_segments")
data class SpeedSegmentEntity(
    @PrimaryKey val id: String,
    val templateName: String,
    val startTime: Long,
    val endTime: Long,
    val durationMs: Long,
    val distanceMeters: Double,
    val maxSpeedKmh: Double,
    val startSpeedKmh: Double,
    val endSpeedKmh: Double,
    val averageHdop: Double,
    val minSatelliteCount: Int,
    val filePath: String?          // 关联的JSON文件路径
)
```

#### 4.2.3 创建 SpeedSegmentDao

- [ ] 创建 `SpeedSegmentDao`
  - 文件路径: `app/src/main/java/com/race/gps/data/db/dao/SpeedSegmentDao.kt`

```kotlin
package com.race.gps.data.db.dao

import androidx.room.*
import com.race.gps.data.db.entity.SpeedSegmentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SpeedSegmentDao {
    @Query("SELECT * FROM speed_segments ORDER BY startTime DESC")
    fun getAllFlow(): Flow<List<SpeedSegmentEntity>>

    @Query("SELECT * FROM speed_segments WHERE id = :id")
    suspend fun getById(id: String): SpeedSegmentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SpeedSegmentEntity)

    @Delete
    suspend fun delete(entity: SpeedSegmentEntity)

    @Query("DELETE FROM speed_segments")
    suspend fun deleteAll()
}
```

#### 4.2.4 数据库迁移

- [ ] 更新 `AppDatabase`，添加 `speed_segments` 表并执行迁移
  - 文件路径: `app/src/main/java/com/race/gps/data/db/AppDatabase.kt`
  - 版本从当前版本升级到下一版本

```kotlin
package com.race.gps.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.race.gps.data.db.dao.SpeedSegmentDao
import com.race.gps.data.db.entity.SpeedSegmentEntity

@Database(
    entities = [SpeedSegmentEntity::class],
    version = 2,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun speedSegmentDao(): SpeedSegmentDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS speed_segments (
                        id TEXT NOT NULL PRIMARY KEY,
                        templateName TEXT NOT NULL,
                        startTime INTEGER NOT NULL,
                        endTime INTEGER NOT NULL,
                        durationMs INTEGER NOT NULL,
                        distanceMeters REAL NOT NULL,
                        maxSpeedKmh REAL NOT NULL,
                        startSpeedKmh REAL NOT NULL,
                        endSpeedKmh REAL NOT NULL,
                        averageHdop REAL NOT NULL,
                        minSatelliteCount INTEGER NOT NULL,
                        filePath TEXT
                    )
                """.trimIndent())
            }
        }
    }
}
```

#### 4.2.5 实现 TestResultRepository

- [ ] 创建 `TestResultRepository` 接口
  - 文件路径: `app/src/main/java/com/race/gps/domain/repository/TestResultRepository.kt`

```kotlin
package com.race.gps.domain.repository

import com.race.gps.domain.model.TestResult
import kotlinx.coroutines.flow.Flow

interface TestResultRepository {
    fun getAllResultsFlow(): Flow<List<TestResult>>
    suspend fun saveResult(result: TestResult)
    suspend fun deleteResult(id: String)
}
```

- [ ] 实现 `TestResultRepositoryImpl`
  - 文件路径: `app/src/main/java/com/race/gps/data/repository/TestResultRepositoryImpl.kt`
  - 同时写入数据库（索引）和文件（完整数据）

```kotlin
package com.race.gps.data.repository

import com.race.gps.data.db.dao.SpeedSegmentDao
import com.race.gps.data.db.entity.SpeedSegmentEntity
import com.race.gps.data.storage.TestResultFileStorage
import com.race.gps.domain.model.TestResult
import com.race.gps.domain.repository.TestResultRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class TestResultRepositoryImpl(
    private val dao: SpeedSegmentDao,
    private val fileStorage: TestResultFileStorage
) : TestResultRepository {

    override fun getAllResultsFlow(): Flow<List<TestResult>> {
        return dao.getAllFlow().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun saveResult(result: TestResult) {
        val file = fileStorage.save(result)
        dao.insert(result.toEntity(file.absolutePath))
    }

    override suspend fun deleteResult(id: String) {
        val entity = dao.getById(id) ?: return
        dao.delete(entity)
    }

    private fun SpeedSegmentEntity.toDomain() = TestResult(
        id = id,
        templateName = templateName,
        startTime = startTime,
        endTime = endTime,
        durationMs = durationMs,
        distanceMeters = distanceMeters,
        maxSpeedKmh = maxSpeedKmh,
        startSpeedKmh = startSpeedKmh,
        endSpeedKmh = endSpeedKmh,
        dataPoints = emptyList(), // 历史列表不加载原始数据点
        averageHdop = averageHdop,
        minSatelliteCount = minSatelliteCount
    )

    private fun TestResult.toEntity(filePath: String) = SpeedSegmentEntity(
        id = id,
        templateName = templateName,
        startTime = startTime,
        endTime = endTime,
        durationMs = durationMs,
        distanceMeters = distanceMeters,
        maxSpeedKmh = maxSpeedKmh,
        startSpeedKmh = startSpeedKmh,
        endSpeedKmh = endSpeedKmh,
        averageHdop = averageHdop,
        minSatelliteCount = minSatelliteCount,
        filePath = filePath
    )
}
```

#### 4.2.6 更新 Koin 模块

- [ ] 创建 `DatabaseModule`
  - 文件路径: `app/src/main/java/com/race/gps/di/DatabaseModule.kt`

```kotlin
package com.race.gps.di

import androidx.room.Room
import com.race.gps.data.db.AppDatabase
import com.race.gps.data.repository.TestResultRepositoryImpl
import com.race.gps.data.storage.TestResultFileStorage
import com.race.gps.domain.repository.TestResultRepository
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val databaseModule = module {
    single {
        Room.databaseBuilder(androidContext(), AppDatabase::class.java, "gps_app.db")
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .build()
    }
    single { get<AppDatabase>().speedSegmentDao() }
    single { TestResultFileStorage(androidContext()) }
    single<TestResultRepository> { TestResultRepositoryImpl(get(), get()) }
}
```

### 4.3 测试步骤

1. 完成一次测试后，确认 `TestResult` 被写入数据库
2. 在设备文件管理器中确认JSON文件生成在 `Android/data/com.race.gps/files/test_results/`
3. 重启应用后，确认历史记录从数据库正确加载
4. 验证数据库迁移不丢失现有数据（如有旧版本数据）

### 4.4 Git提交

```bash
git add app/src/main/java/com/race/gps/data/storage/
git add app/src/main/java/com/race/gps/data/db/
git add app/src/main/java/com/race/gps/domain/repository/TestResultRepository.kt
git add app/src/main/java/com/race/gps/data/repository/TestResultRepositoryImpl.kt
git add app/src/main/java/com/race/gps/di/DatabaseModule.kt
git commit -m "feat(persistence): 添加Room数据库、文件存储和TestResultRepository"
```

---

## 5. Chunk 4: UI实现

### 5.1 目标

- 实现设备连接页面（DeviceConnectionScreen）
- 实现测试类型选择页面（TestTypeSelectionScreen）
- 实现测试执行页面（TestExecutionScreen）
- 实现结果详情页面（TestResultDetailScreen）
- 实现历史记录页面（TestHistoryScreen）

### 5.2 任务清单

#### 5.2.1 设备连接页面

- [ ] 创建 `DeviceConnectionScreen`
  - 文件路径: `app/src/main/java/com/race/gps/ui/screen/DeviceConnectionScreen.kt`
  - 显示已配对设备列表
  - 点击设备触发连接
  - 显示连接状态（连接中/已连接/断开）

```kotlin
package com.race.gps.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.race.gps.viewmodel.GpsDataViewModel
import org.koin.androidx.compose.koinViewModel

@Composable
fun DeviceConnectionScreen(
    onConnected: () -> Unit,
    viewModel: GpsDataViewModel = koinViewModel()
) {
    val gpsData by viewModel.gpsData.collectAsState()

    LaunchedEffect(gpsData.isConnected) {
        if (gpsData.isConnected) onConnected()
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("选择GPS设备", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))

        if (gpsData.isConnected) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("已连接", color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.weight(1f))
                    Button(onClick = { viewModel.disconnect() }) {
                        Text("断开")
                    }
                }
            }
        } else {
            // 设备列表（从ViewModel获取扫描结果）
            Text("正在扫描设备...", style = MaterialTheme.typography.bodyMedium)
        }
    }
}
```

#### 5.2.2 测试类型选择页面

- [ ] 创建 `TestTypeSelectionScreen`
  - 文件路径: `app/src/main/java/com/race/gps/ui/screen/TestTypeSelectionScreen.kt`
  - 显示可用测试类型卡片
  - 显示当前GPS信号质量（卫星数、HDOP）
  - 信号不足时禁用测试按钮

```kotlin
package com.race.gps.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.race.gps.domain.model.TestTemplate
import com.race.gps.viewmodel.GpsDataViewModel
import org.koin.androidx.compose.koinViewModel

@Composable
fun TestTypeSelectionScreen(
    onTemplateSelected: (TestTemplate) -> Unit,
    gpsViewModel: GpsDataViewModel = koinViewModel()
) {
    val gpsData by gpsViewModel.gpsData.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // GPS信号状态卡片
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("GPS信号", style = MaterialTheme.typography.titleMedium)
                Text("卫星数: ${gpsData.satelliteCount}")
                Text("HDOP: ${"%.1f".format(gpsData.hdop)}")
                if (!gpsData.isTestReady) {
                    Text(
                        "信号不足，请等待...",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Text("选择测试类型", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))

        // 0-100加速测试
        TestTypeCard(
            title = "0-100 加速测试",
            description = "从静止加速到100 km/h，记录时间和距离",
            enabled = gpsData.isTestReady,
            onClick = { onTemplateSelected(TestTemplate.Acceleration0To100) }
        )

        Spacer(Modifier.height(12.dp))

        // 100-0刹车测试
        TestTypeCard(
            title = "100-0 刹车测试",
            description = "从100 km/h制动到静止，记录时间和距离",
            enabled = gpsData.isTestReady,
            onClick = { onTemplateSelected(TestTemplate.Braking100To0) }
        )
    }
}

@Composable
private fun TestTypeCard(
    title: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onClick,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("开始测试")
            }
        }
    }
}
```

#### 5.2.3 测试执行页面

- [ ] 创建 `TestExecutionScreen`
  - 文件路径: `app/src/main/java/com/race/gps/ui/screen/TestExecutionScreen.kt`
  - 实时显示当前速度（大字体）
  - 显示测试状态（等待触发/测试中/已完成）
  - 显示计时器（测试进行中）
  - 完成后显示结果摘要并提供导航

```kotlin
package com.race.gps.ui.screen

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.race.gps.domain.model.TestState
import com.race.gps.viewmodel.TestSessionViewModel
import org.koin.androidx.compose.koinViewModel

@Composable
fun TestExecutionScreen(
    onFinished: (String) -> Unit,  // 传递resultId
    onCancel: () -> Unit,
    viewModel: TestSessionViewModel = koinViewModel()
) {
    val testState by viewModel.testState.collectAsState()

    LaunchedEffect(testState) {
        if (testState is TestState.Finished) {
            val result = (testState as TestState.Finished).result
            onFinished(result.id)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AnimatedContent(targetState = testState) { state ->
            when (state) {
                is TestState.Waiting -> WaitingContent(state, onCancel)
                is TestState.Running -> RunningContent(state, onCancel)
                is TestState.Finished -> FinishedContent(state)
                is TestState.Timeout -> TimeoutContent(state, onCancel)
                else -> {}
            }
        }
    }
}

@Composable
private fun WaitingContent(state: TestState.Waiting, onCancel: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("等待触发", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(32.dp))
        Text(
            "${"%.1f".format(state.currentSpeed)}",
            fontSize = 80.sp,
            fontWeight = FontWeight.Bold
        )
        Text("km/h", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))
        Text(
            "速度超过 ${"%.0f".format(state.template.startSpeedKmh)} km/h 时自动开始",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(32.dp))
        OutlinedButton(onClick = onCancel) { Text("取消") }
    }
}

@Composable
private fun RunningContent(state: TestState.Running, onCancel: () -> Unit) {
    val currentSpeed = state.dataPoints.lastOrNull()?.speed ?: state.startSpeed
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("测试进行中", style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text(
            "${"%.1f".format(currentSpeed)}",
            fontSize = 80.sp,
            fontWeight = FontWeight.Bold
        )
        Text("km/h", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))
        Text(
            "${"%.2f".format(state.elapsedMs / 1000.0)} 秒",
            fontSize = 40.sp,
            color = MaterialTheme.colorScheme.secondary
        )
        Spacer(Modifier.height(32.dp))
        OutlinedButton(onClick = onCancel) { Text("中止") }
    }
}

@Composable
private fun FinishedContent(state: TestState.Finished) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("测试完成", style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(24.dp))
        Text(
            "${"%.2f".format(state.result.durationMs / 1000.0)} 秒",
            fontSize = 60.sp,
            fontWeight = FontWeight.Bold
        )
        Text("${"%.1f".format(state.result.distanceMeters)} 米",
            style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun TimeoutContent(state: TestState.Timeout, onCancel: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("测试超时", style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(16.dp))
        Text("未能在规定时间内完成测试")
        Spacer(Modifier.height(32.dp))
        Button(onClick = onCancel) { Text("返回") }
    }
}
```

#### 5.2.4 结果详情页面

- [ ] 创建 `TestResultDetailScreen`
  - 文件路径: `app/src/main/java/com/race/gps/ui/screen/TestResultDetailScreen.kt`
  - 显示完整测试结果（时间、距离、最大速度）
  - 显示GPS质量指标（HDOP、卫星数）
  - 提供分享/导出按钮

```kotlin
package com.race.gps.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.race.gps.domain.model.TestResult

@Composable
fun TestResultDetailScreen(
    result: TestResult,
    onBack: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(result.templateName, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(24.dp))

        ResultMetricCard("测试时间", "${"%.3f".format(result.durationMs / 1000.0)} 秒")
        Spacer(Modifier.height(8.dp))
        ResultMetricCard("测试距离", "${"%.1f".format(result.distanceMeters)} 米")
        Spacer(Modifier.height(8.dp))
        ResultMetricCard("最大速度", "${"%.1f".format(result.maxSpeedKmh)} km/h")
        Spacer(Modifier.height(8.dp))
        ResultMetricCard("平均HDOP", "${"%.2f".format(result.averageHdop)}")
        Spacer(Modifier.height(8.dp))
        ResultMetricCard("最少卫星数", "${result.minSatelliteCount}")

        Spacer(Modifier.weight(1f))
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("返回")
        }
    }
}

@Composable
private fun ResultMetricCard(label: String, value: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(value, style = MaterialTheme.typography.titleMedium)
        }
    }
}
```

#### 5.2.5 历史记录页面

- [ ] 创建 `TestHistoryScreen`
  - 文件路径: `app/src/main/java/com/race/gps/ui/screen/TestHistoryScreen.kt`
  - 显示所有历史测试结果列表
  - 按时间倒序排列
  - 点击进入结果详情

```kotlin
package com.race.gps.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.race.gps.domain.model.TestResult
import com.race.gps.viewmodel.TestHistoryViewModel
import org.koin.androidx.compose.koinViewModel
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun TestHistoryScreen(
    onResultClick: (TestResult) -> Unit,
    viewModel: TestHistoryViewModel = koinViewModel()
) {
    val results by viewModel.results.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("历史记录", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))

        if (results.isEmpty()) {
            Text("暂无测试记录", style = MaterialTheme.typography.bodyMedium)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(results) { result ->
                    HistoryItemCard(result = result, onClick = { onResultClick(result) })
                }
            }
        }
    }
}

@Composable
private fun HistoryItemCard(result: TestResult, onClick: () -> Unit) {
    val dateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(result.templateName, style = MaterialTheme.typography.titleMedium)
                Text(
                    dateFormat.format(Date(result.startTime)),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                Text(
                    "${"%.2f".format(result.durationMs / 1000.0)}s",
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    "${"%.0f".format(result.distanceMeters)}m",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
```

#### 5.2.6 创建 TestHistoryViewModel

- [ ] 创建 `TestHistoryViewModel`
  - 文件路径: `app/src/main/java/com/race/gps/viewmodel/TestHistoryViewModel.kt`

```kotlin
package com.race.gps.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.race.gps.domain.model.TestResult
import com.race.gps.domain.repository.TestResultRepository
import kotlinx.coroutines.flow.*

class TestHistoryViewModel(
    private val repository: TestResultRepository
) : ViewModel() {

    val results: StateFlow<List<TestResult>> = repository.getAllResultsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}
```

#### 5.2.7 配置导航

- [ ] 创建 `AppNavigation`
  - 文件路径: `app/src/main/java/com/race/gps/ui/navigation/AppNavigation.kt`
  - 定义路由：`connection` → `test_type` → `test_execution` → `result_detail/{id}` / `history`

```kotlin
package com.race.gps.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.race.gps.domain.model.TestTemplate
import com.race.gps.ui.screen.*

@Composable
fun AppNavigation() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = "connection") {
        composable("connection") {
            DeviceConnectionScreen(
                onConnected = { navController.navigate("test_type") }
            )
        }
        composable("test_type") {
            TestTypeSelectionScreen(
                onTemplateSelected = { template ->
                    // 通过ViewModel传递模板，然后导航
                    navController.navigate("test_execution")
                }
            )
        }
        composable("test_execution") {
            TestExecutionScreen(
                onFinished = { id -> navController.navigate("result_detail/$id") },
                onCancel = { navController.popBackStack() }
            )
        }
        composable(
            "result_detail/{id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType })
        ) { backStackEntry ->
            val id = backStackEntry.arguments?.getString("id") ?: return@composable
            // 从ViewModel加载结果
        }
        composable("history") {
            TestHistoryScreen(
                onResultClick = { result ->
                    navController.navigate("result_detail/${result.id}")
                }
            )
        }
    }
}
```

### 5.3 测试步骤

1. 启动应用，确认导航到设备连接页面
2. Mock模式下自动连接，确认跳转到测试类型选择页面
3. 选择"0-100加速测试"，确认进入测试执行页面并显示"等待触发"
4. Mock数据触发后，确认计时器开始计时
5. 测试完成后，确认跳转到结果详情页面
6. 返回并进入历史记录页面，确认记录已保存

### 5.4 Git提交

```bash
git add app/src/main/java/com/race/gps/ui/
git add app/src/main/java/com/race/gps/viewmodel/TestHistoryViewModel.kt
git commit -m "feat(ui): 实现完整UI页面（连接、测试选择、执行、结果详情、历史记录）"
```

---

## 6. 测试与验证

### 6.1 单元测试

#### 6.1.1 CalculateResultUseCase 测试

- [ ] 创建 `CalculateResultUseCaseTest`
  - 文件路径: `app/src/test/java/com/race/gps/domain/usecase/CalculateResultUseCaseTest.kt`

```kotlin
package com.race.gps.domain.usecase

import com.race.gps.domain.model.GpsData
import com.race.gps.domain.model.TestTemplate
import org.junit.Assert.*
import org.junit.Test

class CalculateResultUseCaseTest {

    private val useCase = CalculateResultUseCase()

    @Test
    fun `计算0-100加速测试结果`() {
        val startTime = 0L
        val endTime = 5000L
        val dataPoints = (0..50).map { i ->
            GpsData(
                timestamp = i * 100L,
                speed = i * 2.0,
                latitude = 39.9042 + i * 0.00001,
                longitude = 116.4074,
                altitude = 50.0,
                bearing = 0.0,
                satelliteCount = 8,
                hdop = 1.2,
                vdop = 1.5,
                frequency = 10.0,
                isConnected = true,
                isTestReady = true,
                errorMessage = null
            )
        }

        val result = useCase(TestTemplate.Acceleration0To100, startTime, endTime, dataPoints)

        assertEquals(5000L, result.durationMs)
        assertEquals(100.0, result.maxSpeedKmh, 0.1)
        assertTrue(result.distanceMeters > 0)
    }

    @Test
    fun `空数据点返回零距离`() {
        val result = useCase(TestTemplate.Acceleration0To100, 0L, 1000L, emptyList())
        assertEquals(0.0, result.distanceMeters, 0.001)
    }
}
```

#### 6.1.2 TestSessionViewModel 测试

- [ ] 创建 `TestSessionViewModelTest`
  - 文件路径: `app/src/test/java/com/race/gps/viewmodel/TestSessionViewModelTest.kt`
  - 使用 `kotlinx-coroutines-test` 和 `turbine` 测试Flow

```kotlin
package com.race.gps.viewmodel

import app.cash.turbine.test
import com.race.gps.domain.model.*
import com.race.gps.domain.repository.GpsDataRepository
import com.race.gps.domain.usecase.CalculateResultUseCase
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TestSessionViewModelTest {

    private val mockRepository = mockk<GpsDataRepository>()
    private val calculateResult = CalculateResultUseCase()

    @Test
    fun `选择模板后状态变为Waiting`() = runTest {
        every { mockRepository.gpsDataFlow } returns flowOf(GpsData.Empty)
        val viewModel = TestSessionViewModel(mockRepository, calculateResult)

        viewModel.testState.test {
            assertEquals(TestState.Idle, awaitItem())
            viewModel.selectTemplate(TestTemplate.Acceleration0To100)
            val waiting = awaitItem()
            assertTrue(waiting is TestState.Waiting)
        }
    }
}
```

### 6.2 集成测试

#### 6.2.1 端到端测试流程

- [ ] 验证完整测试流程（Mock模式）

```
测试步骤：
1. 启动应用（USE_MOCK_BLE=true）
2. 自动连接Mock设备
3. 选择"0-100加速测试"
4. 等待速度超过5 km/h（约2秒）
5. 观察计时器启动
6. 等待速度超过100 km/h（约40秒）
7. 确认结果页面显示正确时间和距离
8. 确认数据库中有新记录
9. 进入历史记录页面确认记录存在

预期结果：
- 测试时间约40秒（Mock每100ms增加2.5 km/h）
- 距离约500-600米
- 历史记录页面显示该记录
```

#### 6.2.2 数据库迁移验证

- [ ] 验证从版本1升级到版本2不丢失数据

```
测试步骤：
1. 安装旧版本应用（数据库版本1）
2. 创建一些测试数据
3. 安装新版本应用（数据库版本2）
4. 确认旧数据仍然存在
5. 确认新表 speed_segments 已创建
```

### 6.3 性能验证

- [ ] 验证GPS数据流不阻塞UI线程
  - 在 `BleBluetoothDataSource` 中确认数据解析在 `Dispatchers.IO` 执行
  - 使用 Android Profiler 确认主线程无长时间阻塞

- [ ] 验证内存使用
  - 长时间测试（>5分钟）后检查内存无泄漏
  - 确认 `dataPoints` 列表在测试完成后被清理

---

## 7. 提交策略

### 7.1 提交顺序

按以下顺序提交，确保每个提交都可独立编译运行：

```
Commit 1: refactor(bluetooth): 重构蓝牙层为DataSource模式，统一GpsData数据模型
Commit 2: feat(test-session): 实现测试状态机、结果计算和TestSessionViewModel
Commit 3: feat(persistence): 添加Room数据库、文件存储和TestResultRepository
Commit 4: feat(ui): 实现完整UI页面（连接、测试选择、执行、结果详情、历史记录）
Commit 5: test: 添加单元测试和集成测试
```

### 7.2 分支策略

```bash
# 从master创建功能分支
git checkout -b feature/gps-test-redesign

# 按Chunk逐步提交
# Chunk 1完成后
git commit -m "refactor(bluetooth): 重构蓝牙层为DataSource模式，统一GpsData数据模型"

# Chunk 2完成后
git commit -m "feat(test-session): 实现测试状态机、结果计算和TestSessionViewModel"

# Chunk 3完成后
git commit -m "feat(persistence): 添加Room数据库、文件存储和TestResultRepository"

# Chunk 4完成后
git commit -m "feat(ui): 实现完整UI页面（连接、测试选择、执行、结果详情、历史记录）"

# 测试完成后
git commit -m "test: 添加单元测试和集成测试"

# 合并到master（需要用户确认）
# git checkout master && git merge --no-ff feature/gps-test-redesign
```

### 7.3 每个Chunk的验收标准

| Chunk | 验收标准 |
|-------|---------|
| Chunk 1 | 应用启动，Mock数据正常流动，`GpsDataViewModel` 更新 |
| Chunk 2 | 完整测试流程可运行，状态机转换正确 |
| Chunk 3 | 测试结果持久化，重启后历史记录存在 |
| Chunk 4 | 所有页面可正常导航，UI显示正确 |

### 7.4 回滚策略

如果某个Chunk引入问题：

```bash
# 查看提交历史
git log --oneline -10

# 回滚到上一个稳定提交
git revert HEAD  # 创建反向提交（安全）

# 或者软重置（保留文件修改）
git reset --soft HEAD~1
```

---

## 附录：文件结构总览

```
app/src/main/java/com/race/gps/
├── di/
│   ├── BluetoothModule.kt          # Chunk 1
│   └── DatabaseModule.kt           # Chunk 3
├── domain/
│   ├── model/
│   │   ├── GpsData.kt              # Chunk 1
│   │   ├── TestTemplate.kt         # Chunk 2
│   │   ├── TestState.kt            # Chunk 2
│   │   └── TestResult.kt           # Chunk 2
│   ├── repository/
│   │   ├── GpsDataRepository.kt    # Chunk 1
│   │   └── TestResultRepository.kt # Chunk 3
│   └── usecase/
│       └── CalculateResultUseCase.kt # Chunk 2
├── data/
│   ├── bluetooth/
│   │   ├── BluetoothDataSource.kt  # Chunk 1
│   │   └── impl/
│   │       ├── BleBluetoothDataSource.kt   # Chunk 1
│   │       └── MockBluetoothDataSource.kt  # Chunk 1
│   ├── db/
│   │   ├── AppDatabase.kt          # Chunk 3
│   │   ├── dao/
│   │   │   └── SpeedSegmentDao.kt  # Chunk 3
│   │   └── entity/
│   │       └── SpeedSegmentEntity.kt # Chunk 3
│   ├── repository/
│   │   ├── GpsDataRepositoryImpl.kt    # Chunk 1
│   │   └── TestResultRepositoryImpl.kt # Chunk 3
│   └── storage/
│       └── TestResultFileStorage.kt    # Chunk 3
├── viewmodel/
│   ├── GpsDataViewModel.kt         # Chunk 1
│   ├── TestSessionViewModel.kt     # Chunk 2
│   └── TestHistoryViewModel.kt     # Chunk 4
└── ui/
    ├── navigation/
    │   └── AppNavigation.kt        # Chunk 4
    └── screen/
        ├── DeviceConnectionScreen.kt    # Chunk 4
        ├── TestTypeSelectionScreen.kt   # Chunk 4
        ├── TestExecutionScreen.kt       # Chunk 4
        ├── TestResultDetailScreen.kt    # Chunk 4
        └── TestHistoryScreen.kt         # Chunk 4
```

---

*文档生成时间: 2026-03-17*
*预计总实现时间: 8-12小时*
