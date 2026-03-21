# GPS 位置-速度一致性检验实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 GpsDataFilter 中实现位置-速度一致性检验，包括三个独立窗口的中位数滤波（speed/lat/lon/bearing）和基于原始数据的一致性校验

**Architecture:**
- GpsDataFilter.process() 保持原有流程，在物理约束层后新增第二层位置-速度一致性检验
- 滤波后的 lat/lon/bearing 均来自各自的中位数窗口，输出可直接用于赛道轨迹
- 一致性检验在原始数据层执行，结果影响 consistencyFactor，不改变滤波输出

**Tech Stack:** Kotlin / JUnit / MockK

---

## 文件结构

| 文件 | 操作 | 职责 |
|------|------|------|
| `GpsDataFilter.kt` | 修改 | 增加三个窗口 + 一致性检验 + consistencyFactor |
| `GpsDataFilterTest.kt` | 修改 | 新增位置滤波和一致性检验测试用例 |

---

## Task 1: 修改 FilteredGpsData 数据结构

**文件:** `app/src/main/java/com/race/gps/domain/usecase/GpsDataFilter.kt`

- [ ] **Step 1: 在 FilteredGpsData data class 末尾添加两个新字段**

```kotlin
data class FilteredGpsData(
    // ... 现有字段保持不变 ...
    val raw: GpsData,                // 已有
    val consistencyFactor: Double = 1.0,  // 新增：位置-速度一致性因子
    val isPositionAnomaly: Boolean = false  // 新增：位置异常标记
)
```

- [ ] **Step 2: 提交**

```bash
git add app/src/main/java/com/race/gps/domain/usecase/GpsDataFilter.kt
git commit -m "feat: add consistencyFactor and isPositionAnomaly fields to FilteredGpsData"
```

---

## Task 2: 增加位置滤波窗口

**文件:** `app/src/main/java/com/race/gps/domain/usecase/GpsDataFilter.kt`

- [ ] **Step 1: 在 GpsDataFilter 类中新增三个滚动窗口和 previousPosition**

```kotlin
class GpsDataFilter(
    private val windowSize: Int = 9,
    private val maxAcceleration: Double = 15.0,
    private val maxDeceleration: Double = 20.0
) {
    private val speedWindow = mutableListOf<Double>()
    private val latWindow = mutableListOf<Double>()       // 新增
    private val lonWindow = mutableListOf<Double>()       // 新增
    private val bearingWindow = mutableListOf<Double>()   // 新增

    private var previousRaw: GpsData? = null
    private var previousPosition: Pair<Double, Double>? = null  // lat, lon 新增
```

- [ ] **Step 2: 在 process() 方法中，在 speedWindow.add() 之后添加三个窗口的填充逻辑**

```kotlin
        speedWindow.add(raw.speed)
        latWindow.add(raw.latitude)      // 新增
        lonWindow.add(raw.longitude)     // 新增
        bearingWindow.add(raw.bearing)   // 新增
        if (speedWindow.size > windowSize) {
            speedWindow.removeAt(0)
            latWindow.removeAt(0)         // 新增
            lonWindow.removeAt(0)        // 新增
            bearingWindow.removeAt(0)    // 新增
        }
```

- [ ] **Step 3: 在计算 outputSpeed 的逻辑后，添加 lat/lon/bearing 的滤波输出计算**

```kotlin
        // 4. 计算输出值
        val outputSpeed = when {
            isAnomaly && speedWindow.size >= 3 -> speedWindow.median()
            speedWindow.size >= 3 -> speedWindow.median()
            else -> raw.speed
        }

        // 新增：位置和航向的滤波输出
        val outputLat = if (latWindow.size >= 3) latWindow.median() else raw.latitude
        val outputLon = if (lonWindow.size >= 3) lonWindow.median() else raw.longitude
        val outputBearing = if (bearingWindow.size >= 3) bearingWindow.median() else raw.bearing
```

- [ ] **Step 4: 在 reset() 方法中清空新窗口**

```kotlin
    fun reset() {
        speedWindow.clear()
        latWindow.clear()        // 新增
        lonWindow.clear()        // 新增
        bearingWindow.clear()    // 新增
        previousRaw = null
        previousPosition = null  // 新增
    }
```

- [ ] **Step 5: 在 return FilteredGpsData 处使用滤波后的值**

```kotlin
        return FilteredGpsData(
            speed = outputSpeed,
            latitude = outputLat,       // 改为滤波后值
            longitude = outputLon,       // 改为滤波后值
            altitude = raw.altitude,
            bearing = outputBearing,     // 改为滤波后值
            // ... 其他字段保持 ...
            consistencyFactor = 1.0,    // 占位，下一步填充
            isPositionAnomaly = false    // 占位，下一步填充
        )
```

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/race/gps/domain/usecase/GpsDataFilter.kt
git commit -m "feat: add lat/lon/bearing median filter windows to GpsDataFilter"
```

---

## Task 3: 实现位置-速度一致性检验

**文件:** `app/src/main/java/com/race/gps/domain/usecase/GpsDataFilter.kt`

- [ ] **Step 1: 在 process() 方法的物理约束检查后（第31行附近），添加一致性检验调用**

在 `val isAnomaly = isPhysicalConstraintViolation(raw)` 之后添加：

```kotlin
        // 2.5. 位置-速度一致性检验（基于原始数据）
        val (consistencyFactor, isPositionAnomaly) = checkPositionVelocityConsistency(raw)
```

- [ ] **Step 2: 在 process() 方法末尾 return 之前，将一致性结果填入返回值**

在 `return FilteredGpsData(...)` 处，将 `consistencyFactor = 1.0` 和 `isPositionAnomaly = false` 替换为：

```kotlin
            consistencyFactor = consistencyFactor,
            isPositionAnomaly = isPositionAnomaly
```

- [ ] **Step 3: 在私有方法区域添加一致性检验核心实现**

在 `// ==================== 私有方法 ====================` 区域末尾添加：

```kotlin
    /**
     * 位置-速度一致性检验
     * 基于原始数据计算 v_implied = Δd / Δt，与 GPS 报告速度对比
     */
    private fun checkPositionVelocityConsistency(current: GpsData): Pair<Double, Boolean> {
        val prevPos = previousPosition ?: return 1.0 to false
        val prevData = previousRaw ?: return 1.0 to false

        val dt = (current.timestamp - prevData.timestamp) / 1000.0
        if (dt <= 0 || dt > 1.0) return 1.0 to false

        // 计算位移 Δd（简化平面近似）
        val latRad = Math.toRadians(current.latitude)
        val deltaLatM = kotlin.math.abs(current.latitude - prevPos.first) * 111320.0
        val deltaLonM = kotlin.math.abs(current.longitude - prevPos.second) * 111320.0 * kotlin.math.cos(latRad)
        val distanceM = kotlin.math.sqrt(deltaLatM * deltaLatM + deltaLonM * deltaLonM)

        // Δd 过小时跳过一致性检查（0.01m 远小于 GPS 噪声，规避被淹没）
        if (distanceM < 0.01) return 1.0 to false

        // 计算 v_implied
        val vImpliedKmh = (distanceM / dt) * 3.6
        val speedDiff = kotlin.math.abs(current.speed - vImpliedKmh)

        // 航向变化降权（>30°/s 时降权）
        val bearingDelta = kotlin.math.abs(current.bearing - prevData.bearing)
        val normalizedBearingDelta = if (bearingDelta > 180) 360 - bearingDelta else bearingDelta
        val bearingPenalty = if (normalizedBearingDelta > 30.0) 0.8 else 1.0

        // HDOP 降权
        val hdopPenalty = if (current.hdop > 3.0) 0.5 else 1.0

        // 确定容差
        val tolerance = getConsistencyTolerance(current.speed)

        // 一致性因子
        val ratio = speedDiff / tolerance
        val baseFactor = when {
            ratio <= 1.0 -> 1.0
            ratio <= 2.0 -> 0.8
            ratio <= 3.0 -> 0.6
            else -> 0.3
        }

        val consistencyFactor = (baseFactor * bearingPenalty * hdopPenalty).coerceIn(0.0, 1.0)
        val isPositionAnomaly = ratio > 3.0

        return consistencyFactor to isPositionAnomaly
    }

    /**
     * 根据速度确定一致性容差
     */
    private fun getConsistencyTolerance(speed: Double): Double {
        return when {
            speed < 5.0 -> 3.0
            speed < 60.0 -> 5.0
            else -> 10.0
        }
    }
```

- [ ] **Step 4: 在 update previousRaw 后，同步更新 previousPosition**

将 `previousRaw = raw` 改为：

```kotlin
        // 6. 更新状态
        previousRaw = raw
        previousPosition = raw.latitude to raw.longitude
```

- [ ] **Step 5: 更新置信度计算，引入 consistencyFactor**

将 `calculateConfidence` 方法签名和实现改为：

```kotlin
    private fun calculateConfidence(isAnomaly: Boolean, hdop: Double, consistencyFactor: Double): Double {
        var confidence = if (isAnomaly) 0.5 else 1.0

        // HDOP 因子
        val hdopFactor = when {
            hdop < 1.0 -> 1.0
            hdop < 2.0 -> 0.9
            hdop < 5.0 -> 0.6
            else -> 0.3
        }
        confidence *= hdopFactor
        confidence *= consistencyFactor  // 新增

        return confidence.coerceIn(0.0, 1.0)
    }
```

- [ ] **Step 6: 更新 process() 中对 calculateConfidence 的调用**

将 `val confidence = calculateConfidence(isAnomaly, raw.hdop)` 改为：

```kotlin
        val confidence = calculateConfidence(isAnomaly, raw.hdop, consistencyFactor)
```

- [ ] **Step 7: 提交**

```bash
git add app/src/main/java/com/race/gps/domain/usecase/GpsDataFilter.kt
git commit -m "feat: implement position-velocity consistency check with multi-level tolerance"
```

---

## Task 4: 编写新测试用例

**文件:** `app/src/test/java/com/race/gps/domain/usecase/GpsDataFilterTest.kt`

- [ ] **Step 1: 在辅助函数中添加带位置变化的序列生成函数**

在 `createAccelerationSequence` 后添加：

```kotlin
    /**
     * 创建带位置变化的GPS序列
     * 模拟车辆向北（纬度增加）行驶，位置变化与速度一致
     */
    private fun createMovingSequence(
        startSpeed: Double,
        endSpeed: Double,
        points: Int,
        intervalMs: Long = 40L,
        startTimestamp: Long = System.currentTimeMillis(),
        startLat: Double = 60.1725,
        startLon: Double = 24.9375,
        bearing: Double = 0.0  // 正北方向
    ): List<GpsData> {
        val speedStep = (endSpeed - startSpeed) / (points - 1)
        return (0 until points).map { i ->
            val speed = startSpeed + speedStep * i
            // 速度转 m/s，再转度（简化：每m/s约1度/111320）
            val latIncrement = (speed / 3.6) * intervalMs / 111320.0 / 1000.0
            createGpsData(
                timestamp = startTimestamp + i * intervalMs,
                speed = speed,
                latitude = startLat + latIncrement * i,
                longitude = startLon,
                bearing = bearing,
                hdop = 1.0
            )
        }
    }
```

- [ ] **Step 2: 添加正常位置变化一致性检验测试**

在现有测试末尾添加：

```kotlin
    /**
     * GF17: 正常位置变化应通过一致性检验
     *
     * 场景：速度与位置变化一致
     * 输入：位置平滑变化，速度与位移一致
     * 预期：consistencyFactor = 1.0, isPositionAnomaly = false
     */
    @Test
    fun GF17_normalPositionConsistent_fullFactor() {
        // Given: 正常移动序列
        val movingData = createMovingSequence(
            startSpeed = 30.0,
            endSpeed = 60.0,
            points = 9
        )

        // When
        val results = movingData.map { filter.process(it) }

        // Then: 前1-2个点 previousPosition==null 跳过检查（consistencyFactor=1.0默认值）
        // 从第2个点开始应有完整一致性检验
        results.drop(2).forEachIndexed { idx, result ->
            val index = idx + 2
            assertEquals(
                "第${index}个点一致性因子应为1.0",
                1.0,
                result.consistencyFactor,
                0.01
            )
            assertFalse(
                "第${index}个点不应为位置异常",
                result.isPositionAnomaly
            )
        }
    }
```

- [ ] **Step 2b: 添加高速一致性检验测试**

在 GF17 后添加：

```kotlin
    /**
     * GF17b: 高速行驶（120 km/h）应通过一致性检验，容差 10 km/h
     *
     * 场景：高速时 GPS 精度通常更好，容差放宽至 10 km/h
     * 输入：120 km/h 稳定行驶，位置平滑变化
     * 预期：consistencyFactor = 1.0
     */
    @Test
    fun GF17b_highSpeedConsistent_fullFactor() {
        // Given: 高速稳定行驶序列
        val movingData = createMovingSequence(
            startSpeed = 120.0,
            endSpeed = 120.0,  // 稳定 120 km/h
            points = 12
        )

        // When
        val results = movingData.map { filter.process(it) }

        // Then: 从第2个点开始一致性因子应为 1.0
        results.drop(2).forEachIndexed { idx, result ->
            val index = idx + 2
            assertEquals(
                "高速第${index}个点一致性因子应为1.0",
                1.0,
                result.consistencyFactor,
                0.01
            )
            assertFalse(
                "高速第${index}个点不应为位置异常",
                result.isPositionAnomaly
            )
        }
    }
```

- [ ] **Step 3: 添加位置跳变检测测试**（GF18）

```kotlin
    /**
     * GF18: 位置跳变（瞬间位移100m）应被标记为位置异常
     *
     * 场景：GPS 位置瞬间跳变（不可能的物理位移）
     * 输入：第5个点纬度瞬间跳变 +0.001度（约110m）
     * 预期：isPositionAnomaly = true, consistencyFactor 降低
     */
    @Test
    fun GF18_positionJump_detectedAsAnomaly() {
        // Given: 正常移动序列，第5个点位置跳变
        val normalData = createMovingSequence(
            startSpeed = 30.0,
            endSpeed = 60.0,
            points = 9
        )

        val anomalousData = normalData.mapIndexed { index, data ->
            if (index == 4) {
                data.copy(latitude = data.latitude + 0.001) // 约+110m
            } else {
                data
            }
        }

        // When
        val results = anomalousData.map { filter.process(it) }

        // Then: 第5个点应标记为位置异常
        assertTrue(
            "第5个点应为位置异常",
            results[4].isPositionAnomaly
        )
        assertTrue(
            "第5个点一致性因子应降低",
            results[4].consistencyFactor < 1.0
        )
    }
```

- [ ] **Step 4: 添加低速静止跳过一致性检查测试**（GF19）

```kotlin
    /**
     * GF19: 静止漂移（Δd < 0.5m）应跳过一致性检查
     *
     * 场景：低速时位置变化极小，不进行一致性判断
     * 输入：低速（speed < 5 km/h）且位置几乎不变
     * 预期：consistencyFactor = 1.0（跳过检查）
     */
    @Test
    fun GF19_lowSpeedSkipsConsistencyCheck_fullFactor() {
        // Given: 低速静止序列
        val baseTimestamp = System.currentTimeMillis()
        val lowSpeedData = (0 until 9).map { i ->
            createGpsData(
                timestamp = baseTimestamp + i * 40L,
                speed = (i % 3) * 0.5,  // 0, 0.5, 1.0, 0, ...
                latitude = 60.1725,      // 静止
                longitude = 24.9375,     // 静止
                hdop = 1.0
            )
        }

        // When
        val results = lowSpeedData.map { filter.process(it) }

        // Then: 一致性因子应为1.0（Δd过小跳过检查）
        results.drop(1).forEachIndexed { index, result ->
            assertEquals(
                "低速静止第${index}个点一致性因子应为1.0",
                1.0,
                result.consistencyFactor,
                0.01
            )
        }
    }
```

- [ ] **Step 5: 添加航向剧变降权测试**（GF20）

```kotlin
    /**
     * GF20: 航向剧变（>30°/s）应降权 consistencyFactor
     *
     * 场景：转弯时直线位移与速度不匹配
     * 输入：航向在40ms内变化90度
     * 预期：consistencyFactor × 0.8 降权
     */
    @Test
    fun GF20_sharpTurn_reducesConsistencyFactor() {
        // Given: 弯道场景，航向剧变
        val baseTimestamp = System.currentTimeMillis()
        val turnData = (0 until 9).map { i ->
            val bearing = if (i < 5) 0.0 else 90.0  // 第5个点突然转弯
            createGpsData(
                timestamp = baseTimestamp + i * 40L,
                speed = 30.0,
                latitude = 60.1725 + i * 0.0001,
                longitude = 24.9375,
                bearing = bearing,
                hdop = 1.0
            )
        }

        // When
        val results = turnData.map { filter.process(it) }

        // Then: 航向变化点应有降权的 consistencyFactor
        // bearing 从 0° 到 90°，变化 90° > 30°，应 ×0.8
        assertTrue(
            "转弯点一致性因子应降低",
            results[5].consistencyFactor < 0.9
        )
    }
```

- [ ] **Step 6: 添加信号丢失后重置测试**（GF21）

```kotlin
    /**
     * GF21: GPS 信号丢失（>200ms）后重置一致性检验
     *
     * 场景：GPS 信号中断后恢复
     * 输入：第5个点与第4个点间隔 300ms
     * 预期：第5个点不触发位置异常（dt过大跳过检查）
     */
    @Test
    fun GF21_signalLoss_skipsConsistencyCheck() {
        // Given: 正常序列，但第5个点间隔300ms
        val normalData = createMovingSequence(
            startSpeed = 30.0,
            endSpeed = 60.0,
            points = 5
        )

        val gapData = normalData.mapIndexed { index, data ->
            if (index == 4) {
                // 300ms 间隔（原本40ms）
                data.copy(timestamp = data.timestamp + 260)
            } else {
                data
            }
        }

        // When
        val results = gapData.map { filter.process(it) }

        // Then: 大间隔点不应为位置异常（跳过检查）
        assertFalse(
            "大间隔点不应为位置异常",
            results[4].isPositionAnomaly
        )
        assertEquals(
            "大间隔点一致性因子应为1.0",
            1.0,
            results[4].consistencyFactor,
            0.01
        )
    }
```

- [ ] **Step 7: 添加滤波后位置与原始位置不同的测试**（GF22）

```kotlin
    /**
     * GF22: 中位数滤波后位置应比原始位置更平滑
     *
     * 场景：位置有小幅抖动时，中位数滤波应平滑
     * 输入：纬度有小抖动（±0.00001度）的序列
     * 预期：滤波后纬度变化幅度小于原始
     */
    @Test
    fun GF22_positionMedianFilter_smoothsOutput() {
        // Given: 位置有小幅随机抖动
        val baseTimestamp = System.currentTimeMillis()
        val jitterData = (0 until 9).map { i ->
            createGpsData(
                timestamp = baseTimestamp + i * 40L,
                speed = 30.0,
                latitude = 60.1725 + (i % 3 - 1) * 0.00001,  // ±0.00001度抖动
                longitude = 24.9375,
                bearing = 0.0,
                hdop = 1.0
            )
        }

        // When
        val results = jitterData.map { filter.process(it) }

        // Then: 窗口填满后，滤波后纬度应趋于稳定
        if (results.size >= 9) {
            val filteredLats = results.map { it.latitude }
            val originalLats = jitterData.map { it.latitude }
            // 原始数据波动范围
            val originalRange = originalLats.maxOrNull()!! - originalLats.minOrNull()!!
            // 滤波后范围（取中间几个）
            val filteredRange = filteredLats.drop(5).take(3).let {
                it.maxOrNull()!! - it.minOrNull()!!
            }
            assertTrue(
                "滤波后位置变化应小于原始",
                filteredRange <= originalRange
            )
        }
    }
```

- [ ] **Step 8: 验证所有测试通过**

Run: `./gradlew test --tests GpsDataFilterTest`
Expected: 所有 GF01-GF22 通过

- [ ] **Step 9: 提交**

```bash
git add app/src/test/java/com/race/gps/domain/usecase/GpsDataFilterTest.kt
git commit -m "test: add position consistency and filtering tests (GF17-GF22)"
```

---

## Task 5: 回归验证

**文件:** 所有项目

- [ ] **Step 1: 运行全部测试，确保无回归**

Run: `./gradlew test`
Expected: 全部通过

- [ ] **Step 2: 提交回归验证**

```bash
git commit -m "test: run full regression suite - all tests pass"
```
