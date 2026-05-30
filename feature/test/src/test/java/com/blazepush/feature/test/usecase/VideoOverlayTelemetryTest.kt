// @IgnoreFormatCheck
package com.blazepush.feature.test.usecase

import com.blazepush.core.domain.model.LapTelemetrySample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * @author CC
 * @description verify offline overlay telemetry G recompute (longitudinal diff / lateral bearing-rate) + lap window resolution
 * @date 2026-05-31
 */
class VideoOverlayTelemetryTest {

    private fun sample(
        ts: Long,
        speedKmh: Double,
        lat: Double = 30.0,
        lon: Double = 120.0,
        bearing: Double? = 0.0,
    ): LapTelemetrySample = LapTelemetrySample(
        absoluteTsMs = ts,
        elapsedMsInLap = 0L,
        lat = lat,
        lon = lon,
        speedKmh = speedKmh,
        bearingDeg = bearing,
        accelerationG = null, // binary 缺字段：恒 null（反例锁定输入）
        flags = 0,
    )

    @Test
    fun `empty input returns empty frames`() {
        assertTrue(VideoOverlayTelemetry.buildFrames(emptyList()).isEmpty())
    }

    @Test
    fun `single sample returns one frame with zero G`() {
        val frames = VideoOverlayTelemetry.buildFrames(listOf(sample(1000, 50.0)))
        assertEquals(1, frames.size)
        assertEquals(0.0, frames[0].lonG, 1e-9)
        assertEquals(0.0, frames[0].latG, 1e-9)
        assertEquals(50.0, frames[0].speedKmh, 1e-9)
    }

    @Test
    fun `constant speed straight line gives near-zero G`() {
        // 恒速 50 km/h、bearing 恒 90°（直线），G 应 ≈ 0
        val samples = (0 until 10).map { i ->
            sample(1000L + i * 40L, 50.0, lat = 30.0 + i * 0.0001, lon = 120.0, bearing = 0.0)
        }
        val frames = VideoOverlayTelemetry.buildFrames(samples)
        frames.forEach {
            assertTrue("expected |lonG| ≈ 0, got ${it.lonG}", abs(it.lonG) < 1e-6)
            assertTrue("expected |latG| ≈ 0, got ${it.latG}", abs(it.latG) < 1e-6)
        }
    }

    @Test
    fun `acceleration yields positive longitudinal G even though accelerationG input is null`() {
        // 反例核心：输入 accelerationG 全 null，仍重算出非 null 非零纵向 G。
        // 0→100 km/h 用 4s（10 帧 × 0.4s）：a = (100/3.6)/4 ≈ 6.94 m/s² ≈ 0.71G
        val samples = (0 until 11).map { i ->
            sample(1000L + i * 400L, speedKmh = i * 10.0)
        }
        // 输入恒 null 确认
        assertTrue(samples.all { it.accelerationG == null })
        val frames = VideoOverlayTelemetry.buildFrames(samples, smoothingWindow = 1)
        // 中段（稳定差分）纵向 G 应为正且量级合理（每帧 Δv=10km/h / 0.4s）
        val midG = frames[5].lonG
        // Δv=10km/h=2.778 m/s，dt=0.4s → a=6.94 m/s² → G=0.708
        assertTrue("expected lonG ≈ 0.71, got $midG", abs(midG - 0.708) < 0.05)
        assertNotNull(midG)
    }

    @Test
    fun `deceleration yields negative longitudinal G`() {
        val samples = (0 until 11).map { i ->
            sample(1000L + i * 400L, speedKmh = 100.0 - i * 10.0)
        }
        val frames = VideoOverlayTelemetry.buildFrames(samples, smoothingWindow = 1)
        assertTrue("expected lonG < 0 on decel, got ${frames[5].lonG}", frames[5].lonG < -0.5)
    }

    @Test
    fun `bearing change yields positive lateral G magnitude`() {
        // 恒速 100 km/h，bearing 每帧 +10°（持续右转）→ 横向 G 应有显著非零量级
        val samples = (0 until 10).map { i ->
            sample(1000L + i * 200L, speedKmh = 100.0, bearing = (i * 10.0) % 360.0)
        }
        val frames = VideoOverlayTelemetry.buildFrames(samples, smoothingWindow = 1)
        // v=27.78 m/s，ω=10°/0.2s=0.873 rad/s → a=24.2 m/s² → G≈2.47（量级显著非零即可）
        assertTrue("expected |latG| > 0.5 on turn, got ${frames[5].latG}", abs(frames[5].latG) > 0.5)
    }

    @Test
    fun `bearing crossing 360 boundary does not produce spurious huge G`() {
        // 359°→1° 真实变化 +2°，归一化后横向 G 应很小（不是 -358° 的巨值）
        val samples = listOf(
            sample(1000, 100.0, bearing = 359.0),
            sample(1200, 100.0, bearing = 1.0),
        )
        val frames = VideoOverlayTelemetry.buildFrames(samples, smoothingWindow = 1)
        // +2° / 0.2s = 10°/s → 小量级；若没归一化会是 -358°/0.2s 的巨值
        assertTrue("expected small |latG| across 360 boundary, got ${frames[1].latG}", abs(frames[1].latG) < 1.0)
    }

    @Test
    fun `null bearing yields zero lateral G`() {
        val samples = listOf(
            sample(1000, 100.0, bearing = null),
            sample(1200, 100.0, bearing = null),
        )
        val frames = VideoOverlayTelemetry.buildFrames(samples, smoothingWindow = 1)
        assertEquals(0.0, frames[1].latG, 1e-9)
    }

    @Test
    fun `resolveCurrentLap inside window returns lapNumber and elapsed`() {
        val windows = listOf(
            VideoOverlayTelemetry.LapWindow(lapNumber = 1, lapStartWallClock = 1000, lapEndWallClock = 2000),
            VideoOverlayTelemetry.LapWindow(lapNumber = 2, lapStartWallClock = 2000, lapEndWallClock = 3000),
        )
        val r = VideoOverlayTelemetry.resolveCurrentLap(2500, windows)
        assertNotNull(r)
        assertEquals(2, r!!.lapNumber)
        assertEquals(500L, r.currentLapElapsedMs)
    }

    @Test
    fun `resolveCurrentLap between laps returns null`() {
        val windows = listOf(
            VideoOverlayTelemetry.LapWindow(lapNumber = 1, lapStartWallClock = 1000, lapEndWallClock = 2000),
            VideoOverlayTelemetry.LapWindow(lapNumber = 2, lapStartWallClock = 5000, lapEndWallClock = 6000),
        )
        // 3000 落在第 1 圈结束(2000) 与第 2 圈开始(5000) 之间的 pit gap → null
        assertNull(VideoOverlayTelemetry.resolveCurrentLap(3000, windows))
    }

    @Test
    fun `resolveCurrentLap with empty windows returns null`() {
        assertNull(VideoOverlayTelemetry.resolveCurrentLap(1500, emptyList()))
    }

    @Test
    fun `buildReferenceFromSamples and computeDeltaMs give near-zero delta on same trajectory`() {
        // 直线 best 圈：100 点、40ms/点
        val best = (0 until 100).map { i ->
            sample(1000L + i * 40L, speedKmh = 50.0, lat = 30.0 + i * 0.0001, lon = 120.0, bearing = 0.0)
        }
        val ref = VideoOverlayTelemetry.buildReferenceFromSamples(best, lapStartWallClock = 1000, lapDurationMs = 4000)
        assertNotNull(ref)
        // 当前点 = best 第 50 帧位置 + 同 elapsed → delta ≈ 0
        val frame50 = best[50]
        val delta = VideoOverlayTelemetry.computeDeltaMs(
            reference = ref!!,
            currentLapElapsedMs = frame50.absoluteTsMs - 1000,
            currentLat = frame50.lat,
            currentLon = frame50.lon,
        )
        assertNotNull(delta)
        assertTrue("expected |delta| ≤ 50ms, got $delta", abs(delta!!) <= 50)
    }

    @Test
    fun `buildReferenceFromSamples returns null for fewer than 2 samples`() {
        assertNull(VideoOverlayTelemetry.buildReferenceFromSamples(emptyList(), 1000, 4000))
        assertNull(VideoOverlayTelemetry.buildReferenceFromSamples(listOf(sample(1000, 50.0)), 1000, 4000))
    }
}
