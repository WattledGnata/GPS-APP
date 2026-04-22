package com.blazepush.feature.test.usecase

import com.blazepush.feature.test.model.laptiming.CrossingReason
import com.blazepush.feature.test.model.laptiming.GpsSample
import com.blazepush.feature.test.model.track.GeoLine
import com.blazepush.feature.test.model.track.GeoPoint
import com.blazepush.feature.test.model.track.GeoVector
import com.blazepush.feature.test.model.track.TimingGate
import com.blazepush.feature.test.model.track.TimingGateType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GateCrossingDetectorTest {

    private val detector = GateCrossingDetector()

    @Test
    fun crossingInPositiveDirection_isAccepted() {
        val detection = detector.detect(
            previous = sample(timestampMillis = 1_000L, latitude = -0.1, longitude = 0.5),
            current = sample(timestampMillis = 1_100L, latitude = 0.1, longitude = 0.5),
            gate = gate()
        )

        assertEquals(true, detection.accepted)
        assertEquals(CrossingReason.Accepted, detection.reason)
        assertNotNull(detection.directionalSpeedMps)
        assertNotNull(detection.directionScore)
        assertTrue(detection.directionalSpeedMps!! >= 1.0)
        assertTrue(detection.directionScore!! > 0.0)
    }

    @Test
    fun crossingInReverseDirection_isRejected() {
        val detection = detector.detect(
            previous = sample(timestampMillis = 1_000L, latitude = 0.1, longitude = 0.5),
            current = sample(timestampMillis = 2_000L, latitude = -0.1, longitude = 0.5),
            gate = gate()
        )

        assertEquals(false, detection.accepted)
        assertEquals(CrossingReason.WrongDirection, detection.reason)
        assertNotNull(detection.directionScore)
        assertTrue(detection.directionScore!! <= 0.0)
    }

    @Test
    fun movementWithoutIntersection_isRejected() {
        val detection = detector.detect(
            previous = sample(timestampMillis = 1_000L, latitude = -0.3, longitude = 0.5),
            current = sample(timestampMillis = 2_000L, latitude = -0.1, longitude = 0.5),
            gate = gate()
        )

        assertEquals(false, detection.accepted)
        assertEquals(CrossingReason.NoIntersection, detection.reason)
        assertEquals(null, detection.directionalSpeedMps)
        assertEquals(null, detection.directionScore)
    }

    @Test
    fun crossingInfiniteGateExtensionOutsideSegment_isRejected() {
        val detection = detector.detect(
            previous = sample(timestampMillis = 1_000L, latitude = -0.1, longitude = 1.5),
            current = sample(timestampMillis = 1_100L, latitude = 0.1, longitude = 1.5),
            gate = gate()
        )

        assertEquals(false, detection.accepted)
        assertEquals(CrossingReason.NoIntersection, detection.reason)
    }

    @Test
    fun crossingWithinGateSegment_isAccepted() {
        val detection = detector.detect(
            previous = sample(timestampMillis = 1_000L, latitude = -0.1, longitude = 0.5),
            current = sample(timestampMillis = 1_100L, latitude = 0.1, longitude = 0.5),
            gate = gate()
        )

        assertEquals(true, detection.accepted)
        assertEquals(CrossingReason.Accepted, detection.reason)
    }

    // ==================== v2 (fix-gate-crossing-detector-units-and-stability) ====================

    /**
     * 对抗 review 1.1 回归测试：`minDirectionalSpeedMps` 启用后必须正确识别 m/s。
     * 修前 `directionalSpeedMps` 是度²/秒量纲，13.9 m/s 下限会锁死所有真实穿线；
     * 修后：投影到米 + 归一化 passDirection → directionalSpeedMps 真实 m/s。
     */
    @Test
    fun minDirectionalSpeedMps_enforcesRealWorldMps_onTficPresetGate() {
        val track = com.blazepush.feature.test.repository.PresetTrackCatalog()
            .getTrack("preset-tfic-lpcc")!!
        val tficGate = track.startFinishGate.copy(minDirectionalSpeedMps = 13.9) // 50 km/h 下限

        // 构造 120 km/h × 40ms = 1.333m 的位移，沿 passDirection 方向从 gate 一侧走到另一侧
        val fastCrossing = crossingAcrossGate(tficGate, previousTs = 0L, currentTs = 40L, distanceMeters = 1.333)
        val fastDetection = detector.detect(fastCrossing.first, fastCrossing.second, tficGate)
        assertEquals(
            "120 km/h 过线必须 accepted（修前 directionalSpeedMps≈9e-8 度²/s 会被 13.9 锁死）",
            true, fastDetection.accepted
        )
        assertEquals(CrossingReason.Accepted, fastDetection.reason)
        assertTrue(
            "directionalSpeedMps 必须在 120 km/h 附近（实际 m/s 量纲）: got=${fastDetection.directionalSpeedMps}",
            fastDetection.directionalSpeedMps!! in 30.0..36.0
        )

        // 20 km/h × 40ms = 0.222m 位移，应当被 TooSlow 拦住
        val slowCrossing = crossingAcrossGate(tficGate, previousTs = 0L, currentTs = 40L, distanceMeters = 0.222)
        val slowDetection = detector.detect(slowCrossing.first, slowCrossing.second, tficGate)
        assertEquals(
            "20 km/h 过线应当被 13.9 m/s 下限拦下",
            false, slowDetection.accepted
        )
        assertEquals(CrossingReason.TooSlow, slowDetection.reason)
    }

    /**
     * 对抗 review 1.7 回归测试：接近平行的位移不应因浮点抖动漏报。
     * 修前 detector 用原始度空间叉积，denominator 在 1e-10 量级时 t/u 爆炸。
     * 修后投影到米空间，denominator 数量级稳定。
     */
    @Test
    fun detect_nearParallelCrossing_acceptsStably() {
        val track = com.blazepush.feature.test.repository.PresetTrackCatalog()
            .getTrack("preset-tfic-lpcc")!!
        val tficGate = track.startFinishGate
        // 位移方向与 gate 线夹角约 5°（接近平行但未平行）
        val crossing = crossingAcrossGateWithAngle(
            tficGate,
            previousTs = 0L,
            currentTs = 40L,
            distanceMeters = 2.0,
            angleOffsetDegrees = 85.0 // 与 passDirection 夹角 5°，与 gate 线夹角约 85°
        )
        val detection = detector.detect(crossing.first, crossing.second, tficGate)
        assertEquals(
            "接近平行（与 passDirection 夹角 5°）但仍跨线的位移必须 accepted",
            true, detection.accepted
        )
        assertEquals(CrossingReason.Accepted, detection.reason)
    }

    /**
     * 对抗 review 1.7 回归测试：切到 gate 线端点附近的穿线行为必须稳定，
     * 不随浮点微小扰动在 accept/reject 之间抖动。
     */
    @Test
    fun detect_tangentialContactAtGateEnd_isStableAcrossJitter() {
        val track = com.blazepush.feature.test.repository.PresetTrackCatalog()
            .getTrack("preset-tfic-lpcc")!!
        val tficGate = track.startFinishGate

        // 两次相同几何 crossing，仅在浮点末位微扰一下 lat/lon，结果必须一致
        val c1 = crossingAcrossGate(tficGate, previousTs = 0L, currentTs = 40L, distanceMeters = 2.0)
        val p1 = c1.first
        val q1 = c1.second
        val p2 = p1.copy(latitude = p1.latitude + 1e-12, longitude = p1.longitude + 1e-12)
        val q2 = q1.copy(latitude = q1.latitude + 1e-12, longitude = q1.longitude + 1e-12)

        val d1 = detector.detect(p1, q1, tficGate)
        val d2 = detector.detect(p2, q2, tficGate)
        assertEquals("浮点 1e-12 扰动不应改变 accepted 判定", d1.accepted, d2.accepted)
        assertEquals("reason 也必须稳定", d1.reason, d2.reason)
    }

    /** 构造一对沿 passDirection 方向、中点位于 gate 线中点的 sample，位移 distanceMeters。 */
    private fun crossingAcrossGate(
        gate: TimingGate,
        previousTs: Long,
        currentTs: Long,
        distanceMeters: Double
    ): Pair<GpsSample, GpsSample> {
        val centerLat = (gate.line.start.latitude + gate.line.end.latitude) / 2.0
        val centerLon = (gate.line.start.longitude + gate.line.end.longitude) / 2.0
        // passDirection 度空间方向投影到米，归一化为单位向量
        val lonScale = 111320.0 * kotlin.math.cos(Math.toRadians(centerLat))
        val passN = gate.passDirection.x * 111320.0
        val passE = gate.passDirection.y * lonScale
        val passLen = kotlin.math.sqrt(passN * passN + passE * passE)
        // 单位向量在度空间（供下游把半距转回度偏移）
        val unitLat = (passN / passLen) / 111320.0
        val unitLon = (passE / passLen) / lonScale
        val halfDist = distanceMeters / 2.0
        // 半距转度偏移：米 * (度/米)
        val half = halfDist // 米
        val prev = GpsSample(
            timestampMillis = previousTs,
            latitude = centerLat - unitLat * half,
            longitude = centerLon - unitLon * half,
            speedKmh = 36.0
        )
        val curr = GpsSample(
            timestampMillis = currentTs,
            latitude = centerLat + unitLat * half,
            longitude = centerLon + unitLon * half,
            speedKmh = 36.0
        )
        return prev to curr
    }

    /** 构造与 passDirection 夹角 angleOffsetDegrees 的位移（投射到米空间后旋转）。 */
    private fun crossingAcrossGateWithAngle(
        gate: TimingGate,
        previousTs: Long,
        currentTs: Long,
        distanceMeters: Double,
        angleOffsetDegrees: Double
    ): Pair<GpsSample, GpsSample> {
        val centerLat = (gate.line.start.latitude + gate.line.end.latitude) / 2.0
        val centerLon = (gate.line.start.longitude + gate.line.end.longitude) / 2.0
        val lonScale = 111320.0 * kotlin.math.cos(Math.toRadians(centerLat))
        val passN = gate.passDirection.x * 111320.0
        val passE = gate.passDirection.y * lonScale
        val passLen = kotlin.math.sqrt(passN * passN + passE * passE)
        val passUnitN = passN / passLen
        val passUnitE = passE / passLen
        // 将 passUnit 旋转 angleOffsetDegrees
        val rad = Math.toRadians(angleOffsetDegrees)
        val cos = kotlin.math.cos(rad)
        val sin = kotlin.math.sin(rad)
        val rotN = passUnitN * cos - passUnitE * sin
        val rotE = passUnitN * sin + passUnitE * cos
        val half = distanceMeters / 2.0
        val unitLat = rotN / 111320.0
        val unitLon = rotE / lonScale
        val prev = GpsSample(
            timestampMillis = previousTs,
            latitude = centerLat - unitLat * half,
            longitude = centerLon - unitLon * half,
            speedKmh = 36.0
        )
        val curr = GpsSample(
            timestampMillis = currentTs,
            latitude = centerLat + unitLat * half,
            longitude = centerLon + unitLon * half,
            speedKmh = 36.0
        )
        return prev to curr
    }

    private fun gate(): TimingGate = TimingGate(
        id = "start-finish",
        name = "Start/Finish",
        type = TimingGateType.StartFinish,
        line = GeoLine(
            start = GeoPoint(latitude = 0.0, longitude = 0.0),
            end = GeoPoint(latitude = 0.0, longitude = 1.0)
        ),
        passDirection = GeoVector(x = 1.0, y = 0.0),
        sequenceIndex = 0,
        minDirectionalSpeedMps = 1.0
    )

    private fun sample(
        timestampMillis: Long,
        latitude: Double,
        longitude: Double
    ): GpsSample = GpsSample(
        timestampMillis = timestampMillis,
        latitude = latitude,
        longitude = longitude,
        speedKmh = 10.0
    )
}
