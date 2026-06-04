package com.blazepush.core.domain.usecase

import com.blazepush.core.domain.model.GpsDataPoint
import com.blazepush.core.domain.model.SpeedSegment
import com.blazepush.core.domain.model.TestResult
import com.blazepush.core.domain.model.TestSession
import com.blazepush.core.domain.model.TestTemplate
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import java.util.UUID

/**
 * 运动阈值（km/h）:计时窗口开/关段的 0 哨兵替换值（fix-accel-last-crossing design Decision 2）。
 * 1.0 = GPS 静止噪声带(0-2)上沿与 Dragy/RaceBox 类起步口径(≈0.5-1)的平衡,
 * 与触发判定 speed > 1.0(TestSessionViewModel)同阈。
 */
const val MOTION_THRESHOLD_KMH = 1.0

/**
 * 计算测试结果的UseCase
 * 从TestSession的原始数据点计算出最终结果
 */
class CalculateResultUseCase {

    operator fun invoke(session: TestSession, dataFilePath: String): TestResult {
        val dataPoints = session.dataPoints
        if (dataPoints.isEmpty()) {
            return emptyResult(session, dataFilePath)
        }

        // 1. 计算加速度（在 raw 等间距 dataPoints 上，**不能在 correctedPoints 上**：
        //    correctTimingPoints 注入的 preciseStart / preciseEnd 锚点与邻居 dt 不等于 40ms，
        //    会污染 5 点 SG 边界系数。spec.md "等间距假设 + 偏差 ≥ 20% 退化" 已锁定该约束。
        val accelerationsMs2 = calculateAccelerations(dataPoints)
        // avgAcceleration 维持 V1 兼容（abs 后均值，恒 ≥ 0）：spec 未规定 avg 拆分；
        // 不维持 V1 会让 brake 测试 UI "AVG G" 显示负数（用户困惑回归）。
        val avgAcceleration = if (accelerationsMs2.isNotEmpty()) {
            accelerationsMs2.map { kotlin.math.abs(it) }.average() / GRAVITY_MS2
        } else 0.0
        val maxAcceleration = accelerationsMs2.filter { it > 0 }.maxOrNull()?.div(GRAVITY_MS2) ?: 0.0
        val maxDeceleration = accelerationsMs2.filter { it < 0 }.minOrNull()?.let { -it / GRAVITY_MS2 } ?: 0.0

        // 2. 计时窗口提取（fix-accel-last-crossing：状态机取最后一个完整起步→首次过线段,
        //    用于 totalTime / totalDistance / segments，不喂 SG）。
        //    null = DNF（数据内无完整窗口,如未真正破百/未刹停）→ 计时类字段归零,
        //    SG 三项保留（raw 全程统计,对"没完成但跑了"仍有参考价值,spec R2 Scenario 3）。
        //    旧版此处 fallback 返回全程数据 → 53.32s 假成绩(2026-06-03 路测),已废除。
        val correctedPoints = correctTimingPoints(dataPoints, session.template)
            ?: return TestResult(
                id = UUID.randomUUID().toString(),
                sessionId = session.id,
                template = session.template,
                carModel = session.carModel,
                timestamp = session.startTime,
                totalTime = 0.0,
                totalDistance = 0.0,
                avgAcceleration = avgAcceleration,
                maxAcceleration = maxAcceleration,
                maxDeceleration = maxDeceleration,
                segments = emptyList(),
                dataPoints = emptyList(),
                dataFilePath = dataFilePath
            )

        // 3. 计算总时间
        val totalTime = correctedPoints.last().elapsedTime - correctedPoints.first().elapsedTime

        // 4. 计算总距离
        val totalDistance = calculateTotalDistance(correctedPoints)

        // 5. 计算分段数据
        val segments = calculateSegments(correctedPoints, session.template)

        return TestResult(
            id = UUID.randomUUID().toString(),
            sessionId = session.id,
            template = session.template,
            carModel = session.carModel,
            timestamp = session.startTime,
            totalTime = totalTime,
            totalDistance = totalDistance,
            avgAcceleration = avgAcceleration,
            maxAcceleration = maxAcceleration,
            maxDeceleration = maxDeceleration,
            segments = segments,
            dataPoints = correctedPoints,
            dataFilePath = dataFilePath
        )
    }


    /**
     * 计时窗口提取（fix-accel-last-crossing design Decision 1/1b/2/3）。
     *
     * 单次正向状态机收集所有完整（开段过线→首次关段过线）候选,取**最后一个**为成绩窗口:
     * - 加速 0→100:上行过运动阈值(1.0 km/h,起步)开段,首次上行过 endSpeed 关段。
     *   多次蠕动起步 → 每次上穿覆盖式重开锚点 → 只算最后一轮冲刺;
     *   回落再破百 → 首次触线停表(物理口径,回落段不计入);
     *   过线后挪车 → 挪车不过线不产新候选,成绩不丢。
     * - 刹车 100→0 镜像:下行过 startSpeed 开段,首次下行过停车阈值(1.0)关段。
     * - 模板 0 哨兵(startSpeed=0 / endSpeed=0)在算法内替换为运动阈值——0 作为过线条件
     *   对恒非负的速度恒假,旧版因此整体短路返回全程数据(53.32s 假成绩根因,line 86 旧址)。
     *
     * @return 裁剪并重置 elapsedTime 的窗口数据;null = 无完整窗口(DNF)
     */
    private fun correctTimingPoints(
        dataPoints: List<GpsDataPoint>,
        template: TestTemplate
    ): List<GpsDataPoint>? {
        if (dataPoints.size < 2) return null

        val window = when (template) {
            is TestTemplate.Acceleration0To100 -> findLastCompleteSegment(
                dataPoints = dataPoints,
                openThreshold = maxOf(template.startSpeed.toDouble(), MOTION_THRESHOLD_KMH),
                closeThreshold = template.endSpeed.toDouble(),
            )
            // 刹车不走镜像状态机（design Decision 1b 实施期修订 2026-06-04）:
            // 触发条件"减速低于 95"使数据从 ~95 开始,不存在 >100 帧,"下行过 100 开段"恒 DNF。
            // 窗口起点=数据首帧（触发时刻）,终点=首次下行过停车阈值;从 100 物理起算
            // 需 Ready 态预缓冲（backlog braking-prebuffer-from-ready）。
            is TestTemplate.Braking100To0 -> findBrakingWindow(
                dataPoints = dataPoints,
                stopThreshold = maxOf(template.endSpeed.toDouble(), MOTION_THRESHOLD_KMH),
            )
        } ?: return null

        val (preciseStart, preciseEnd) = window
        // 重新计算相对时间（窗口起点归零）
        val startTime = preciseStart.elapsedTime
        return buildList {
            add(preciseStart.copy(elapsedTime = 0.0))
            dataPoints
                .filter { it.elapsedTime > preciseStart.elapsedTime && it.elapsedTime < preciseEnd.elapsedTime }
                .forEach { add(it.copy(elapsedTime = it.elapsedTime - startTime)) }
            add(preciseEnd.copy(elapsedTime = preciseEnd.elapsedTime - startTime))
        }
    }

    /**
     * 加速状态机扫描:上行过开段阈值（覆盖式重开锚点 → 多次蠕动只留最后一轮）→ 首次上行过
     * 关段阈值收候选,返回最后一个完整候选（design Decision 1 三场景矩阵）。
     *
     * prev.speed == curr.speed 的相邻对跳过（插值 ratio 除零防御,spec R4）;
     * 关段时刻必须晚于开段时刻,否则丢弃该候选（畸形序列不产负值窗口,spec R4）。
     */
    private fun findLastCompleteSegment(
        dataPoints: List<GpsDataPoint>,
        openThreshold: Double,
        closeThreshold: Double,
    ): Pair<GpsDataPoint, GpsDataPoint>? {
        var lastComplete: Pair<GpsDataPoint, GpsDataPoint>? = null
        var pendingStart: GpsDataPoint? = null

        for (i in 1 until dataPoints.size) {
            val prev = dataPoints[i - 1]
            val curr = dataPoints[i]
            if (prev.speed == curr.speed) continue

            if (prev.speed < openThreshold && curr.speed >= openThreshold) {
                pendingStart = interpolateAt(prev, curr, openThreshold)
            }

            val start = pendingStart ?: continue
            if (prev.speed < closeThreshold && curr.speed >= closeThreshold) {
                val end = interpolateAt(prev, curr, closeThreshold)
                if (end.elapsedTime > start.elapsedTime) {
                    lastComplete = start to end
                }
                pendingStart = null // 候选关闭;再产候选需重新过开段线
            }
        }
        return lastComplete
    }

    /**
     * 刹车窗口（design Decision 1b 实施期修订）:起点=数据首帧（触发时刻）,
     * 终点=**首次**下行过 [stopThreshold] 的插值时刻（挪车段第二次下行不取,spec R3）;
     * 无下行过线 → null（未刹停 DNF）。
     */
    private fun findBrakingWindow(
        dataPoints: List<GpsDataPoint>,
        stopThreshold: Double,
    ): Pair<GpsDataPoint, GpsDataPoint>? {
        val start = dataPoints.first()
        for (i in 1 until dataPoints.size) {
            val prev = dataPoints[i - 1]
            val curr = dataPoints[i]
            if (prev.speed == curr.speed) continue
            if (prev.speed > stopThreshold && curr.speed <= stopThreshold) {
                val end = interpolateAt(prev, curr, stopThreshold)
                return if (end.elapsedTime > start.elapsedTime) start to end else null
            }
        }
        return null
    }

    /** 相邻对内线性插值过线点（调用方已保证 prev.speed != curr.speed）。 */
    private fun interpolateAt(prev: GpsDataPoint, curr: GpsDataPoint, targetSpeed: Double): GpsDataPoint {
        val ratio = (targetSpeed - prev.speed) / (curr.speed - prev.speed)
        return GpsDataPoint(
            elapsedTime = prev.elapsedTime + ratio * (curr.elapsedTime - prev.elapsedTime),
            speed = targetSpeed,
            latitude = prev.latitude + ratio * (curr.latitude - prev.latitude),
            longitude = prev.longitude + ratio * (curr.longitude - prev.longitude),
            altitude = prev.altitude
        )
    }

    private fun calculateTotalDistance(dataPoints: List<GpsDataPoint>): Double {
        var total = 0.0
        for (i in 1 until dataPoints.size) {
            val prev = dataPoints[i - 1]
            val curr = dataPoints[i]
            if (prev.latitude != 0.0 && curr.latitude != 0.0) {
                total += haversineDistance(
                    prev.latitude, prev.longitude,
                    curr.latitude, curr.longitude
                )
            }
        }
        return total
    }

    /**
     * 使用 Haversine 公式计算两点之间的距离（米）
     */
    private fun haversineDistance(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Double {
        val earthRadius = 6371000.0 // 地球半径（米）

        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)
        val deltaLat = Math.toRadians(lat2 - lat1)
        val deltaLon = Math.toRadians(lon2 - lon1)

        val a = sin(deltaLat / 2).pow(2) +
                cos(lat1Rad) * cos(lat2Rad) * sin(deltaLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))

        return earthRadius * c
    }

    /**
     * 加速度序列（单位 m/s²，正向加速 > 0、制动 < 0）。
     *
     * 走 [AccelerationSmoother] 5 点 Savitzky-Golay 中心差分；spec.md
     * `Requirement: 离线 G 值统计与 UI 曲线 MUST 共用 AccelerationSmoother` 锁定共用入口。
     *
     * 输入 dataPoints[i].speed 已经是 outputSpeed（GpsDataFilter 9 点 median 后），
     * binary 持久化路径同源（见 TestSessionViewModel 写入侧 filteredData.speed）。
     */
    private fun calculateAccelerations(dataPoints: List<GpsDataPoint>): List<Double> {
        val samples = dataPoints.map { p ->
            TimedSpeedSample(
                // Math.round 避免 IEEE 754 浮点截断（如 8.04 * 1000.0 = 8039.999... → toLong=8039 漂 -1ms）
                timestamp = Math.round(p.elapsedTime * 1000.0),
                speedKmh = p.speed,
            )
        }
        return AccelerationSmoother.compute(samples)
    }

    private fun calculateSegments(
        dataPoints: List<GpsDataPoint>,
        template: TestTemplate
    ): List<SpeedSegment> {
        return when (template) {
            is TestTemplate.Acceleration0To100 -> {
                // 0-10 到 80-90（9段）
                (0..80 step 10).map { startSpeed ->
                    calculateSegment(dataPoints, startSpeed, startSpeed + 10, ascending = true, isLastSegment = false)
                } + listOf(
                    // 90-100 段（最后一段，用最后一个数据点作为终点）
                    calculateSegment(dataPoints, 90, 100, ascending = true, isLastSegment = true)
                )
            }
            is TestTemplate.Braking100To0 -> {
                (100 downTo 10 step 10).mapIndexed { index, startSpeed ->
                    val isLast = index == 9  // 最后一个是 10-0
                    calculateSegment(dataPoints, startSpeed, startSpeed - 10, ascending = false, isLastSegment = isLast)
                }
            }
        }
    }

    private fun calculateSegment(
        dataPoints: List<GpsDataPoint>,
        fromSpeed: Int,
        toSpeed: Int,
        ascending: Boolean,
        isLastSegment: Boolean = false
    ): SpeedSegment {
        if (dataPoints.isEmpty()) {
            return SpeedSegment(fromSpeed, toSpeed, 0.0, 0.0)
        }

        val from = fromSpeed.toDouble()
        val to = toSpeed.toDouble()

        // 找到第一个速度达到 fromSpeed 的点作为起点
        val startIdx = dataPoints.indexOfFirst { point ->
            if (ascending) point.speed >= from else point.speed <= from
        }
        // 找到终点
        // 如果是最后一段（100km/h），用最后一个数据点作为终点
        // 否则找第一个达到目标速度的点
        val endIdx = if (isLastSegment) {
            dataPoints.lastIndex
        } else {
            dataPoints.indexOfFirst { point ->
                if (ascending) point.speed >= to else point.speed <= to
            }
        }

        if (startIdx < 0 || endIdx < 0 || startIdx >= endIdx) {
            return SpeedSegment(fromSpeed, toSpeed, 0.0, 0.0)
        }

        val startTime = dataPoints[startIdx].elapsedTime
        val endTime = dataPoints[endIdx].elapsedTime
        val time = endTime - startTime

        // 计算该区间的距离（使用GPS坐标累加）
        val segmentPoints = dataPoints.subList(startIdx, endIdx + 1)
        val distance = calculateTotalDistance(segmentPoints)

        return SpeedSegment(
            startSpeed = fromSpeed,
            endSpeed = toSpeed,
            time = time,
            distance = distance
        )
    }

    private fun emptyResult(session: TestSession, dataFilePath: String) = TestResult(
        id = UUID.randomUUID().toString(),
        sessionId = session.id,
        template = session.template,
        carModel = session.carModel,
        timestamp = session.startTime,
        totalTime = 0.0,
        totalDistance = 0.0,
        avgAcceleration = 0.0,
        maxAcceleration = 0.0,
        maxDeceleration = 0.0,
        segments = emptyList(),
        dataPoints = emptyList(),
        dataFilePath = dataFilePath
    )
}
