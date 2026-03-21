# GPS 异常点过滤模块设计规范

> 日期：2026-03-21
> 目标：设计 GPS 异常点过滤模块，解决启动/结束条件误判问题，为后续纵向/横向 G 值分析做准备

## 1. 背景与问题

### 1.1 现状问题

当前启动测试系统的触发和结束条件判断存在以下问题：

1. **触发条件过于简单**：仅判断单点速度值，无加速度验证，GPS 噪声跳变会导致误触发
2. **刹车测试无减速趋势检测**：匀速行驶也可能误触发
3. **无上一帧数据参考**：无法判断速度变化趋势
4. **终点检测不稳健**：跳胎/ABS 导致速度在终点附近振荡

### 1.2 设计目标

- 从数学角度解决 GPS 异常点问题
- 实时提供干净数据用于触发判断
- 完整保留原始数据用于事后精确计算
- 为纵向/横向 G 值分析预留扩展能力

## 2. 核心架构

### 2.1 双缓冲 + 质量评分

```
GpsData 输入 (原始)
         │
         ▼
┌─────────────────────────────────────┐
│  第一层：物理约束过滤                  │
│  ├─ 速度域：|Δv| 超过阈值 → 异常      │
│  ├─ 位置域：|Δd| 超过阈值 → 异常      │
│  └─ 一致性：|v_gps - v_implied| → 异常│
└─────────────────┬───────────────────┘
                  │ (原始点同时入 rawBuffer)
                  ▼
┌─────────────────────────────────────┐
│  第二层：移动中位数滤波 (9点窗口)        │
│  • 40ms × 9 = 360ms                 │
│  • 速度/位置/航向角各自中位数滤波       │
└─────────────────┬───────────────────┘
                  ▼
┌─────────────────────────────────────┐
│  第三层：置信度评分 (0.0 ~ 1.0)        │
│  • hdopFactor × consistencyFactor   │
└─────────────────┬───────────────────┘
                  │
        ┌─────────┴─────────┐
        ▼                   ▼
  实时管道               原始缓冲
  (触发判断)            (事后分析)
```

### 2.2 数据保留策略

```
未触发前：
├── GpsDataFilter 始终维护 2 秒滚动原始缓冲
└── 触发瞬间：将 pre-trigger 缓冲锁入 TestSession

触发后：
└── 全部数据实时传入 TestSession，不丢任何点

终点后：
└── TestSessionViewModel 检测到 end condition 后
    继续收集 1 秒冗余数据，然后停止记录

事后计算：
└── 从 [pre-trigger 2s + 测试全程 + post-end 1s] 中
    用线性插值精确找到起止点
```

## 3. 物理参数

### 3.1 物理约束阈值（25Hz / 40ms 采样）

| 参数 | 阈值 | 说明 |
|------|------|------|
| 最大加速度 | 1.5G (≈ 15 m/s²) | 覆盖极端加速场景（3秒破百 ≈ 0.95G） |
| 最大减速度 | 2.0G (≈ 20 m/s²) | 覆盖强刹车场景（25m刹停 ≈ 1.57G） |
| 40ms 最大速度跳变 | 加速 0.6 km/h，减速 0.8 km/h | 基于上述 G 值推导 |
| 40ms 最大位移 | 基于当前速度计算 | v × 40ms + 安全余量 |

### 3.2 位置-速度一致性检验

```
v_implied = Δd / Δt   (从位置变化反推速度)
| v_gps - v_implied | 过大 → 异常
```

### 3.3 统计滤波参数

| 参数 | 值 | 说明 |
|------|------|------|
| 窗口大小 | 9 点 | 360ms @ 25Hz |
| 滤波方法 | 移动中位数 | 对异常值天然鲁棒 |

### 3.4 置信度评分模型

```
confidence = baseScore × hdopFactor × consistencyFactor

其中：
  baseScore         = 1.0 (正常点) 到 0.5 (被修正的异常点)
  hdopFactor       = hdop < 1 → 1.0, < 2 → 0.9, < 5 → 0.6, 否则 0.3
  consistencyFactor = 1.0 (速度-位置一致) 到 0.5 (严重不一致)
```

## 4. 触发与结束条件

### 4.1 触发条件（加速度检测 + 时序确认）

```
触发条件 = 加速度 > 0.1G (≈ 1 m/s²) 且连续 5 个点确认
```

- **加速度阈值 0.1G**：足以区分 GPS 漂移（≈0 m/s²）和真实加速
- **5 点确认（200ms）**：避免 GPS 噪声单点跳变误触发
- 实测 0-100 加速瞬时可达 0.95G，0.1G 阈值留有充足余量
- GPS 漂移速度在 0.0x km/h 量级，对应加速度接近 0

### 4.2 终点条件（硬逻辑）

```
结束条件 = speed < 1.0 km/h
```

- 硬逻辑，触发后立即停止记录到本次测试
- 终点后保留 1 秒冗余数据供线性插值精确定位
- 跳胎/ABS 振荡问题通过"触发后不丢点 + 事后插值"解决终点附近的精确定位

### 4.3 实时粗判 + 事后精判

```
实时阶段（给用户反馈）：
├── 起点触发：宽松条件（加速度 > 0.1G + 5点确认）
└── 终点检测：严格硬逻辑（speed < 1.0）

事后阶段（计算精确结果）：
├── 从 [2s pre-trigger + 测试全程 + 1s post-end] 中
├── 用线性插值找到精确的起点速度穿越时刻
└── 用线性插值找到精确的终点速度穿越时刻
```

## 5. 模块接口设计

### 5.1 GpsDataFilter 类（纯数据处理，不关心业务生命周期）

```kotlin
class GpsDataFilter(
    private val windowSize: Int = 9,           // 9点窗口（360ms @ 25Hz）
    private val maxAcceleration: Double = 15.0, // 1.5G ≈ 15 m/s²
    private val maxDeceleration: Double = 20.0  // 2.0G ≈ 20 m/s²
) {
    // 输入：单个原始 GPS 点
    // 输出：滤波后的数据 + 置信度 + 加速度
    fun process(raw: GpsData): FilteredGpsData

    // 重置内部窗口状态（测试开始时调用）
    fun reset()
}

// 滤波输出
data class FilteredGpsData(
    val speed: Double,              // 滤波后速度 (km/h)
    val latitude: Double,           // 滤波后纬度
    val longitude: Double,          // 滤波后经度
    val altitude: Double,           // 滤波后海拔
    val bearing: Double,            // 滤波后航向角
    val acceleration: Double,       // 纵向加速度 (m/s²)
    val confidence: Double,         // 置信度 0.0 ~ 1.0
    val isAnomaly: Boolean,         // 是否被修正
    val timestamp: Long,            // 原始时间戳
    val raw: GpsData                // 原始数据引用（用于事后分析）
)
```

### 5.2 GpsDataPoint 数据类

```kotlin
data class GpsDataPoint(
    val elapsedTime: Double,  // 相对测试开始的时间（秒）
    val speed: Double,        // km/h（滤波后）
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val bearing: Double       // 航向角（滤波后）
)
```

> 注：FilteredGpsData 包含更丰富的信息（置信度、加速度、原始数据引用），
> TestSession 可选择存储 FilteredGpsData 或转换为 GpsDataPoint。

### 5.3 TestSessionViewModel 职责扩展

ViewModel 负责维护 pre-trigger 缓冲和测试生命周期：

```kotlin
class TestSessionViewModel {
    // pre-trigger 缓冲：由 ViewModel 维护，存储最近 2s 的 FilteredGpsData
    private val preTriggerBuffer = mutableListOf<FilteredGpsData>()
    private const val PRE_TRIGGER_DURATION_MS = 2000L

    // 每收到一个 FilteredGpsData：
    // 1. 加入 preTriggerBuffer
    // 2. 超过 2s 的旧数据移除
    // 3. 检测触发条件
    // 4. 触发时：锁定 buffer 传给 TestSession

    fun onFilteredData(data: FilteredGpsData) {
        preTriggerBuffer.add(data)
        trimBufferTo2Seconds()

        if (isTriggerConditionMet(data)) {
            startTest(preTriggerBuffer.toList()) // 锁定并传递
        }
    }
}
```

### 5.4 TestSession 数据结构

```kotlin
data class TestSession(
    val id: String,
    val template: TestTemplate,
    val carModel: String,
    val startTime: Long,
    val preTriggerData: List<FilteredGpsData>, // 触发前 2s 滤波数据（锁定时传入）
    val dataPoints: MutableList<FilteredGpsData> = mutableListOf(), // 测试过程数据
    var triggerTime: Long? = null,
    var endTime: Long? = null
)
```

## 6. 扩展预留（下一阶段）

### 6.1 纵向/横向 G 值

```kotlin
data class ExtendedGpsData : FilteredGpsData(
    val longitudinalG: Double,   // 纵向G值（加速为正）
    val lateralG: Double,        // 横向G值（转弯离心力）
    val totalG: Double          // 总G值 √(纵向² + 横向²)
)

// 计算公式：
// longitudinalG = Δv / Δt / 9.81
// lateralG = v² × Δbearing / Δt / 9.81   (bearing单位弧度)
```

### 6.2 置信度应用场景

- G-force 仪表盘：置信度 < 0.5 时灰显或显示警告
- 轨迹可视化：低置信度点用不同颜色/透明度标记
- 测试结果报告：标注数据质量评级

## 7. 与现有代码集成

### 7.1 集成点

```
GpsDataViewModel.gpsData
        │
        ▼
┌───────────────────────┐
│    GpsDataFilter      │  ← 纯数据处理，不关心业务生命周期
│  process(raw) → out   │
└───────────┬───────────┘
            │ FilteredGpsData
            ▼
┌───────────────────────────────────────────────────────┐
│               TestSessionViewModel                    │
│                                                       │
│  preTriggerBuffer (2s滚动) ◄── 持续消费 FilteredData  │
│         │                                             │
│         ▼                                             │
│  检测触发条件 (加速度 > 0.1G + 5点确认)               │
│         │                                             │
│         ▼ 触发时                                      │
│  锁定 preTriggerBuffer → 传入 TestSession            │
│         │                                             │
│         ▼                                             │
│  持续收集到 TestSession.dataPoints                   │
│         │                                             │
│         ▼ 检测到 end condition                       │
│  继续收集 1s 冗余 → 停止记录                          │
└───────────┬───────────────────────────────────────────┘
            │
            ▼
    TestSession
    ├── preTriggerData (触发前 2s)
    ├── dataPoints (测试过程)
    └── 1s post-end 冗余
            │
            ▼
    CalculateResultUseCase
    (从完整数据中线性插值精确定位起止点)
```

### 7.2 需修改的文件

1. **新建** `app/src/main/java/com/race/gps/domain/usecase/GpsDataFilter.kt` — 纯数据处理模块
2. **修改** `TestSession` — 添加 preTriggerData 字段
3. **修改** `TestSessionViewModel` — 集成 GpsDataFilter，维护 preTriggerBuffer，实现触发/结束检测
4. **修改** `CalculateResultUseCase` — 消费 preTriggerData + dataPoints 做插值

## 8. 测试场景

### 8.1 单元测试

| 场景 | 输入 | 预期输出 |
|------|------|---------|
| 正常加速 | 连续 9 个递增速度点 | 全部通过，confidence = 1.0 |
| GPS 速度跳变 | 第 5 个点 speed 突增 20 km/h | 第 5 个点被标记为异常并插值修正 |
| GPS 速度下跌 | 加速中第 5 个点 speed 骤降 10 km/h | 第 5 个点被标记为异常并插值修正 |
| 位置跳变 | 经纬度瞬间跳 100m | 被物理约束层过滤 |
| 静止漂移 | 速度在 0-0.5 km/h 振荡 | 正常通过（未超过加速度阈值） |
| 触发确认 | 5 个点加速度 > 0.1G | 第 5 个点返回 isTriggered = true |
| 窗口填充 | 前 8 个点（窗口未满） | 中位数取实际点的中位数 |

### 8.2 集成测试

| 场景 | 验证点 |
|------|--------|
| 0-100 实测 | pre-trigger 2s 数据完整，触发时刻准确 |
| 100-0 实测 | 终点后 1s 冗余数据存在，线性插值定位精确 |
| 多段测试 | 60-160 等扩展测试类型正常工作 |
| 置信度评分 | 低质量数据（hdop > 5）confidence < 0.5 |
