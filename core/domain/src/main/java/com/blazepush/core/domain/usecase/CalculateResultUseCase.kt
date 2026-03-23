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

        // 1. 计时点修正：回溯历史数据找到精确的起始/结束点
        val correctedPoints = correctTimingPoints(dataPoints, session.template)

        // 2. 计算总时间
        val totalTime = correctedPoints.last().elapsedTime - correctedPoints.first().elapsedTime

        // 3. 计算总距离
        val totalDistance = calculateTotalDistance(correctedPoints)

        // 4. 计算加速度
        val accelerations = calculateAccelerations(correctedPoints)
        val avgAcceleration = if (accelerations.isNotEmpty()) accelerations.average() else 0.0
        val maxAcceleration = accelerations.maxOrNull() ?: 0.0

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

    private fun calculateAccelerations(dataPoints: List<GpsDataPoint>): List<Double> {
        val accelerations = mutableListOf<Double>()
        for (i in 1 until dataPoints.size) {
            val prev = dataPoints[i - 1]
            val curr = dataPoints[i]
            val dt = curr.elapsedTime - prev.elapsedTime
            // 过滤异常数据：采样间隔太短(<10ms)或太长(>1s)会导致异常值
            if (dt > 0.01 && dt < 1.0) {
                val dv = (curr.speed - prev.speed) / 3.6  // 转换为 m/s
                val accel = Math.abs(dv / dt) / 9.81       // 转换为 G
                // 过滤物理上不可能的值：超过3G通常是GPS噪声
                if (accel < 3.0) {
                    accelerations.add(accel)
                }
            }
        }
        return accelerations
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
        segments = emptyList(),
        dataPoints = emptyList(),
        dataFilePath = dataFilePath
    )
}
