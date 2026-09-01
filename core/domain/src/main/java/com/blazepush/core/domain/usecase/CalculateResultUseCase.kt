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

        val selection = PerformanceResultWindowExtractor.extract(dataPoints, session.template)
            ?: return emptyResult(session, dataFilePath)
        val correctedPoints = selection.dataPoints

        // 成绩摘要和 UI 曲线严格消费同一个最终窗口。精确边界可能使采样不再完全等间隔，
        // AccelerationSmoother 会按其既有契约自动退化到非均匀采样安全路径。
        val accelerationsMs2 = calculateAccelerations(correctedPoints)
        // avgAcceleration 维持 V1 兼容（abs 后均值，恒 ≥ 0）：spec 未规定 avg 拆分；
        // 不维持 V1 会让 brake 测试 UI "AVG G" 显示负数（用户困惑回归）。
        val avgAcceleration = if (accelerationsMs2.isNotEmpty()) {
            accelerationsMs2.map { kotlin.math.abs(it) }.average() / GRAVITY_MS2
        } else 0.0
        val maxAcceleration = accelerationsMs2.filter { it > 0 }.maxOrNull()?.div(GRAVITY_MS2) ?: 0.0
        val maxDeceleration = accelerationsMs2.filter { it < 0 }.minOrNull()?.let { -it / GRAVITY_MS2 } ?: 0.0

        // 2. 计算总时间
        val totalTime = correctedPoints.last().elapsedTime - correctedPoints.first().elapsedTime

        // 3. 计算总距离
        val totalDistance = calculateTotalDistance(correctedPoints)

        // 4. 计算分段数据
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
            dataFilePath = dataFilePath,
            window = selection.window,
            deviceSnapshot = session.deviceSnapshot,
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
        dataFilePath = dataFilePath,
        deviceSnapshot = session.deviceSnapshot,
    )
}
