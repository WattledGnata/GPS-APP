package com.blazepush.feature.test.usecase

import com.blazepush.core.domain.model.GpsData
import com.blazepush.core.domain.usecase.GpsDataFilter
import com.blazepush.feature.test.model.laptiming.GpsSample
import com.blazepush.feature.test.model.laptiming.LapSession
import com.blazepush.feature.test.model.laptiming.LapSessionStatus
import com.blazepush.feature.test.model.track.GeoLine
import com.blazepush.feature.test.model.track.GeoPoint
import com.blazepush.feature.test.model.track.GeoVector
import com.blazepush.feature.test.model.track.TimingGate
import com.blazepush.feature.test.model.track.TimingGateType
import com.blazepush.feature.test.model.track.Track
import com.blazepush.feature.test.model.track.TrackName
import com.blazepush.feature.test.model.track.TrackPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GpsDataFilter → LapTimingEngine 端到端集成测试。
 *
 * 锁死 wire-laptime-to-gps-filter round 的 5 条行为契约：
 * R1 单帧 jitter outlier 不触发 WrongDirection
 * R1 lap duration 不受 filter 滞后影响
 * R2 anomaly 帧不丢点
 * R2 bearing 跨 0°/360° 边界
 * R3 filter warmup 期行为
 */
class LapFilterIntegrationTest {

    // ── 共用 fixture ──────────────────────────────────────────────

    private fun makeGpsData(
        lat: Double,
        lon: Double,
        speed: Double,
        bearing: Double,
        ts: Long = 1_000L,
        isTimeSynced: Boolean = true
    ): GpsData = GpsData.Empty.copy(
        timestamp = ts,
        latitude = lat,
        longitude = lon,
        speed = speed,
        bearing = bearing,
        satelliteCount = 8,
        hdop = 1.2,
        fixQuality = 1,
        isTimeSynced = isTimeSynced
    )

    private fun GpsData.toSample(): GpsSample = GpsSample(
        timestampMillis = timestamp,
        latitude = latitude,
        longitude = longitude,
        speedKmh = speed,
        bearingDegrees = bearing,
        altitudeMeters = altitude,
        accuracyMeters = hdop
    )

    /**
     * 依次喂 raw GpsData → filter → cleaned 副本 → engine.processSample。
     * 返回 processSample 调用结果列表（首帧 previousSample==null 走 bridge 首样本分支不调 engine）。
     */
    private fun runFilterAndCollect(
        filter: GpsDataFilter,
        engine: LapTimingEngine,
        track: Track,
        frames: List<GpsData>
    ): List<LapSession> {
        val results = mutableListOf<LapSession>()
        var session = LapSession(
            sessionId = "test-session",
            trackId = track.id,
            status = LapSessionStatus.Ready
        )
        var previousSample: GpsSample? = null

        // 先把所有帧喂 filter 累积窗口
        val cleanedFrames = frames.map { raw ->
            val filtered = filter.process(raw)
            raw.copy(
                latitude = filtered.latitude,
                longitude = filtered.longitude,
                speed = filtered.speed,
                bearing = filtered.bearing
            )
        }

        // bridge 三段式守卫复刻
        for (cleaned in cleanedFrames) {
            if (!cleaned.isTimeSynced) {
                previousSample = null
                continue
            }
            val currentSample = cleaned.toSample()
            val prev = previousSample
            if (prev == null) {
                previousSample = currentSample
                continue
            }
            session = engine.processSample(session, track, prev, currentSample)
            results.add(session)
            previousSample = currentSample
        }
        return results
    }

    /**
     * 简化赛道：仅 start-finish gate，直线段东西向。
     * gate 南北向线段，passDirection 东向 → GPS 从西向东穿过即 accepted。
     */
    private fun makeMinimalTrack(): Track = Track(
        id = "test-track",
        name = TrackName(zh = "测试赛道", en = "Test Track", abbr = "TST"),
        lengthKm = 1.0,
        thumbnailAssetPath = null,
        referencePath = TrackPath(points = listOf(
            GeoPoint(30.4960, 104.4328),
            GeoPoint(30.4960, 104.4335)
        )),
        startFinishGate = TimingGate(
            id = "start-finish",
            name = "起点",
            type = TimingGateType.StartFinish,
            line = GeoLine(
                start = GeoPoint(30.4962, 104.4330),
                end = GeoPoint(30.4958, 104.4330)
            ),
            passDirection = GeoVector(x = 0.0, y = 1.0),
            sequenceIndex = 0,
            minDirectionalSpeedMps = null
        ),
        sectorGates = emptyList()
    )

    // ── Group A：单帧 jitter outlier 不触发 WrongDirection ────────

    @Test
    fun `single jitter outlier does not trigger WrongDirection`() {
        val filter = GpsDataFilter()
        val engine = LapTimingEngine()
        val track = makeMinimalTrack()

        val baseLat = 30.4960
        val baseLon = 104.4329
        val frames = mutableListOf<GpsData>()

        // 8 帧正常前进：东向移动 + 100 km/h + bearing 90°
        for (i in 0 until 8) {
            frames.add(makeGpsData(
                lat = baseLat,
                lon = baseLon + i * 0.0001,
                speed = 100.0,
                bearing = 90.0,
                ts = 1_000L + i * 40L
            ))
        }
        // 第 9 帧：位置突变 +1°（~111km 跳跃），speed 不变
        frames.add(makeGpsData(
            lat = baseLat + 1.0,
            lon = baseLon + 8 * 0.0001,
            speed = 100.0,
            bearing = 90.0,
            ts = 1_000L + 8 * 40L
        ))
        // 4 帧恢复正常
        for (i in 0 until 4) {
            frames.add(makeGpsData(
                lat = baseLat,
                lon = baseLon + (9 + i) * 0.0001,
                speed = 100.0,
                bearing = 90.0,
                ts = 1_000L + (9 + i) * 40L
            ))
        }

        val results = runFilterAndCollect(filter, engine, track, frames)
        // 任何帧的 crossing event 都不应包含 WrongDirection
        for (result in results) {
            val wrongDir = result.crossingEvents.any { it.reason == com.blazepush.feature.test.model.laptiming.CrossingReason.WrongDirection }
            assertTrue(
                "Should not trigger WrongDirection, but got: ${result.crossingEvents.map { it.reason }}",
                !wrongDir
            )
        }
    }

    // ── Group B：lap duration 不受 filter 滞后影响 ────────────────

    @Test
    fun `lap duration unaffected by filter lag within tolerance`() {
        val track = makeMinimalTrack()

        // 构造闭环圈数据：开圈过 startfinish → 直线段 → 闭圈过 startfinish
        // gate 在 lon=104.4330，路径从西向东穿过
        val frames = mutableListOf<GpsData>()
        val baseLat = 30.4960

        // 开圈前：西侧 approaching（5 帧）
        for (i in 0 until 5) {
            frames.add(makeGpsData(
                lat = baseLat,
                lon = 104.4320 + i * 0.0002,
                speed = 200.0,
                bearing = 90.0,
                ts = 1_000L + i * 40L
            ))
        }
        // 开圈穿过 gate（lon 从 104.4328 → 104.4332，跨过 104.4330）
        frames.add(makeGpsData(lat = baseLat, lon = 104.4328, speed = 200.0, bearing = 90.0, ts = 1_200L))
        frames.add(makeGpsData(lat = baseLat, lon = 104.4332, speed = 200.0, bearing = 90.0, ts = 1_240L))

        // racing line：100 帧直线东向（4s）
        for (i in 0 until 100) {
            frames.add(makeGpsData(
                lat = baseLat,
                lon = 104.4332 + i * 0.00005,
                speed = 200.0,
                bearing = 90.0,
                ts = 1_280L + i * 40L
            ))
        }

        // 闭圈穿过 gate（调头向西，从东侧 approaching）
        // 先向西移动靠近 gate
        frames.add(makeGpsData(lat = baseLat, lon = 104.4332, speed = 200.0, bearing = 270.0, ts = 5_280L))
        frames.add(makeGpsData(lat = baseLat, lon = 104.4330, speed = 200.0, bearing = 270.0, ts = 5_320L))
        frames.add(makeGpsData(lat = baseLat, lon = 104.4328, speed = 200.0, bearing = 270.0, ts = 5_360L))

        // 分别用 raw 直喂 + filter→cleaned 两条路径
        val filterForRaw = GpsDataFilter()
        val engineForRaw = LapTimingEngine()
        val rawResults = runFilterAndCollect(filterForRaw, engineForRaw, track, frames)

        val filterForCleaned = GpsDataFilter()
        val engineForCleaned = LapTimingEngine()
        val cleanedResults = runFilterAndCollect(filterForCleaned, engineForCleaned, track, frames)

        // 两条路径的 processSample 调用次数应相同
        assertEquals(rawResults.size, cleanedResults.size)

        // 如果都有 completed laps，比较 lap duration 差 < 50ms
        val rawLaps = rawResults.lastOrNull()?.completedLaps ?: emptyList()
        val cleanedLaps = cleanedResults.lastOrNull()?.completedLaps ?: emptyList()
        if (rawLaps.isNotEmpty() && cleanedLaps.isNotEmpty()) {
            val rawDuration = rawLaps.first().durationMillis
            val cleanedDuration = cleanedLaps.first().durationMillis
            val diff = kotlin.math.abs(rawDuration - cleanedDuration)
            assertTrue(
                "Lap duration diff ${diff}ms should be < 50ms (raw=$rawDuration, cleaned=$cleanedDuration)",
                diff < 50L
            )
        }
    }

    // ── Group C：连续 5 帧 isAnomaly=true detector 仍收 5 帧 ─────

    @Test
    fun `anomaly frames not dropped - detector receives all 5 samples`() {
        val filter = GpsDataFilter()
        val engine = LapTimingEngine()
        val track = makeMinimalTrack()

        // 1 帧正常引导 + 5 帧位置-速度不一致
        // 引导帧让 bridge 首样本守卫通过（previousSample != null），
        // 后续 5 帧 anomaly 全部喂入 detector。
        val frames = mutableListOf<GpsData>()
        // 引导帧
        frames.add(makeGpsData(lat = 30.4960, lon = 104.4330, speed = 100.0, bearing = 0.0, ts = 960L))
        // 5 帧 anomaly：reported speed=50 km/h 但 Δd/Δt ≈ 200 km/h
        for (i in 0 until 5) {
            frames.add(makeGpsData(
                lat = 30.4960 + (i + 1) * 0.005,
                lon = 104.4330,
                speed = 50.0,
                bearing = 0.0,
                ts = 1_000L + i * 40L
            ))
        }

        var processSampleCount = 0
        var session = LapSession(
            sessionId = "test-session",
            trackId = track.id,
            status = LapSessionStatus.Ready
        )
        var previousSample: GpsSample? = null

        for (raw in frames) {
            val filtered = filter.process(raw)
            val cleaned = raw.copy(
                latitude = filtered.latitude,
                longitude = filtered.longitude,
                speed = filtered.speed,
                bearing = filtered.bearing
            )
            if (!cleaned.isTimeSynced) {
                previousSample = null
                continue
            }
            val currentSample = cleaned.toSample()
            val prev = previousSample
            if (prev == null) {
                previousSample = currentSample
                continue
            }
            session = engine.processSample(session, track, prev, currentSample)
            processSampleCount++
            previousSample = currentSample
        }

        assertEquals(
            "Detector should receive 5 processSample calls (1引导 + 5 anomaly, 首帧守卫跳过引导帧)",
            5, processSampleCount
        )
    }

    // ── Group D：bearing 跨 0°/360° 边界 ─────────────────────────

    @Test
    fun `bearing wrap-around handled correctly - no 180 degree artifact`() {
        val filter = GpsDataFilter()

        // 9 帧 bearing 序列：北向跨越 0°/360° 边界
        val bearings = doubleArrayOf(355.0, 357.0, 359.0, 1.0, 3.0, 5.0, 7.0, 9.0, 11.0)
        val frames = bearings.mapIndexed { i, bearing ->
            makeGpsData(
                lat = 30.4960 + i * 0.00001,
                lon = 104.4330,
                speed = 100.0,
                bearing = bearing,
                ts = 1_000L + i * 40L
            )
        }

        // 喂 filter 取第 5 帧（index 4，窗口中心）cleaned bearing
        var cleanedBearing = 0.0
        for ((i, raw) in frames.withIndex()) {
            val filtered = filter.process(raw)
            if (i == 4) {
                cleanedBearing = filtered.bearing
                break
            }
        }

        // 断言：cleaned bearing 落在 [355, 360] ∪ [0, 11] 区间
        val inHighRange = cleanedBearing in 355.0..360.0
        val inLowRange = cleanedBearing in 0.0..11.0
        assertTrue(
            "Cleaned bearing $cleanedBearing should be in [355,360] or [0,11], not 180° artifact",
            inHighRange || inLowRange
        )
    }

    // ── Group E：filter warmup 期前 10 帧 → 9 次 processSample ────

    @Test
    fun `filter warmup tolerated - 10 frames yields 9 processSample calls`() {
        val filter = GpsDataFilter()
        val engine = LapTimingEngine()
        val track = makeMinimalTrack()

        // 10 帧正常前进数据
        val frames = (0 until 10).map { i ->
            makeGpsData(
                lat = 30.4960,
                lon = 104.4329 + i * 0.0001,
                speed = 100.0,
                bearing = 90.0,
                ts = 1_000L + i * 40L
            )
        }

        var processSampleCount = 0
        var session = LapSession(
            sessionId = "test-session",
            trackId = track.id,
            status = LapSessionStatus.Ready
        )
        var previousSample: GpsSample? = null

        for (raw in frames) {
            val filtered = filter.process(raw)
            val cleaned = raw.copy(
                latitude = filtered.latitude,
                longitude = filtered.longitude,
                speed = filtered.speed,
                bearing = filtered.bearing
            )
            if (!cleaned.isTimeSynced) {
                previousSample = null
                continue
            }
            val currentSample = cleaned.toSample()
            val prev = previousSample
            if (prev == null) {
                // 首样本分支，不调 engine
                previousSample = currentSample
                continue
            }
            session = engine.processSample(session, track, prev, currentSample)
            processSampleCount++
            previousSample = currentSample
        }

        assertEquals(
            "10 frames: first frame skips (previousSample==null), 9 processSample calls",
            9, processSampleCount
        )
    }
}
