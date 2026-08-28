# robust-chart-yaxis-scaling — Design

## Decision 1：IQR Tukey vs 百分位 P2/P98

**Alternatives：**
- **A：IQR Tukey 方法**（Q1−1.5·IQR, Q3+1.5·IQR）：统计经典离群判断，无参数（1.5 倍数是 Tukey 1977 固定值），对正态分布离群排除约 0.7%，对 GPS 采样的单根尖刺极其有效；缺点：若数据高度偏斜（如加速测试起步阶段速度从 0 快速攀升），Q1 附近点密集可能把上界算得偏保守，但 `coerceIn(rawMin, rawMax)` 取交避免了上界超出真实数据。
- **B：百分位 P2/P98**（sort + index）：更直观，对偏斜数据更友好；缺点：需要排序 O(n log n)，对少量数据（4-10 点）行为不稳定，且 2%/98% 的参数选取需要调参。
- **C：均值±3σ 方法**：假定正态分布，GPS 速度数据非正态（加速测试有强烈偏斜），不适合。

**选择 A（IQR Tukey）**，理由：
1. 零参数（1.5 是固定值，不需要调参），CC 不需要和 user 对齐参数。
2. O(n log n) 排序一次，纯函数无副作用，适合 Compose remember 块。
3. 与实际数据 min/max 取交（`clamp to raw range`）确保 Y 轴不超出真实数据，避免"看起来有数据但 Y 轴放大超出范围"的视觉误导。
4. 数据点 < 4 时 fallback raw min/max，避免 IQR = 0 的边界问题。

## Decision 2：超界点绘制策略 —— clamp vs drop

**Alternatives：**
- **A：clamp 到边界绘制**：超出 [lower, upper] 的点 clamp 后保持曲线连续，尖刺变为贴边水平段，视觉上明显是"超出显示范围"信号。已有先例：`GForceChart` 的 `coerceIn(-3.0, 3.0)` 用此策略。
- **B：drop 超界点**：相邻点直连产生 V 字断点（已有 spec.md 记录此问题），`GForceChart spec.md` 明确写"MUST clip 而非 drop"。

**选择 A（clamp）**，与现有 spec.md 约束一致。

**实现差异：**
- **B 套（SpeedTimeChart / MultiLapSpeedChart）**：`computeChartCoordinates` 中 y 坐标映射公式已通过 `(rawVal - minVal) / valRange` 隐式处理——只需把 `ChartBounds.minVal/maxVal` 改为 robust 范围，超界点的 y 坐标会自然超出 [0, canvasHeight]，clamp 需显式在坐标计算后 `coerceIn(0f, canvasSize.height)`。
- **A 套（SpeedChart）**：绘制 `y = padding + chartHeight - (speed / maxSpeed) * chartHeight`，超出 maxSpeed 的点 y < padding（出 canvas 顶），clamp 需 coerceIn padding 区间；GForceChart 已有 `coerceIn(-3.0, 3.0)` 但是 G 值的 clip，此处改 maxG fallback 后无需额外 clamp（绘制逻辑已 clamp 到 [−maxG, +maxG]）。

## Decision 3：G 曲线对称 vs 速度非对称

**G 曲线**：以 `centerY` 居中，范围 ±maxG，`robustRange` 结果取 `max(|lower|, |upper|)` 作为对称 maxG。这确保 G=0 线始终在图中心。

**速度曲线**：底部固定 0（物理意义：速度不为负），只改上界用 robust 上界。非对称（0 ~ robust 上界）。

## Decision 4：robustRange 函数位置

放在 `SpeedTimeChart.kt`（B 套函数的共享位置，B 套两个 chart 都在 `feature/test/ui/components/` 下），`internal` 可见性，A 套 `SpeedChart.kt` 在同一 package 内可直接调用。

## Risks

- **Risk 1：IQR 对单调递增数据（加速测试速度从 0 到 100）过于保守**：Q3-Q1 = IQR 很大（约 25 km/h for uniform 0-100），上界 = Q3 + 1.5·IQR 超出 raw max → `coerceIn(rawMin, rawMax)` 取交退化 raw max，无损正确性。Mitigation：取交保底。
- **Risk 2：全相同值 IQR = 0 → 下界等于上界**：此时 `max(range, 1.0)` padding 机制仍需在 robust range 之上添加 5% padding，或 fallback raw range。Mitigation：`robustRange` 返回 [v, v] 时调用方 padding 处理一致（与当前 raw min/max 处理路径相同）。
- **Risk 3：< 4 点 fallback raw min/max 时 IQR 未运行**：此 fallback 行为等价于修改前，无回归。Mitigation：单测覆盖。
