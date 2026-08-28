# robust-chart-yaxis-scaling — Proposal

## Why

所有数据曲线（速度曲线、加速度曲线）Y 轴范围直接取 raw `values.min()/max()`。一个单根毛刺离群点即可把 Y 轴撑满，正常数据被压缩到图高的一小段，曲线信息密度骤降，用户无法判读真实数据趋势。

当前 baseline 问题分布：
- **B 套 `SpeedTimeChart.computeChartBounds`**（约 L48-49）：速度轴 + 加速度轴均用 `values.min()/values.max()`，任意尖刺撑满 Y 轴。
- **B 套 `MultiLapSpeedChart.computeMultiLapBounds`**（约 L71-72）：跨 series 全局 speed min/max，同一问题。
- **A 套 `SpeedChart.kt` SpeedChart**（约 L50）：`maxSpeed = dataPoints.maxOf { it.speed }`，raw max 直接作为 Y 轴上界。
- **A 套 `SpeedChart.kt` GForceChart**（约 L207）：fallback `gForcePoints.maxOfOrNull { abs(it.second) }`，raw max of abs G，尖刺直接撑满对称 Y 轴。

用户场景：GPS 测试 25Hz 采样，偶发 HDOP 差时的一帧高速尖刺（如 300 km/h vs 真实 130 km/h）会把速度曲线上界拉到 300，正常 130 km/h 巡航段被压到图高 43%，趋势完全不可读。

## What Changes

1. 引入纯函数 `robustRange(values: List<Double>): Pair<Double, Double>`：基于 IQR Tukey 上下界算法（Q1−1.5·IQR, Q3+1.5·IQR），与实际数据 min/max 取交（不超出真实数据范围），数据点 < 4 时 fallback 到 raw min/max（IQR 无意义）。
2. `SpeedTimeChart.computeChartBounds`（B 套速度+加速度）改用 `robustRange` 算 Y 轴范围，超界点绘制时 clamp 到边界（曲线连续）。
3. `MultiLapSpeedChart.computeMultiLapBounds`（M3 多圈对比）跨 series 收集所有速度值后调 `robustRange`。
4. `SpeedChart`（A 套速度曲线）`maxSpeed` 改用 `robustRange` 上界。
5. `GForceChart`（A 套 G 值曲线）fallback `maxG` 改用 `robustRange` abs-G 上界，保持 G=0 对称绘制。
6. 单测：`robustRange` 完整覆盖（正常 / 含离群 / 数据少 fallback / 全相同值 / 空），及各曲线"含离群点时 Y 轴范围不被撑满"+"超界点 clamp"+"数据少 fallback" scenario（含反例）。
