# 第��阶段：速度传输与智能测试

## 阶段目标

基于第一阶段的28字节RaceChrono协议修复，新增以下功能：

1. **动态速度模拟**：支持多种速度模式和实时调整
2. **智能启动测试**：自动检测测试条件并启动
3. **数据质量监控**：实时监控GPS数据质量
4. **异常处理**：离散数据点兜底处理

---

## 功能模块

### 模块1：动态速度模拟系统

#### 1.1 速度模式

```kotlin
enum class SpeedMode {
    STATIC,           // 静止：固定速度
    CONSTANT,         // 恒定：用户设定速度
    ACCELERATION,     // 加速：线性增加
    DECELERATION,     // 减速：线性减少
    WAVEFORM,         // 波形：正弦速度变化
    REALISTIC,        // 真实：模拟实际驾驶
    CUSTOM            // 自定义：用户定义的速度曲线
}
```

#### 1.2 UI控制面板

**新增Screen: SpeedControlScreen**

- **速度模式选择器**：单选按钮组
- **当前速度显示**：大数字显示当前速度
- **目标速度设置**：
  - Slider滑块（0-300 km/h）
  - 数字输入框
- **加速/减���参数**：
  - 加速率（m/s²）
  - 目标速度
- **实时曲线图**：速度-时间曲线
- **预设场景**：
  - 城市道路（0-60 km/h波动）
  - 高速公路（100-120 km/h）
  - 赛道模式（0-200 km/h）
  - 急刹车测试

#### 1.3 数据生成逻辑

```kotlin
class SpeedController {
    private var currentSpeed: Float = 0f
    private var mode: SpeedMode = SpeedMode.STATIC
    private var targetSpeed: Float = 0f
    private var acceleration: Float = 2.0f // m/s²

    fun updateSpeed(deltaTimeMs: Long): Float {
        when (mode) {
            SpeedMode.STATIC -> {
                // 速度不变
            }
            SpeedMode.CONSTANT -> {
                currentSpeed = targetSpeed
            }
            SpeedMode.ACCELERATION -> {
                val deltaV = acceleration * deltaTimeMs / 1000f
                currentSpeed = min(currentSpeed + deltaV, targetSpeed)
            }
            SpeedMode.DECELERATION -> {
                val deltaV = acceleration * deltaTimeMs / 1000f
                currentSpeed = max(currentSpeed - deltaV, targetSpeed)
            }
            SpeedMode.WAVEFORM -> {
                val t = System.currentTimeMillis() / 1000.0
                currentSpeed = targetSpeed + sin(t) * targetSpeed * 0.3f
            }
            SpeedMode.REALISTIC -> {
                // 模拟真实驾驶：随机加减速
                if (Random.nextBoolean()) {
                    currentSpeed += Random.nextFloat() * 2f
                } else {
                    currentSpeed -= Random.nextFloat() * 1f
                }
                currentSpeed = currentSpeed.coerceIn(0f, targetSpeed)
            }
        }
        return currentSpeed
    }
}
```

---

### 模块2：智能启动测试系统

#### 2.1 启动条件检测

**检测项：**
1. **连接状态**：BLE设备已连接
2. **数据接收**：能接收到GPS数据
3. **数据质量**：
   - 卫星数 ≥ 6
   - HDOP < 2.0
   - 速度数据有效
4. **用户确认**：用户点击开始测试

#### 2.2 自动启动流程

```kotlin
class SmartTestLauncher {
    data class LaunchCondition(
        val name: String,
        val check: () -> Boolean,
        val description: String
    )

    private val conditions = listOf(
        LaunchCondition(
            "BLE连接",
            { bluetoothDataSource.isConnected },
            "确保GPS设备已连接"
        ),
        LaunchCondition(
            "数据接收",
            { lastDataAge < 1000 },
            "能正常接收GPS数据"
        ),
        LaunchCondition(
            "卫星数量",
            { gpsData.satelliteCount >= 6 },
            "至少6颗卫星"
        ),
        LaunchCondition(
            "定位精度",
            { gpsData.hdop < 2.0 },
            "HDOP < 2.0"
        ),
        LaunchCondition(
            "速度数据",
            { gpsData.speed >= 0 },
            "速度数据有效"
        )
    )

    fun canLaunch(): Boolean {
        return conditions.all { it.check() }
    }

    fun getUnmetConditions(): List<LaunchCondition> {
        return conditions.filter { !it.check() }
    }
}
```

#### 2.3 UI显示

**新增Screen: SmartLaunchScreen**

- **条件检查列表**：显示所有启动条件状态
  - ✅ 已满足
  - ⏳ 等待中
  - ❌ 未满足
- **倒计时启动**：5秒倒计时自动开始
- **手动启动按钮**：条件满足时可点击
- **实时状态更新**：每秒更新条件状态

---

### 模块3：数据质量监控

#### 3.1 监控指标

```kotlin
data class DataQuality(
    val satelliteCount: Int,           // 卫星数量
    val signalStrength: SignalStrength, // 信号强度
    val hdop: Float,                    // 水平精度因子
    val vdop: Float,                    // 垂直精度因子
    val dataAge: Long,                  // 数据年龄（ms）
    val packetLoss: Float,              // 丢包率 (%)
    val frequency: Float,               // 数据频率 (Hz)
    val overall: QualityLevel           // 综合质量等级
)

enum class QualityLevel {
    EXCELLENT,  // 优秀：所有指标良好
    GOOD,       // 良好：基本满足测试要求
    FAIR,       // 一般：部分指标不理想
    POOR        // 差：不建议测试
}
```

#### 3.2 质量计算逻辑

```kotlin
fun calculateQuality(gpsData: GpsData, stats: DataStats): DataQuality {
    val satelliteScore = when {
        gpsData.satelliteCount >= 12 -> 100
        gpsData.satelliteCount >= 8 -> 80
        gpsData.satelliteCount >= 6 -> 60
        else -> 40
    }

    val hdopScore = when {
        gpsData.hdop < 1.0 -> 100
        gpsData.hdop < 2.0 -> 80
        gpsData.hdop < 5.0 -> 50
        else -> 20
    }

    val packetLossScore = (100 - stats.packetLossRate).toInt()

    val overallScore = (satelliteScore + hdopScore + packetLossScore) / 3

    val overall = when {
        overallScore >= 80 -> QualityLevel.EXCELLENT
        overallScore >= 60 -> QualityLevel.GOOD
        overallScore >= 40 -> QualityLevel.FAIR
        else -> QualityLevel.POOR
    }

    return DataQuality(
        satelliteCount = gpsData.satelliteCount,
        signalStrength = stats.signalStrength,
        hdop = gpsData.hdop,
        vdop = gpsData.vdop,
        dataAge = stats.dataAge,
        packetLoss = stats.packetLossRate,
        frequency = stats.frequency,
        overall = overall
    )
}
```

#### 3.3 UI显示

**新增Component: DataQualityCard**

```kotlin
@Composable
fun DataQualityCard(quality: DataQuality) {
    Card {
        Column {
            Text("数据质量", style = MaterialTheme.typography.titleMedium)

            // 综合质量等级
            QualityBadge(quality.overall)

            // 详细指标
            QualityIndicator("卫星数", "${quality.satelliteCount}/12")
            QualityIndicator("HDOP", String.format("%.1f", quality.hdop))
            QualityIndicator("信号强度", quality.signalStrength.name)
            QualityIndicator("丢包率", "${quality.packetLoss.toInt()}%")
            QualityIndicator("频率", "${quality.frequency.toInt()} Hz")
        }
    }
}
```

---

### 模块4：离散数据点处理

#### 4.1 异常检测

```kotlin
class AnomalyDetector {
    data class Anomaly(
        val type: AnomalyType,
        val severity: Severity,
        val description: String,
        val timestamp: Long
    )

    enum class AnomalyType {
        MISSING_DATA,      // 数据缺失
        OUT_OF_RANGE,      // 数值超出范围
        SUDDEN_JUMP,       // 突变
        INCONSISTENT,      // 数据不一致
        STALE_DATA         // 数据过期
    }

    enum class Severity {
        INFO,     // 信息
        WARNING,  // 警告
        ERROR     // 错误
    }

    fun detect(current: GpsData, previous: GpsData?, stats: DataStats): List<Anomaly> {
        val anomalies = mutableListOf<Anomaly>()

        // 检查数据年龄
        if (stats.dataAge > 2000) {
            anomalies.add(Anomaly(
                AnomalyType.STALE_DATA,
                Severity.WARNING,
                "数据过期: ${stats.dataAge}ms",
                System.currentTimeMillis()
            ))
        }

        // 检查速度突变
        if (previous != null) {
            val speedDiff = abs(current.speed - previous.speed)
            if (speedDiff > 20) { // 速度突变超过20 km/h
                anomalies.add(Anomaly(
                    AnomalyType.SUDDEN_JUMP,
                    Severity.WARNING,
                    "速度突变: ${"%.1f".format(previous.speed)} -> ${"%.1f".format(current.speed)}",
                    System.currentTimeMillis()
                ))
            }
        }

        // 检查数值范围
        if (current.speed < 0 || current.speed > 500) {
            anomalies.add(Anomaly(
                AnomalyType.OUT_OF_RANGE,
                Severity.ERROR,
                "速度异常: ${current.speed} km/h",
                System.currentTimeMillis()
            ))
        }

        // 检查卫星数
        if (current.satelliteCount < 4) {
            anomalies.add(Anomaly(
                AnomalyType.INCONSISTENT,
                Severity.WARNING,
                "卫星数过少: ${current.satelliteCount}",
                System.currentTimeMillis()
            ))
        }

        return anomalies
    }
}
```

#### 4.2 数据平滑处理

```kotlin
class DataSmoothing {
    private val windowSize = 5
    private val dataWindow = ArrayDeque<GpsData>()

    fun smooth(data: GpsData): GpsData {
        dataWindow.addLast(data)
        if (dataWindow.size > windowSize) {
            dataWindow.removeFirst()
        }

        if (dataWindow.size < windowSize) {
            return data // 数据不足，返回原值
        }

        // 移动平均平滑
        val avgSpeed = dataWindow.map { it.speed }.average().toFloat()
        val avgLat = dataWindow.map { it.latitude }.average()
        val avgLon = dataWindow.map { it.longitude }.average()

        return data.copy(
            speed = avgSpeed,
            latitude = avgLat,
            longitude = avgLon
        )
    }
}
```

#### 4.3 插值处理

```kotlin
class DataInterpolator {
    fun interpolate(
        previous: GpsData,
        current: GpsData,
        missingDataPoints: Int
    ): List<GpsData> {
        if (missingDataPoints <= 0) return listOf(current)

        val result = mutableListOf<GpsData>()
        val timeStep = (current.timestamp - previous.timestamp) / (missingDataPoints + 1)

        for (i in 1..missingDataPoints) {
            val ratio = i.toFloat() / (missingDataPoints + 1)
            val interpolatedSpeed = previous.speed + (current.speed - previous.speed) * ratio
            val interpolatedLat = previous.latitude + (current.latitude - previous.latitude) * ratio
            val interpolatedLon = previous.longitude + (current.longitude - previous.longitude) * ratio

            result.add(GpsData(
                timestamp = previous.timestamp + timeStep * i,
                speed = interpolatedSpeed,
                latitude = interpolatedLat,
                longitude = interpolatedLon,
                // ... 其他字段
                isInterpolated = true // 标记为插值数据
            ))
        }

        result.add(current)
        return result
    }
}
```

---

## UI/UX设计

### 主界面布局

```
┌─────────────────────────────────────────┐
│  GPS测试应用                             │
├─────────────────────────────────────────┤
│                                         │
│  [连接状态: 已连接] 🟢                  │
│                                         │
│  ┌───────────────────────────────────┐  │
│  │  数据质量                         │  │
│  │  🟢 优秀 (卫星:12 HDOP:1.0)      │  │
│  └───────────────────────────────────┘  │
│                                         │
│  ┌───────────────────────────────────┐  │
│  │  速度控制                         │  │
│  │  [恒定速度] [60] km/h             │  │
│  │  ▁▃▅▇▉▇▅▃▂ (速度曲线)             │  │
│  └───────────────────────────────────┘  │
│                                         │
│  ┌───────────────────────────────────┐  │
│  │  智能启动                         │  │
│  │  ✅ BLE连接                       │  │
│  │  ✅ 数据接收                      │  │
│  │  ✅ 卫星数量 (12)                 │  │
│  │  ✅ 定位精度 (HDOP:1.0)           │  │
│  │  [开始测试]                       │  │
│  └───────────────────────────────────┘  │
│                                         │
│  [测试历史] [数据导出] [设置]           │
└─────────────────────────────────────────┘
```

---

## 技术实现要点

### 1. 架构设计

```
┌─────────────────────────────────────┐
│          UI Layer                   │
│  ┌──────────┐  ┌────────────────┐  │
│  │ 速度控制  │  │ 智能启动界面   │  │
│  └──────────┘  └────────────────┘  │
└─────────────────────────────────────┘
           ↓                ↓
┌─────────────────────────────────────┐
│      ViewModel Layer                │
│  ┌──────────┐  ┌────────────────┐  │
│  │速度控制器 │  │ 启动控制器     │  │
│  │ViewModel │  │ ViewModel      │  │
│  └──────────┘  └────────────────┘  │
└─────────────────────────────────────┘
           ↓                ↓
┌─────────────────────────────────────┐
│      Domain Layer                   │
│  ┌──────────┐  ┌────────────────┐  │
│  │速度模型   │  │ 质量评估       │  │
│  │异常检测   │  │ 数据平滑       │  │
│  └──────────┘  └────────────────┘  │
└─────────────────────────────────────┘
           ↓                ↓
┌─────────────────────────────────────┐
│      Data Layer                     │
│  ┌──────────┐  ┌────────────────┐  │
│  │GPS数据源  │  │ 蓝牙连接       │  │
│  └──────────┘  └────────────────┘  │
└─────────────────────────────────────┘
```

### 2. 状态管理

```kotlin
data class SpeedControlState(
    val mode: SpeedMode = SpeedMode.STATIC,
    val currentSpeed: Float = 0f,
    val targetSpeed: Float = 0f,
    val acceleration: Float = 2.0f,
    val isAdjusting: Boolean = false
)

data class SmartLaunchState(
    val conditions: List<LaunchCondition> = emptyList(),
    val canLaunch: Boolean = false,
    val countdown: Int = 0,
    val isLaunching: Boolean = false
)

data class QualityMonitorState(
    val quality: DataQuality? = null,
    val anomalies: List<Anomaly> = emptyList(),
    val isMonitoring: Boolean = true
)
```

### 3. 数据流

```
BLE数据 → 解析器 → 数据源 → ViewModel → UI
                ↓
           质量监控 → 异常检测 → 数据平滑 → UI
                ↓
           速度控制器 → 模拟器 → BLE发送
```

---

## 开发计划

### Phase 2.1: 速度控制模拟（1-2天）
- [ ] 创建速度控制UI
- [ ] 实现各种速度模式
- [ ] 添加速度曲线图
- [ ] 模拟器端集成

### Phase 2.2: 智能启动系统（1天）
- [ ] 实现条件检测逻辑
- [ ] 创建启动条件UI
- [ ] 添加自动倒计时
- [ ] 集成到测试流程

### Phase 2.3: 数据质量监控（1天）
- [ ] 实现质量评估算法
- [ ] 创建质量监控UI
- [ ] 添加实时指标显示
- [ ] 集成异常检测

### Phase 2.4: 离散数据处理（1天）
- [ ] 实现异常检测
- [ ] 添加数据平滑
- [ ] 实现插值算法
- [ ] 处理逻辑集成

### Phase 2.5: 集成测试（1天）
- [ ] 端到端测试
- [ ] 性能优化
- [ ] 用户体验调整
- [ ] 文档完善

**总计：5-6天**

---

## 验收标准

### 1. 速度控制
- [ ] 可以实时调整速度（0-300 km/h）
- [ ] 支持7种速度模式
- [ ] 速度曲线正确显示
- [ ] 接收端速度显示正确

### 2. 智能启动
- [ ] 能准确检测所有启动条件
- [ ] 条件状态实时更新
- [ ] 倒计时自动启动正常
- [ ] 手动启动功能正常

### 3. 数据质量
- [ ] 质量等级计算正确
- [ ] 所有指标实时显示
- [ ] 异常情况能及时检测

### 4. 数据处理
- [ ] 异常数据能正确识别
- [ ] 数据平滑有效
- [ ] 插值处理合理

---

## 备注

- **性能要求**：所有UI操作60fps流畅运行
- **兼容性**：支持Android 8+
- **稳定性**：长时间运行无内存泄漏
- **用户体验**：操作简单直观，反馈及时
