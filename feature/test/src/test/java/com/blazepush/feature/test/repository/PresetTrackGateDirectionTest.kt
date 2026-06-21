// @IgnoreFormatCheck
package com.blazepush.feature.test.repository

import com.blazepush.feature.test.model.laptiming.GpsSample
import com.blazepush.feature.test.model.track.TimingGate
import com.blazepush.feature.test.usecase.GateCrossingDetector
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归测试：TFIC 预置赛道每个计时门的 passDirection 必须与赛车**真实行进方向**一致，
 * 使车沿真实方向穿过门时 GateCrossingDetector 判定 accepted（directionScore > 0）。
 *
 * ground-truth 行进 bearing 来源（独立于被测的 passDirection）：
 *  - 官方 track_天府国际赛道.rcz 各 trap 的 bearing 字段
 *  - 2026-06-19 真机 session 142605bb 轨迹实测验证
 *
 * 背景缺陷：自绘 preset-tfic-lpcc 的 s1/s2 passDirection 曾被画反 180°，
 * 导致该 session 20 次 Sector 过线全部 WrongDirection、分段计时（S1/S2/S3）完全失效。
 * 本测试锁住修复：若 s1/s2 再被画反，沿真实行进方向过线会 WrongDirection → 测试 fail。
 *
 * 注意：crossingAlongBearing 沿给定 bearing 造样本，**不读 gate.passDirection**，
 * 因此能独立检验 passDirection 是否朝向正确（区别于 GateCrossingDetectorTest.crossingAcrossGate
 * 沿 passDirection 自身造样本、无法发现方向反置）。
 */
class PresetTrackGateDirectionTest {

    private val detector = GateCrossingDetector()

    @Test
    fun tficAllGates_acceptCrossingAlongRealDrivingDirection() {
        val track = PresetTrackCatalog().getTrack("preset-tfic-lpcc")!!
        // gate -> 赛车真实过线 bearing（度，0=正北顺时针），+ 标签
        val cases = listOf(
            Triple(track.startFinishGate, 183.0, "起点(朝南)"),
            Triple(track.orderedSectorGates[0], 84.0, "s1(朝东)"),
            Triple(track.orderedSectorGates[1], 359.0, "s2(朝北)"),
        )
        for ((gate, bearing, label) in cases) {
            val (prev, cur) = crossingAlongBearing(gate, bearing, distanceMeters = 2.0)
            val d = detector.detect(prev, cur, gate)
            assertTrue(
                "$label 沿真实行进方向($bearing°)过线必须 accepted，实际 reason=${d.reason} " +
                    "directionScore=${d.directionScore}",
                d.accepted,
            )
            assertTrue(
                "$label directionScore 必须 > 0（passDirection 与行进方向同向），实际=${d.directionScore}",
                (d.directionScore ?: -1.0) > 0.0,
            )
        }
    }

    /** 构造一对沿指定行进 bearing、穿过 gate 线中点的 GpsSample（不读 passDirection，独立 ground truth）。 */
    private fun crossingAlongBearing(
        gate: TimingGate,
        bearingDeg: Double,
        distanceMeters: Double,
    ): Pair<GpsSample, GpsSample> {
        val centerLat = (gate.line.start.latitude + gate.line.end.latitude) / 2.0
        val centerLon = (gate.line.start.longitude + gate.line.end.longitude) / 2.0
        val lonScale = 111320.0 * kotlin.math.cos(Math.toRadians(centerLat))
        val rad = Math.toRadians(bearingDeg)
        val north = kotlin.math.cos(rad)
        val east = kotlin.math.sin(rad)
        val half = distanceMeters / 2.0
        val dLat = (north * half) / 111320.0
        val dLon = (east * half) / lonScale
        val prev = GpsSample(
            timestampMillis = 0L,
            latitude = centerLat - dLat,
            longitude = centerLon - dLon,
            speedKmh = 72.0,
        )
        val cur = GpsSample(
            timestampMillis = 40L,
            latitude = centerLat + dLat,
            longitude = centerLon + dLon,
            speedKmh = 72.0,
        )
        return prev to cur
    }
}
