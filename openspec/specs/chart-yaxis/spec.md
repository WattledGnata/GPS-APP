# chart-yaxis Specification

## Purpose
TBD - created by archiving change robust-chart-yaxis-scaling. Update Purpose after archive.
## Requirements
### Requirement: robustRange 纯函数 IQR Tukey 算法

`robustRange(values: List<Double>): Pair<Double, Double>` SHALL：
- 当 `values.size < 4` 时 fallback 返回 `Pair(values.min(), values.max())`（IQR 无意义）；`values` 为空时返回 `Pair(0.0, 1.0)` 哨兵。
- 否则排序 values，Q1 = 第 25 百分位（index = size/4），Q3 = 第 75 百分位（index = 3*size/4），IQR = Q3 - Q1，lower = Q1 − 1.5·IQR，upper = Q3 + 1.5·IQR。
- 返回 `Pair(lower.coerceAtLeast(rawMin), upper.coerceAtMost(rawMax))`（与真实数据取交，不超出 raw 范围）。
- 函数为纯函数，无 Android 依赖，可在 JVM 单测运行。

#### Scenario: 正常数据无离群点 — 返回接近 raw min/max 的范围

```
values = [80.0, 90.0, 100.0, 110.0, 120.0]（5 点正态样本）
sorted: [80, 90, 100, 110, 120], Q1=90, Q3=110, IQR=20
lower = 90 - 30 = 60 → coerceAtLeast(80) = 80
upper = 110 + 30 = 140 → coerceAtMost(120) = 120
期望：Pair(80.0, 120.0)  // 等于 raw min/max
```

#### Scenario: 含单根尖刺离群点 — 上界不被撑满

```
values = [80.0, 85.0, 90.0, 92.0, 88.0, 86.0, 300.0]（7 点，最后一个 300 是尖刺）
sorted: [80, 85, 86, 88, 90, 92, 300]
Q1 = sorted[1] = 85, Q3 = sorted[5] = 92, IQR = 7
lower = 85 - 10.5 = 74.5 → coerceAtLeast(80) = 80
upper = 92 + 10.5 = 102.5 → coerceAtMost(300) = 102.5
期望：lower ≈ 80, upper < 200（上界不被 300 撑满）
反例：raw max 直接用时 upper = 300，正常数据被压扁（此为测试 MUST 验证的反例）
```

#### Scenario: 数据点 < 4 fallback raw min/max

```
values = [50.0, 100.0, 80.0]（3 点）
期望：Pair(50.0, 100.0)  // fallback raw min/max，与 sorted raw 一致
```

#### Scenario: 全相同值

```
values = [100.0, 100.0, 100.0, 100.0, 100.0]（5 点全相同）
Q1=Q3=100, IQR=0, lower=100, upper=100
期望：Pair(100.0, 100.0)  // 等于 raw min/max
```

#### Scenario: 空 list fallback 哨兵

```
values = []
期望：Pair(0.0, 1.0)  // 哨兵，调用方 existing "empty → default" 路径接管
```

---

### Requirement: computeChartBounds 改用 robustRange

`computeChartBounds` SHALL 对 SPEED 和 ACCEL 两 axis 的 values 调用 `robustRange`，不再直接用 `values.min()/values.max()`。

#### Scenario: 含离群点速度数据 — Y 轴上界不被撑满

```
samples 含 99 个 120 km/h 样本 + 1 个 300 km/h 尖刺样本
期望：computeChartBounds(samples, SPEED).maxVal < 200.0
反例：raw max 直接用时 maxVal = 300 + padding，超过 200（测试验证此反例）
```

#### Scenario: 数据少于 4 点 — fallback raw 范围（保持现有行为）

```
samples = 3 个 [80, 100, 90] km/h
期望：maxVal 接近 100（raw max），不因 robustRange 引入异常
```

#### Scenario: empty samples — 返回默认 ChartBounds

```
samples = []
期望：ChartBounds(minVal=0.0, maxVal=1.0, lapDurationMs=1L)（现有行为不变）
```

---

### Requirement: computeMultiLapBounds 改用 robustRange

`computeMultiLapBounds` SHALL 对跨 series 收集的所有 speedKmh 调用 `robustRange`，不再直接用全局 min/max。

#### Scenario: 多圈叠加含跨 series 尖刺 — Y 轴上界不被撑满

```
3 个 series，各 50 sample，速度 80-130，但 series[0] 第一个 sample speedKmh = 400（尖刺）
期望：computeMultiLapBounds(...).speedMax < 250.0
反例：raw max 直接用时 speedMax = 400 + padding（测试验证）
```

#### Scenario: 正常多圈叠加 — Y 轴范围合理

```
3 个 series，速度各 [60, 140] 正常分布
期望：speedMin ≤ 60, speedMax ≥ 140（正常数据不被截断）
```

#### Scenario: 所有 series 空 — fallback 哨兵

```
series = []
期望：MultiLapBounds(maxElapsedMs=1L, speedMin=0.0, speedMax=1.0)
```

---

### Requirement: 超界点 clamp 绘制（SpeedTimeChart B 套）

`computeChartCoordinates` 计算出的 y 坐标 SHALL 在 canvas 边界内 clamp（`coerceIn(0f, canvasSize.height)`），超出 robust 范围的点贴边绘制，曲线不断线。

#### Scenario: 尖刺点 y 坐标 clamp 到顶边

```
含一个超出 robustRange 上界的点（速度超过 maxVal），其 raw y < 0（在 canvas 顶部之上）
期望：clamp 后 y = 0.0（贴顶边），曲线与相邻点仍连续（path 不断线）
```

#### Scenario: 正常点 y 坐标不受 clamp 影响

```
正常点速度在 robustRange 内，raw y 在 [0, height] 内
期望：clamp 无效果，y = rawY（不变）
```

#### Scenario: 反例 — raw min/max 时无超界点需 clamp

```
使用 raw max 作为 maxVal，所有点的 raw speed ≤ raw max → 无点超界
robustRange 排除离群点后正常点速度均在 [lower, upper] 内，尖刺点 clamp 到顶
验证 robust 模式下尖刺点 y = 0（clamp），raw 模式下尖刺点 y 在 [0, height]（不 clamp = 撑满 Y 轴）
```

---

### Requirement: SpeedChart（A 套）maxSpeed 改用 robustRange 上界

`SpeedChart` 绘制时 maxSpeed SHALL 为 `robustRange(speeds).second` 而非 `speeds.max()`。底部仍为 0（物理非负）。

#### Scenario: 含尖刺 — maxSpeed 不被撑满

```
90 个 dataPoints speed = 100.0, 10 个 speed = 500.0（尖刺）
期望：maxSpeed < 300（不被 500 撑满）
```

#### Scenario: 数据点少于 4 — fallback raw max（保持现有行为）

```
dataPoints = 3 点 [80, 100, 90]
期望：maxSpeed = 100.0（raw max，fallback 行为不变）
```

#### Scenario: 反例 — raw max 会被撑满而 robustRange 不会

```
同"含尖刺"场景，raw maxOf { it.speed } = 500.0 > 200（测试用硬断言验证反例）
```

---

### Requirement: GForceChart（A 套）fallback maxG 改用 robustRange abs-G 上界

`GForceChart` fallback（maxAcceleration == 0.0 时）SHALL 用 `robustRange(absG).second` 而非 `gForcePoints.maxOfOrNull { abs(it.second) }`。G 曲线保持 ±maxG 对称（maxG = robust abs-G 上界）。

#### Scenario: 含 G 尖刺 — maxG 不被撑满

```
gForcePoints 多数 abs(G) ≈ 0.3，但含 1 个 abs(G) = 5.0 尖刺
期望：fallback maxG < 2.0（不被 5.0 撑满）
```

#### Scenario: maxAcceleration > 0 时用传入值（现有行为不变）

```
maxAcceleration = 1.5
期望：maxG = 1.5（不走 robustRange 分支）
```

#### Scenario: 反例 — raw maxOfOrNull 时 maxG 被尖刺撑满

```
同"含 G 尖刺"场景，raw maxOfOrNull { abs } = 5.0 > 2.0（测试验证反例，robust < 2.0）
```

