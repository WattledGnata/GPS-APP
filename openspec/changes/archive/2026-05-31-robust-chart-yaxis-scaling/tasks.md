# robust-chart-yaxis-scaling — Tasks

## Tasks

- [ ] **Task 1：实现 `robustRange` 纯函数 + 单测**
  - 文件：`feature/test/src/main/java/com/blazepush/feature/test/ui/components/SpeedTimeChart.kt`
  - 在 `computeChartBounds` 之前（约 L42 之前）插入 `internal fun robustRange(values: List<Double>): Pair<Double, Double>`
  - 算法：sorted → Q1 = sorted[size/4], Q3 = sorted[3*size/4], IQR = Q3-Q1, lower/upper Tukey + coerceIn rawMin/rawMax；size < 4 fallback raw；size == 0 return (0.0, 1.0)
  - 单测文件：新建 `feature/test/src/test/java/com/blazepush/feature/test/ui/components/RobustRangeTest.kt`
  - done condition：`robustRange` grep 存在于 `SpeedTimeChart.kt`，单测 5 case 全 pass

- [ ] **Task 2：修改 `computeChartBounds` 用 robustRange**
  - 文件：`SpeedTimeChart.kt` 约 L48-49
  - 改前：`val minVal = values.min(); val maxVal = values.max()`
  - 改后：`val (minVal, maxVal) = robustRange(values)`
  - done condition：grep `robustRange` 在 `computeChartBounds` 函数体内存在；编译通过

- [ ] **Task 3：修改 `computeChartCoordinates` 添加 y clamp**
  - 文件：`SpeedTimeChart.kt` 约 L74（y 坐标计算行）
  - 在 y 赋值后加 `.coerceIn(0f, canvasSize.height)`
  - done condition：grep `coerceIn` 在 `computeChartCoordinates` 函数体内存在

- [ ] **Task 4：修改 `computeMultiLapBounds` 用 robustRange**
  - 文件：`feature/test/src/main/java/com/blazepush/feature/test/ui/components/MultiLapSpeedChart.kt`
  - 约 L70-73，改 `val (minVal, maxVal) = robustRange(speeds)` 替换 `speeds.min()/speeds.max()`
  - done condition：grep `robustRange` 在 `computeMultiLapBounds` 函数体内存在；编译通过

- [ ] **Task 5：修改 `SpeedChart.kt` SpeedChart maxSpeed**
  - 文件：`feature/test/src/main/java/com/blazepush/feature/test/ui/components/SpeedChart.kt` 约 L50
  - 改前：`val maxSpeed = dataPoints.maxOf { it.speed }.toFloat()`
  - 改后：`val speeds = dataPoints.map { it.speed }; val maxSpeed = robustRange(speeds).second.toFloat()`
  - done condition：grep `robustRange` 在 `SpeedChart.kt` 存在；编译通过

- [ ] **Task 6：修改 `GForceChart` fallback maxG**
  - 文件：`SpeedChart.kt` 约 L204-208
  - fallback 分支改 `robustRange(gForcePoints.map { abs(it.second) }).second.coerceAtLeast(0.5).toFloat()`
  - done condition：grep `robustRange` 在 `GForceChart` 函数体内存在

- [ ] **Task 7：扩展现有测试覆盖 robustRange 场景**
  - `SpeedTimeChartContractTest.kt`：加 "含离群点时 computeChartBounds maxVal 不被撑满" + "数据少 fallback"
  - `MultiLapSpeedChartTest.kt`：加 "跨 series 含离群点时 speedMax 不被撑满"
  - `AccelTimeChartContractTest.kt`：加 "含离群点时 accel bounds 不被撑满"
  - done condition：所有新测试 pass

## §10 Follow-up Backlog

（暂无）
