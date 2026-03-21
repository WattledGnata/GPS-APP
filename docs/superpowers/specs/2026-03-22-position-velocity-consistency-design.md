# GPS 位置-速度一致性检验设计规范

> 日期：2026-03-22
> 目标：在 GpsDataFilter 中实现位置-速度一致性检验，为赛道轨迹和 G 值分析提供干净的位置数据
> 背景：基于 2026-03-21 的 GPS 异常点过滤模块设计规范，补充 3.2 节要求的物理一致性校验

## 1. 设计决策

### 核心原则

**速度和位置同等重要**。从未来赛道轨迹和 G 值球的需求来看：
- 赛道轨迹需要干净的位置数据避免锯齿化
- G 值球需要纵向 G（速度变化）和横向 G（航向/位置变化），任一维度噪声都会污染 G 值计算
- 真实行车中，位置跳变比速度跳变更不可能发生——车不可能瞬移

因此，**位置滤波与速度滤波同等对待**，两者各自独立维护滤波窗口。

### 方案选择

**位置独立 9 点中位数滤波 + 原始数据一致性校验**

- 速度和位置各自独立维护 9 点中位数滤波窗口（windowSize=9 @ 25Hz ≈ 360ms）
- 位置-速度一致性检验在**原始数据层**做（校验原始数据是否一致）
- 一致性结果影响 `consistencyFactor`，不直接修正滤波后的速度/位置
- 滤波后的 lat/lon 直接可用于赛道轨迹绘制

## 2. 架构

```
GpsData 输入 (原始)
         │
         ▼
┌─────────────────────────────────────┐
│  第一层：物理约束检查                  │
│  ├─ 速度域：|Δv| 超过 1.5G/2.0G → 异常│
│  └─ 位置域：|Δd| 超过阈值 → 异常      │
└─────────────────┬───────────────────┘
                  │ (原始点同时入三个窗口)
                  ▼
┌─────────────────────────────────────┐
│  第二层：位置-速度一致性检验           │
│  ├─ v_implied = Δd / Δt             │
│  ├─ |v_gps - v_implied| > 容差 →    │
│  │    降低 consistencyFactor          │
│  └─ 低速/静止/航向剧变时降权处理      │
└─────────────────┬───────────────────┘
                  ▼
┌─────────────────────────────────────┐
│  第三层：移动中位数滤波 (9点窗口)       │
│  • speedWindow  → 滤波后速度          │
│  • latWindow    → 滤波后纬度          │
│  • lonWindow    → 滤波后经度          │
│  • bearingWindow → 滤波后航向        │
└─────────────────┬───────────────────┘
                  ▼
┌─────────────────────────────────────┐
│  第四层：置信度评分                    │
│  confidence = baseScore ×            │
│              hdopFactor ×             │
│              consistencyFactor        │
└─────────────────────────────────────┘
```

## 3. 关键算法

### 3.1 Δd 计算（简化平面近似）

```
Δlat_m = |Δlat| × 111320
Δlon_m = |Δlon| × 111320 × cos(lat_rad)
Δd = √(Δlat_m² + Δlon_m²)   // 米
```

- 40ms 间隔短距离下，简化公式误差 < 0.1%，远优于 Haversine 开方计算开销
- 111320 = 1° 纬度对应的米数（近似常数）
- 经度距离需乘以 cos(lat) 修正

### 3.2 v_implied 计算

```
dt = (current.timestamp - prev.timestamp) / 1000.0  // 秒
v_implied_kmh = (Δd / dt) × 3.6
```

### 3.3 多级容差表

| 速度范围 | Δd 条件 | 容差（|v_gps - v_implied|）|
|----------|---------|------------------------------|
| speed < 5 km/h 且 Δd < 0.5m | — | 跳过一致性检查 |
| speed < 5 km/h | Δd ≥ 0.5m | < 3 km/h |
| 5 ≤ speed < 60 km/h | — | < 5 km/h |
| speed ≥ 60 km/h | — | < 10 km/h |

### 3.4 consistencyFactor 计算

```
|Δ| = |v_gps - v_implied|
ratio = |Δ| / tolerance
consistencyFactor = when {
    ratio <= 1.0 -> 1.0      // 一致
    ratio <= 2.0 -> 0.8      // 轻微不一致
    ratio <= 3.0 -> 0.6      // 中度不一致
    else -> 0.3              // 严重不一致
}
```

### 3.5 置信度模型

```
confidence = baseScore × hdopFactor × consistencyFactor
```

- `baseScore` = 1.0（正常点）或 0.5（被速度物理约束修正的异常点）
- `hdopFactor` = hdop < 1 → 1.0, < 2 → 0.9, < 5 → 0.6, 否则 0.3
- `consistencyFactor` = 1.0 ~ 0.3（新增）
- 最终 `confidence` ∈ [0.0, 1.0]

## 4. 边界情况处理

| 场景 | 处理策略 |
|------|----------|
| dt ≤ 0 或 dt > 1000ms | 跳过一致性检查，记录 previousRaw/previousPosition 状态 |
| Δd < 0.01m | 跳过（被 GPS 位置噪声淹没） |
| 航向变化 > 30°/s | consistencyFactor × 0.8（降权不跳过） |
| GPS 信号丢失 > 200ms | 重置 previousPosition，等待新起点重新建立 |
| HDOP > 3.0 | consistencyFactor × 0.5（降权） |

## 5. 数据结构变更

### FilteredGpsData 新增字段

```kotlin
data class FilteredGpsData(
    val speed: Double,              // 滤波后速度 (km/h)
    val latitude: Double,           // 滤波后纬度（新增）
    val longitude: Double,          // 滤波后经度（新增）
    val altitude: Double,           // 滤波后海拔
    val bearing: Double,            // 滤波后航向角（新增）
    val acceleration: Double,       // 纵向加速度 (m/s²)
    val confidence: Double,         // 置信度 0.0 ~ 1.0
    val isAnomaly: Boolean,         // 是否被速度物理约束修正
    val isTestTriggered: Boolean = false,
    val timestamp: Long,
    val raw: GpsData,
    val consistencyFactor: Double = 1.0,  // 位置-速度一致性因子（新增）
    val isPositionAnomaly: Boolean = false  // 位置异常标记（新增）
)
```

## 6. GpsDataFilter 内部结构变更

### 滚动窗口（新增）

```kotlin
class GpsDataFilter(
    private val windowSize: Int = 9,
    private val maxAcceleration: Double = 15.0,
    private val maxDeceleration: Double = 20.0
) {
    private val speedWindow = mutableListOf<Double>()
    private val latWindow = mutableListOf<Double>()      // 新增
    private val lonWindow = mutableListOf<Double>()      // 新增
    private val bearingWindow = mutableListOf<Double>()  // 新增

    private var previousRaw: GpsData? = null
    private var previousPosition: Pair<Double, Double>? = null  // lat, lon 新增
}
```

## 7. 与现有代码集成

### 7.1 无需修改的文件

- `TestSession` — 已有 preTriggerData 字段，无需变更
- `TestSessionViewModel` — preTriggerBuffer 逻辑不变，只消费 FilteredGpsData
- `CalculateResultUseCase` — 已有使用 lat/lon 计算距离的逻辑，直接使用滤波后数据

### 7.2 需修改的文件

| 文件 | 修改内容 |
|------|----------|
| `GpsDataFilter.kt` | 增加三个位置窗口、一致性检验逻辑、consistencyFactor 计算 |

## 8. 测试场景

| 场景 | 输入 | 预期输出 |
|------|------|---------|
| 正常加速 | 连续 9 个递增速度点，位置平滑变化 | speed/lat/lon 全部中位数滤波通过，consistencyFactor = 1.0 |
| GPS 位置跳变 | 经纬度瞬间跳 100m | 异常点标记为 isPositionAnomaly = true，consistencyFactor 降低 |
| 静止漂移 | 速度在 0-0.5 km/h 振荡，Δd < 0.5m | 跳过一致性检查，consistencyFactor 保持 1.0 |
| 高速一致性 | 速度 120 km/h，位置平滑变化 | 一致性检验通过，容差 < 10 km/h |
| 航向剧变 | 40ms 内 bearing 变化 90° | consistencyFactor × 0.8 降权 |
| 信号丢失恢复 | 300ms 无数据后恢复 | 重置窗口，重新建立一致性基准 |

## 9. 与赛道轨迹和 G 值球的关联

### 赛道轨迹
- 滤波后的 lat/lon 经 9 点中位数处理，轨迹线条平滑
- isPositionAnomaly 可用于在轨迹上标记低质量点（如用不同颜色/透明度）

### G 值球
- 纵向 G = Δv / Δt / 9.81（基于滤波后速度）
- 横向 G = v² × Δbearing / Δt / 9.81（基于滤波后速度和 bearing）
- 一致性检验确保速度和位置数据在 40ms 尺度上互相印证，G 值计算可靠

## 10. 修订历史

| 版本 | 日期 | 修改内容 |
|------|------|----------|
| 1.0 | 2026-03-22 | 初始版本：位置独立9点中位数滤波 + 原始数据一致性校验 |
