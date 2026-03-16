package com.race.gps.domain.usecase

import android.location.Location
import com.race.gps.domain.model.GpsDataPoint
import com.race.gps.domain.model.SpeedSegment
import com.race.gps.domain.model.TestResult
import com.race.gps.domain.model.TestSession
import com.race.gps.domain.model.TestTemplate
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
                val results = FloatArray(1)
                Location.distanceBetween(
                    prev.latitude, prev.longitude,
                    curr.latitude, curr.longitude,
                    results
                )
                total += results[0]
            }
        }
        return total
    }

    private fun calculateAccelerations(dataPoints: List<GpsDataPoint>): List<Double> {
        val accelerations = mutableListOf<Double>()
        for (i in 1 until dataPoints.size) {
            val prev = dataPoints[i - 1]
            val curr = dataPoints[i]
            val dt = curr.elapsedTime - prev.elapsedTime
            if (dt > 0) {
                val dv = (curr.speed - prev.speed) / 3.6  // 转换为 m/s
                val accel = Math.abs(dv / dt) / 9.81       // 转换为 G
                accelerations.add(accel)
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
                (0..90 step 10).map { startSpeed ->
                    calculateSegment(dataPoints, startSpeed, startSpeed + 10, ascending = true)
                }
            }
            is TestTemplate.Braking100To0 -> {
                (100 downTo 10 step 10).map { startSpeed ->
                    calculateSegment(dataPoints, startSpeed, startSpeed - 10, ascending = false)
                }
            }
        }
    }

    private fun calculateSegment(
        dataPoints: List<GpsDataPoint>,
        fromSpeed: Int,
        toSpeed: Int,
        ascending: Boolean
    ): SpeedSegment {
        val from = fromSpeed.toDouble()
        val to = toSpeed.toDouble()

        val startPoint = findPrecisePoint(dataPoints, from, ascending)
        val endPoint = findPrecisePoint(dataPoints, to, ascending)

        val time = if (startPoint != null && endPoint != null) {
            Math.abs(endPoint.elapsedTime - startPoint.elapsedTime)
        } else 0.0

        val distance = if (startPoint != null && endPoint != null) {
            val segPoints = dataPoints.filter {
                if (ascending) it.speed in from..to
                else it.speed in to..from
            }
            calculateTotalDistance(segPoints)
        } else 0.0

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
