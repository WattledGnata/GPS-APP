package com.blazepush.core.domain.usecase

import com.blazepush.core.domain.model.GpsDataPoint
import com.blazepush.core.domain.model.PerformanceResultWindow
import com.blazepush.core.domain.model.TestTemplate
import kotlin.math.roundToLong

/** 最后一个完整性能成绩窗口及其插值后的展示/计算点。 */
data class PerformanceResultSelection(
    val window: PerformanceResultWindow,
    val dataPoints: List<GpsDataPoint>,
)

/**
 * 从完整诊断遥测中选择最终有效成绩。索引指向包围精确过线时刻的原始 sample，时间偏移
 * 相对原始首帧；[dataPoints] 已在精确边界插值并从 0 重新计时。
 */
object PerformanceResultWindowExtractor {
    fun extract(
        dataPoints: List<GpsDataPoint>,
        template: TestTemplate,
    ): PerformanceResultSelection? {
        if (dataPoints.size < 2) return null
        val candidate = when (template) {
            TestTemplate.Acceleration0To100 -> findAccelerationWindow(
                dataPoints,
                maxOf(template.startSpeed.toDouble(), MOTION_THRESHOLD_KMH),
                template.endSpeed.toDouble(),
            )
            TestTemplate.Braking100To0 -> findBrakingWindow(
                dataPoints,
                maxOf(template.endSpeed.toDouble(), MOTION_THRESHOLD_KMH),
            )
        } ?: return null

        val preciseStart = interpolateAt(
            dataPoints[candidate.startLeftIndex],
            dataPoints[candidate.startRightIndex],
            candidate.startSpeed,
        )
        val preciseEnd = interpolateAt(
            dataPoints[candidate.endLeftIndex],
            dataPoints[candidate.endRightIndex],
            candidate.endSpeed,
        )
        if (preciseEnd.elapsedTime <= preciseStart.elapsedTime) return null

        val originSeconds = dataPoints.first().elapsedTime
        val window = PerformanceResultWindow(
            startSampleIndex = candidate.startLeftIndex,
            endSampleIndex = candidate.endRightIndex,
            startDeltaMs = ((preciseStart.elapsedTime - originSeconds) * 1000.0).roundToLong(),
            endDeltaMs = ((preciseEnd.elapsedTime - originSeconds) * 1000.0).roundToLong(),
        )
        val startSeconds = preciseStart.elapsedTime
        val points = buildList {
            add(preciseStart.copy(elapsedTime = 0.0))
            dataPoints.asSequence()
                .drop(candidate.startRightIndex)
                .take(candidate.endLeftIndex - candidate.startRightIndex + 1)
                .filter { it.elapsedTime > preciseStart.elapsedTime && it.elapsedTime < preciseEnd.elapsedTime }
                .forEach { add(it.copy(elapsedTime = it.elapsedTime - startSeconds)) }
            add(preciseEnd.copy(elapsedTime = preciseEnd.elapsedTime - startSeconds))
        }
        return PerformanceResultSelection(window, points)
    }

    private fun findAccelerationWindow(
        points: List<GpsDataPoint>,
        openSpeed: Double,
        closeSpeed: Double,
    ): Candidate? {
        var pending: Pair<Int, Int>? = null
        var last: Candidate? = null
        for (i in 1 until points.size) {
            val prev = points[i - 1]
            val curr = points[i]
            if (prev.speed == curr.speed) continue
            if (prev.speed < openSpeed && curr.speed >= openSpeed) pending = (i - 1) to i
            val start = pending ?: continue
            if (prev.speed < closeSpeed && curr.speed >= closeSpeed) {
                val startPoint = interpolateAt(points[start.first], points[start.second], openSpeed)
                val endPoint = interpolateAt(prev, curr, closeSpeed)
                if (endPoint.elapsedTime > startPoint.elapsedTime) {
                    last = Candidate(start.first, start.second, i - 1, i, openSpeed, closeSpeed)
                }
                pending = null
            }
        }
        return last
    }

    private fun findBrakingWindow(points: List<GpsDataPoint>, stopSpeed: Double): Candidate? {
        for (i in 1 until points.size) {
            val prev = points[i - 1]
            val curr = points[i]
            if (prev.speed > stopSpeed && curr.speed <= stopSpeed && prev.speed != curr.speed) {
                return Candidate(0, 0, i - 1, i, points.first().speed, stopSpeed)
            }
        }
        return null
    }

    private fun interpolateAt(prev: GpsDataPoint, curr: GpsDataPoint, target: Double): GpsDataPoint {
        if (prev === curr || prev.speed == curr.speed) return prev.copy(speed = target)
        val ratio = ((target - prev.speed) / (curr.speed - prev.speed)).coerceIn(0.0, 1.0)
        return GpsDataPoint(
            elapsedTime = prev.elapsedTime + ratio * (curr.elapsedTime - prev.elapsedTime),
            speed = target,
            latitude = prev.latitude + ratio * (curr.latitude - prev.latitude),
            longitude = prev.longitude + ratio * (curr.longitude - prev.longitude),
            altitude = prev.altitude + ratio * (curr.altitude - prev.altitude),
        )
    }

    private data class Candidate(
        val startLeftIndex: Int,
        val startRightIndex: Int,
        val endLeftIndex: Int,
        val endRightIndex: Int,
        val startSpeed: Double,
        val endSpeed: Double,
    )
}
