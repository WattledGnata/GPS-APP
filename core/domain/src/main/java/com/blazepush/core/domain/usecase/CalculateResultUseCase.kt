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

        // 2. 计时点修正：回溯历史数据找到精确的起始/结束点（用于 totalTime / totalDistance / segments，不喂 SG）
        val correctedPoints = correctTimingPoints(dataPoints, session.template)

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
     * 计时点修正：在数据明显越过阈值后，回溯历史数据找到精确的计时点
     * 使用线性插值在两个相邻数据点之间找到精确的速度阈值时刻
     */
    private fun correctTimingPoints(
        dataPoints: List<GpsDataPoint>,
        template: TestTemplate
    ): List<GpsDataPoint> {
        if (dataPoints.size < 2) return dataPoints

        val startSpeed = template.startSpeed.toDouble()
        val endSpeed = template.endSpeed.toDouble()

        // 找到精确的起始点（线性插值）
        val preciseStart = findPrecisePoint(dataPoints, startSpeed, isAcceleration = template is TestTemplate.Acceleration0To100)
        val preciseEnd = findPrecisePoint(dataPoints, endSpeed, isAcceleration = template is TestTemplate.Acceleration0To100)

        if (preciseStart == null || preciseEnd == null) return dataPoints

        // 重新计算相对时间
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
     * 线性插值找到精确的速度阈值时刻
     */
    private fun findPrecisePoint(
        dataPoints: List<GpsDataPoint>,
        targetSpeed: Double,
        isAcceleration: Boolean
    ): GpsDataPoint? {
        for (i in 1 until dataPoints.size) {
            val prev = dataPoints[i - 1]
            val curr = dataPoints[i]

            val crossed = if (isAcceleration) {
                prev.speed < targetSpeed && curr.speed >= targetSpeed
            } else {
                prev.speed > targetSpeed && curr.speed <= targetSpeed
            }

            if (crossed) {
                // 线性插值计算精确时刻
                val ratio = (targetSpeed - prev.speed) / (curr.speed - prev.speed)
                val preciseTime = prev.elapsedTime + ratio * (curr.elapsedTime - prev.elapsedTime)
                val preciseLat = prev.latitude + ratio * (curr.latitude - prev.latitude)
                val preciseLon = prev.longitude + ratio * (curr.longitude - prev.longitude)
                return GpsDataPoint(
                    elapsedTime = preciseTime,
                    speed = targetSpeed,
                    latitude = preciseLat,
                    longitude = preciseLon,
                    altitude = prev.altitude
                )
            }
        }
        return null
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
