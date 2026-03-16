# GPS测试应用重新设计规范

**日期**: 2026-03-17
**版本**: 1.0
**状态**: 待审查

## 1. 概述

### 1.1 背景

当前GPS测试应用存在以下核心问题：
- 数据流向不统一，导致不同页面数据不一致（GPS页面有数据，测试页面无数据）
- 架构混乱，Service层概念不清晰
- 功能设计不完整，缺少刹车测试、数据分析等核心功能

### 1.2 设计目标

基于RaceChrono GPS蓝牙协议，重新设计应用架构和功能：
- **一期重心**：0-100加速测试 + 100-0刹车测试（纯直线性能测试）
- **架构原则**：单一数据源、清晰的数据流向、模块化设计
- **用户体验**：简化操作流程、详细数据分析、精美海报分享

### 1.3 核心价值

- 解决数据不一致问题，确保所有UI组件从同一数据源获取数据
- 提供完整的直线性能测试功能（加速+刹车）
- 详细的数据分析（10km/h速度分段、曲线对比、统计分析）
- 便捷的社交分享（海报图片生成）

## 2. 架构设计

### 2.1 整体架构

采用清晰的分层架构，确保单向数据流：

```
UI Layer (Compose)
    ↓ 订阅
ViewModel Layer (Koin)
    ↓ 调用
Domain Layer (UseCase)
    ↓ 调用
Data Layer (Repository)
    ↓ 订阅
Bluetooth Layer (数据源)
```

### 2.2 核心概念模型

**测试模板（TestTemplate）**
- 定义测试类型、触发条件、结束条件
- 一期内置：加速模板（0-100）、刹车模板（100-0）
- 架构支持后续扩展

**测试会话（TestSession）**
- 代表一次完整的测试过程
- 包含原始GPS数据流、计算结果、元数据
- 状态机：Idle → Waiting → Running → Completed

**测试结果（TestResult）**
- 从TestSession计算得出的结果数据
- 包含总时间、总距离、分段数据、曲线数据
- 可序列化存储到数据库

### 2.3 数据流设计（解决核心问题）

**单一数据源原则**：
```
BLE设备
  → BluetoothDataSource.dataFlow (唯一数据源)
  → GpsDataRepository
  → GpsDataViewModel.gpsDataFlow (单例)
  → 所有UI组件
```

**关键设计点**：
1. `BluetoothDataSource` 是唯一的数据发射点
2. `GpsDataViewModel` 在Koin中注册为单例，所有页面共享
3. 所有UI组件订阅同一个 `gpsDataFlow`
4. 避免多个数据通道导致的不一致

### 2.4 Bluetooth层重新设计

**移除Android Service概念**：
- 不使用Android Service组件（无需跨进程通信）
- 改为普通Kotlin类 + 协程管理子线程
- 包结构：`bluetooth/` 替代 `service/`

**BluetoothDataSource（唯一数据源）**：
```kotlin
class BluetoothDataSource(
    private val context: Context,
    private val parser: RaceChronoParser
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 唯一的数据输出口
    private val _dataFlow = MutableStateFlow(GpsData.Empty)
    val dataFlow: StateFlow<GpsData> = _dataFlow.asStateFlow()

    private var bleConnection: BleConnection? = null

    fun connect(deviceAddress: String) {
        scope.launch {
            bleConnection = BleConnection(context, deviceAddress) { rawData ->
                val gpsData = parser.parse(rawData)
                _dataFlow.value = gpsData
            }
            bleConnection?.connect()
        }
    }
}
```

## 3. 核心功能设计

### 3.1 测试类型

**一期实现**：
1. **0-100加速测试**
   - 起始条件：速度 > 5 km/h
   - 结束条件：速度 >= 100 km/h
   - 记录：总时间、总距离、加速度曲线、10km/h分段数据

2. **100-0刹车测试**
   - 起始条件：速度在 95-105 km/h 范围内
   - 结束条件：速度 <= 1 km/h
   - 记录：总时间、总距离、减速度曲线、10km/h分段数据

**二期功能**（待实现）：
- 60-160加速测试
- 0-200加速测试
- 其他自定义测试

### 3.2 测试流程

**状态机设计**：
```kotlin
sealed class TestState {
    object Idle : TestState()

    // 用户点击"开始测试"后进入等待状态
    data class Waiting(
        val template: TestTemplate,
        val carModel: CarModel
    ) : TestState()

    // 速度达到起始条件后真正开始计时
    data class Running(
        val session: TestSession,
        val triggerSpeed: Double
    ) : TestState()

    // 速度达到目标值后自动结束
    data class Completed(val result: TestResult) : TestState()
}
```

**用户操作流程**：
1. 选择测试类型（0-100加速 / 100-0刹车）
2. 选择车型
3. 点击"开始测试" → 进入Waiting状态
4. 系统监测速度，达到起始条件 → 进入Running状态，开始计时
5. 系统监测速度，达到目标值 → 进入Completed状态，自动结束
6. 查看结果详情

**一期实现范围**：
- ✅ 基本的起始点检测（速度达到起始条件）
- ✅ 基本的结束点检测（速度达到目标值）
- ⏸️ 复杂的失败检测（速度异常波动）
- ⏸️ 离散点修复（GPS信号丢失、速度跳变）
- ⏸️ 智能触发（识别加速/刹车意图）

### 3.3 数据分析

**核心指标**：
- 总时间（秒）
- 总距离（米）
- 平均加速度（G）
- 最大加速度（G）

**分段数据（10km/h速度段）**：
- 加速测试：0-10、10-20、20-30...90-100
- 刹车测试：100-90、90-80、80-70...10-0
- 每段记录：用时、距离

**曲线数据**：
- 速度-时间曲线
- 加速度-时间曲线
- 支持多次测试叠加对比

**统计分析**：
- 最佳成绩
- 平均成绩
- 按车型分组统计
- 按时间段统计

### 3.4 海报分享

**功能**：
- 生成精美的测试结果海报图片
- 支持多种模板风格（简约、运动、科技）
- 包含关键数据和速度曲线
- 保存到相册或直接分享

**海报内容**：
- 测试类型和成绩（大标题）
- 速度曲线图
- 关键指标（时间、距离、加速度）
- 车型信息
- 测试日期

## 4. 数据模型设计

### 4.1 核心数据模型

**GpsData（GPS数据）**：
```kotlin
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
    val frequency: Double        // Hz
)
```

**TestTemplate（测试模板）**：
```kotlin
sealed class TestTemplate(
    val id: String,
    val name: String,
    val description: String
) {
    abstract fun shouldTrigger(gpsData: GpsData): Boolean
    abstract fun shouldEnd(gpsData: GpsData, session: TestSession): Boolean

    object Acceleration0To100 : TestTemplate(...)
    object Braking100To0 : TestTemplate(...)
}
```

**TestSession（测试会话）**：
```kotlin
data class TestSession(
    val id: String,
    val template: TestTemplate,
    val carModel: CarModel,
    val startTime: Long,
    val dataPoints: MutableList<GpsDataPoint>,
    var triggerTime: Long?,
    var endTime: Long?
)
```

**TestResult（测试结果）**：
```kotlin
data class TestResult(
    val id: String,
    val sessionId: String,
    val template: TestTemplate,
    val carModel: CarModel,
    val timestamp: Long,
    val totalTime: Double,       // 秒
    val totalDistance: Double,   // 米
    val avgAcceleration: Double, // G
    val maxAcceleration: Double, // G
    val segments: List<SpeedSegment>,
    val dataPoints: List<GpsDataPoint>
)
```

**SpeedSegment（速度分段）**：
```kotlin
data class SpeedSegment(
    val startSpeed: Int,    // 起始速度 (km/h)
    val endSpeed: Int,      // 结束速度 (km/h)
    val time: Double,       // 该段用时 (秒)
    val distance: Double    // 该段距离 (米)
)
```

### 4.2 数据库设计

**利用已有的Room架构**：
- TestRecordEntity（测试记录）
- AccelerationDataPointEntity（数据点）
- CarModelEntity（车型）

**新增字段**：
- TestRecordEntity 添加 `testTemplateId` 字段
- 添加 `avgAcceleration`、`maxAcceleration` 字段

## 5. UI/UX设计

### 5.1 页面结构

```
主页面（底部导航）
├── 测试 (Test)
├── 历史 (History)
└── 设置 (Settings)
```

### 5.2 测试页面流程

**设备连接页** → **测试类型选择页** → **测试执行页** → **结果详情页**

**关键交互**：
1. 自动检测已保存设备并尝试连接
2. 显示实时GPS信号质量
3. 点击卡片选择测试类型
4. 必须选择车型才能继续
5. 点击"开始测试"进入等待状态
6. 实时显示当前速度和进度
7. 达到目标自动结束
8. 显示完整结果和曲线

### 5.3 历史页面

**功能**：
- 按时间倒序显示测试记录
- 支持按测试类型和车型筛选
- 点击记录查看详情
- 长按记录删除
- 显示统计摘要

### 5.4 对比页面

**功能**：
- 选择两次测试进行对比
- 叠加显示速度曲线
- 高亮显示差异（进步/退步）
- 对比分段数据

### 5.5 海报分享页面

**功能**：
- 实时预览海报效果
- 选择模板风格
- 保存到相册或直接分享

## 6. 技术实现

### 6.1 依赖注入（Koin）

```kotlin
val bluetoothModule = module {
    single { RaceChronoParser() }
    single { BluetoothDataSource(androidContext(), get()) }
}

val dataModule = module {
    single { GpsDataRepository(get()) }
    single { TestSessionRepository(get()) }
    single { CarModelRepository(get()) }
}

val domainModule = module {
    factory { CalculateResultUseCase() }
    factory { GeneratePosterUseCase(androidContext()) }
}

val viewModelModule = module {
    // 共享的GpsDataViewModel（单例）
    single { GpsDataViewModel(get()) }
    viewModel { TestSessionViewModel(get(), get(), get()) }
    viewModel { TestResultViewModel(get()) }
    viewModel { TestHistoryViewModel(get()) }
}
```

### 6.2 关键UseCase

**CalculateResultUseCase**：
- 计算总时间、总距离
- 计算加速度（平均值、最大值）
- 计算10km/h速度分段数据
- 生成TestResult对象

**GeneratePosterUseCase**：
- 使用Canvas绘制海报
- 支持多种模板风格
- 绘制速度曲线
- 返回Bitmap对象

### 6.3 协程管理

- BluetoothDataSource 使用独立的 CoroutineScope（Dispatchers.IO）
- ViewModel 使用 viewModelScope
- Repository 层的挂起函数在 Dispatchers.IO 执行

## 7. 实现计划

### 7.1 一期功能（核心）

**必须实现**：
- ✅ 重构Bluetooth层（BluetoothDataSource + 单一数据源）
- ✅ 实现GpsDataViewModel（单例共享）
- ✅ 实现测试状态机（Waiting → Running → Completed）
- ✅ 实现0-100加速测试
- ✅ 实现100-0刹车测试
- ✅ 实现结果计算（总时间、距离、分段数据）
- ✅ 实现UI页面（设备连接、测试类型选择、测试执行、结果详情）
- ✅ 实现历史记录（列表、筛选、删除）
- ✅ 实现海报生成和分享

### 7.2 二期功能（增强）

**待实现**：
- ⏸️ 复杂的失败检测（速度异常波动）
- ⏸️ 离散点修复（GPS信号丢失、速度跳变）
- ⏸️ 智能触发（识别加速/刹车意图）
- ⏸️ 更多测试类型（60-160、0-200等）
- ⏸️ 数据导出（CSV/Excel）
- ⏸️ 云端同步

## 8. 风险与挑战

### 8.1 技术风险

**GPS数据精度**：
- 10Hz采样率可能存在延迟
- 速度跳变和信号丢失需要处理
- 缓解：一期使用简单的阈值判断，二期实现复杂的离散点修复

**性能问题**：
- 实时数据处理可能影响UI流畅度
- 缓解：使用协程和Flow，确保数据处理在后台线程

### 8.2 用户体验风险

**测试触发时机**：
- 用户可能不理解"等待状态"
- 缓解：清晰的UI提示和状态说明

**数据准确性**：
- 用户可能质疑测试结果的准确性
- 缓解：显示GPS信号质量、卫星数量等指标

## 9. 成功标准

### 9.1 功能完整性

- ✅ 支持0-100加速和100-0刹车测试
- ✅ 数据流向统一，所有页面数据一致
- ✅ 提供详细的数据分析（分段数据、曲线图）
- ✅ 支持海报生成和分享

### 9.2 用户体验

- ✅ 操作流程简单直观
- ✅ 实时反馈清晰
- ✅ 测试结果准确可靠

### 9.3 代码质量

- ✅ 架构清晰，易于扩展
- ✅ 单一数据源，避免数据不一致
- ✅ 模块化设计，便于维护

## 10. 附录

### 10.1 术语表

- **RaceChrono GPS协议**：一种用于高精度GPS数据传输的蓝牙协议
- **测试模板**：定义测试类型和触发条件的配置
- **测试会话**：一次完整的测试过程
- **速度分段**：将测试过程按10km/h速度段划分

### 10.2 参考资料

- RaceChrono GPS协议文档
- Android BLE开发指南
- Jetpack Compose官方文档
- Koin依赖注入框架文档
