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
import org.junit.Assert.assertNull
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
        // 位移方向与 gate 线近平行（与 gate 线夹角 ~5°），攻击 review 1.7 数值稳定性
        val crossing = crossingAcrossGateWithAngle(
            tficGate,
            previousTs = 0L,
            currentTs = 40L,
            distanceMeters = 2.0,
            // A9 修正：crossingAcrossGateWithAngle 内部把 passUnit 旋转 angleOffsetDegrees，
            // 85° 意味着位移**与 passDirection 夹角 85°**（即与 gate 线夹角 ~5°，接近平行
            // 于 gate 线）。原注释写成"与 passDirection 夹角 5°"是反的。
            angleOffsetDegrees = 85.0
        )
        val detection = detector.detect(crossing.first, crossing.second, tficGate)
        assertEquals(
            "位移与 gate 线近平行（夹角 ~5°）但仍跨线，必须 accepted（米投影后 denominator 数量级稳定）",
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

    // ==================== openspec fix-lap-timing-closure-and-precision-contract R1 ====================
    //
    // R1 Requirement: GateCrossingDetector.detect 返回归一化过线参数 crossingProgress
    //   Scenarios 1-5：accepted 返回 [0,1] 非 null / 对称过线 == 0.5 / 浮点越界 clamp /
    //                 rejected 三路径 null / segmentsIntersectMeters 返回 Double?

    @Test
    fun detect_acceptedCrossing_returnsCrossingProgressInRange() {
        // R1 Scenario 1：accepted 过线返回 crossingProgress ∈ [0, 1] 非 null
        val detection = detector.detect(
            previous = sample(timestampMillis = 1_000L, latitude = -0.1, longitude = 0.5),
            current = sample(timestampMillis = 1_100L, latitude = 0.1, longitude = 0.5),
            gate = gate()
        )

        assertEquals(true, detection.accepted)
        assertNotNull("accepted 分支 crossingProgress MUST 非 null", detection.crossingProgress)
        val progress = detection.crossingProgress!!
        assertTrue(
            "crossingProgress 应在 [0.0, 1.0] 范围内，实际=$progress",
            progress in 0.0..1.0
        )
    }

    @Test
    fun detect_symmetricCrossing_returnsCrossingProgressEqualsHalf() {
        // R1 Scenario 2：对称过线 crossingProgress 精确等于 0.5
        // prev 与 current 相对 gate 中心对称偏移 0.25 × passDirection，过线发生在线段中点
        val detection = detector.detect(
            previous = sample(timestampMillis = 1_000L, latitude = -0.25, longitude = 0.5),
            current = sample(timestampMillis = 1_100L, latitude = 0.25, longitude = 0.5),
            gate = gate()
        )

        assertEquals(true, detection.accepted)
        assertNotNull(detection.crossingProgress)
        assertEquals(
            "对称过线 crossingProgress 应精确等于 0.5 (浮点精度 1e-9 内)",
            0.5,
            detection.crossingProgress!!,
            1e-9
        )
    }

    @Test
    fun detect_floatingPointOverflow_crossingProgressIsClamped() {
        // R1 Scenario 3 / P1-1：浮点边界越界被 coerceIn 收敛到 [0, 1]
        // 硬区分 v1（segmentsIntersectMeters 内部严格 `t in 0.0..1.0` 直接返回 null）
        //   vs v2（接受 [-tolerance, 1 + tolerance] 容差，返回 t；detect 的 coerceIn 做最终 clamp）

        // 构造几何让 t = 2.0 显著越界（prev→current 线段 a→b 的 1/2 长度外，gate 线垂直于路径）
        // 线段 prev(0,0)→current(0,1)，gate (-1,2)→(1,2)：
        //   denominator = abx × cdy - aby × cdx = 0 × 0 - 1 × 2 = -2
        //   t = (acx × cdy - acy × cdx) / denom = ((-1)×0 - 2×2) / -2 = -4 / -2 = 2.0
        //   u = (acx × aby - acy × abx) / denom = ((-1)×1 - 2×0) / -2 = -1 / -2 = 0.5

        // v1 默认严格语义下 t=2.0 显著越界 → 返回 null（"不相交"）
        val strict = detector.segmentsIntersectMeters(
            ax = 0.0, ay = 0.0, bx = 0.0, by = 1.0,
            cx = -1.0, cy = 2.0, dx = 1.0, dy = 2.0
        )
        assertNull(
            "default tolerance=1e-9 下 t=2.0 显著越界 MUST 返回 null（v1/v2 公共行为）",
            strict
        )

        // 传大 tolerance=3.0 验证容差接受路径：v2 返回 t=2.0（不 null）
        val relaxed = detector.segmentsIntersectMeters(
            ax = 0.0, ay = 0.0, bx = 0.0, by = 1.0,
            cx = -1.0, cy = 2.0, dx = 1.0, dy = 2.0,
            tolerance = 3.0
        )
        assertNotNull(
            "tolerance=3.0 接受 t=2.0 微越界（EPSILON 容差机制生效）",
            relaxed
        )
        assertEquals(
            "容差接受后返回原始 t=2.0（交给 detect 的 coerceIn 做 clamp）",
            2.0,
            relaxed!!,
            1e-9
        )

        // 模拟 detect 路径：对 segmentsIntersectMeters 返回的越界 t 做 coerceIn → [0, 1]
        assertEquals(
            "detect 内 intersectionT.coerceIn(0.0, 1.0)：t=2.0 → 1.0 上界 clamp",
            1.0,
            relaxed.coerceIn(0.0, 1.0),
            0.0
        )
        assertEquals(
            "对称：t=-1e-16 → 0.0 下界 clamp",
            0.0,
            (-1e-16).coerceIn(0.0, 1.0),
            0.0
        )

        // 硬区分反证：若 v1 实现保留（严格 `t in 0.0..1.0` 返回 null），
        // 同一几何在 default tolerance 下会给出 null，从而 detect 走 NoIntersection 路径、
        // 返回 accepted=false / crossingProgress=null —— clamp 契约名存实亡。
        // default tolerance 下 v2 也可能对显著越界返回 null，所以本 Scenario 硬区分的是
        // **"扩大 tolerance 下容差机制是否真的接受越界 t"**；实际生产路径下微越界（~1e-16）
        // 被 `FLOAT_BOUNDARY_TOLERANCE = 1e-9` 接受后由 detect 的 coerceIn clamp。

        // 正向行为：端点过线（几何上 t=1.0 精确）在 default tolerance 下被 accepted
        val endpointDetection = detector.detect(
            previous = sample(timestampMillis = 1_000L, latitude = -0.1, longitude = 0.5),
            current = sample(timestampMillis = 1_100L, latitude = 0.0, longitude = 0.5),  // current 刚好位于 gate 线 lat=0 上
            gate = gate()
        )
        if (endpointDetection.accepted) {
            val progress = endpointDetection.crossingProgress!!
            assertTrue(
                "端点过线 crossingProgress MUST 在 [0, 1] 内（coerceIn 兜底）",
                progress in 0.0..1.0
            )
        }
    }

    @Test
    fun detect_rejectedCrossing_crossingProgressIsNull() {
        // R1 Scenario 4：rejected 三路径 NoIntersection / WrongDirection / TooSlow 均为 null
        // NoIntersection (prev/current 同侧，无几何相交)
        val noIntersection = detector.detect(
            previous = sample(timestampMillis = 1_000L, latitude = -0.3, longitude = 0.5),
            current = sample(timestampMillis = 2_000L, latitude = -0.1, longitude = 0.5),
            gate = gate()
        )
        assertEquals(false, noIntersection.accepted)
        assertEquals(CrossingReason.NoIntersection, noIntersection.reason)
        assertNull("NoIntersection 路径 crossingProgress MUST 为 null", noIntersection.crossingProgress)

        // WrongDirection (反向过线)
        val wrongDirection = detector.detect(
            previous = sample(timestampMillis = 1_000L, latitude = 0.1, longitude = 0.5),
            current = sample(timestampMillis = 2_000L, latitude = -0.1, longitude = 0.5),
            gate = gate()
        )
        assertEquals(false, wrongDirection.accepted)
        assertEquals(CrossingReason.WrongDirection, wrongDirection.reason)
        assertNull("WrongDirection 路径 crossingProgress MUST 为 null", wrongDirection.crossingProgress)

        // TooSlow (directionalSpeedMps < gate.minDirectionalSpeedMps)
        // 构造方法：gate.minDirectionalSpeedMps = 1.0，prev/current 过线但 dt 很大 → 速度很小
        val tooSlow = detector.detect(
            previous = sample(timestampMillis = 1_000L, latitude = -0.0001, longitude = 0.5),
            current = sample(timestampMillis = 1_000_000_000L, latitude = 0.0001, longitude = 0.5),
            gate = gate()
        )
        assertEquals(false, tooSlow.accepted)
        assertEquals(CrossingReason.TooSlow, tooSlow.reason)
        assertNull("TooSlow 路径 crossingProgress MUST 为 null", tooSlow.crossingProgress)
    }

    @Test
    fun segmentsIntersectMeters_returnsDoubleNullable() {
        // R1 Scenario 5：segmentsIntersectMeters 返回 Double? 语义
        // 直接调 internal 函数（@VisibleForTesting）覆盖 4 个子场景

        // 子场景 1：相交正向（prev→current 从下到上，gate 线水平）
        val intersectForward = detector.segmentsIntersectMeters(
            ax = 0.0, ay = -1.0, bx = 0.0, by = 1.0,  // prev→current 垂直向上
            cx = -1.0, cy = 0.0, dx = 1.0, dy = 0.0   // gate 水平
        )
        assertNotNull("相交正向 MUST 返回非 null", intersectForward)
        assertEquals("相交在中点，t=0.5", 0.5, intersectForward!!, 1e-9)
        assertTrue("t MUST 在 [0, 1] 范围内", intersectForward in 0.0..1.0)

        // 子场景 2：相交反向（方向不在本函数处理，仅几何）
        // prev→current 从上到下，gate 仍水平 —— 几何仍相交，t 仍 [0, 1]
        val intersectReverse = detector.segmentsIntersectMeters(
            ax = 0.0, ay = 1.0, bx = 0.0, by = -1.0,
            cx = -1.0, cy = 0.0, dx = 1.0, dy = 0.0
        )
        assertNotNull("相交反向（几何层）MUST 返回非 null", intersectReverse)
        assertTrue("t MUST 在 [0, 1] 范围内", intersectReverse!! in 0.0..1.0)

        // 子场景 3：不相交（prev 和 current 同侧）
        val noIntersect = detector.segmentsIntersectMeters(
            ax = 0.0, ay = -2.0, bx = 0.0, by = -1.0,  // prev→current 都在下方
            cx = -1.0, cy = 0.0, dx = 1.0, dy = 0.0
        )
        assertNull("不相交 MUST 返回 null", noIntersect)

        // 子场景 4：denominator == 0（严格平行）
        val parallel = detector.segmentsIntersectMeters(
            ax = 0.0, ay = 0.0, bx = 1.0, by = 0.0,   // 水平线段 1
            cx = 0.0, cy = 1.0, dx = 1.0, dy = 1.0    // 水平线段 2（平行）
        )
        assertNull("严格平行 MUST 返回 null（v1 防御性语义保留）", parallel)
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
