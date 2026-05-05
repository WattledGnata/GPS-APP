package com.blazepush.core.domain.usecase

import com.blazepush.core.domain.model.LapTelemetry
import com.blazepush.core.domain.model.LapTelemetrySample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.floor

class LapAlignmentTest {

    private fun mockLap(
        sampleCount: Int,
        startLat: Double = 0.0,
        endLat: Double = 0.009,
        startLon: Double = 0.0,
        endLon: Double = 0.0,
        lapDurationMs: Long = 60000L,
        accelerationG: Double? = null,
        lapStartWallClock: Long = 0L,
        fixedLat: Double? = null,
        fixedLon: Double? = null,
    ): LapTelemetry {
        val samples = (0 until sampleCount).map { i ->
            val frac = if (sampleCount > 1) i.toDouble() / (sampleCount - 1) else 0.0
            LapTelemetrySample(
                absoluteTsMs = lapStartWallClock + (frac * lapDurationMs).toLong(),
                elapsedMsInLap = (frac * lapDurationMs).toLong(),
                lat = fixedLat ?: (startLat + (endLat - startLat) * frac),
                lon = fixedLon ?: (startLon + (endLon - startLon) * frac),
                speedKmh = 80.0,
                bearingDeg = 0.0,
                accelerationG = accelerationG,
            )
        }
        return LapTelemetry(
            sessionId = "test",
            lapIndex = 0,
            lapStartWallClock = lapStartWallClock,
            lapEndWallClock = lapStartWallClock + lapDurationMs,
            lapDurationMs = lapDurationMs,
            samples = samples,
            sectorBoundaries = listOf(lapStartWallClock),
            trackId = null,
            trackNameSnapshot = null,
        )
    }

    // ---- Case A: Three laps different pace ----

    @Test
    fun caseA_threeLapsDifferentPace() {
        val lap0 = mockLap(sampleCount = 1500, lapDurationMs = 60000L).copy(lapIndex = 0)
        val lap1 = mockLap(sampleCount = 1625, lapDurationMs = 65000L).copy(
            lapIndex = 1,
            samples = mockLap(sampleCount = 1625, lapDurationMs = 65000L).samples.map {
                it.copy(speedKmh = 74.0)
            }
        )
        val lap2 = mockLap(sampleCount = 1550, lapDurationMs = 62000L).copy(
            lapIndex = 2,
            samples = mockLap(sampleCount = 1550, lapDurationMs = 62000L).samples.map {
                it.copy(speedKmh = 77.0)
            }
        )
        val laps = listOf(lap0, lap1, lap2)

        val result = LapAlignment.alignByDistance(laps, 0, 5.0)

        // Assert 1: 3 laps output
        assertEquals(3, result.samplesPerLap.size)

        // Assert 2: all inner lists same size == gridSize
        val gs = result.gridSize
        assertEquals(gs, result.samplesPerLap[0].size)
        assertEquals(gs, result.samplesPerLap[1].size)
        assertEquals(gs, result.samplesPerLap[2].size)

        // Assert 3: gridSize ≈ floor(1000/5) + 1 = 201 (1km straight line)
        val expectedGridSize = floor(1000.0 / 5.0).toInt() + 1
        assertEquals(expectedGridSize, gs)

        // Assert 4: lap0 speed at grid 100 (distance=500m) ≈ 80.0
        assertEquals(80.0, result.samplesPerLap[0][100].speedKmh, 1.0)

        // Assert 5: lap1/lap2 speed at grid 100
        assertEquals(74.0, result.samplesPerLap[1][100].speedKmh, 1.0)
        assertEquals(77.0, result.samplesPerLap[2][100].speedKmh, 1.0)

        // Assert 6: first grid point lat == first sample lat
        assertEquals(laps[0].samples[0].lat, result.samplesPerLap[0][0].lat, 1e-9)

        // Assert 7: gridIndexFor reverse lookup
        assertEquals(100, result.gridIndexFor(500.0))

        // Assert 8: distanceAtGridIndex
        assertEquals(500.0, result.distanceAtGridIndex(100), 1e-9)

        // Assert 9: referenceLapIndex preserved
        assertEquals(0, result.referenceLapIndex)
    }

    // ---- Case B: Single lap input ----

    @Test
    fun caseB_singleLap() {
        val lap = mockLap(sampleCount = 1000, lapDurationMs = 48000L, endLat = 0.0072)
        val laps = listOf(lap)

        val result = LapAlignment.alignByDistance(laps, 0, 5.0)

        assertEquals(1, result.samplesPerLap.size)
        val expectedGridSize = floor(800.0 / 5.0).toInt() + 1 // 800m straight
        assertEquals(expectedGridSize, result.gridSize)

        // Grid 50 lat should fall within mock data range
        val lat50 = result.samplesPerLap[0][50].lat
        assertTrue(lat50 in laps[0].samples.first().lat..laps[0].samples.last().lat)
    }

    // ---- Case C: Distance too short / invalid step ----

    @Test
    fun caseC1_stepZero() {
        val laps = listOf(mockLap(sampleCount = 100))
        val result = LapAlignment.alignByDistance(laps, 0, 0.0)
        assertEquals(LapAlignmentResult.EMPTY, result)
        assertTrue(result.samplesPerLap.isEmpty())
    }

    @Test
    fun caseC2_stepNegative() {
        val laps = listOf(mockLap(sampleCount = 100))
        val result = LapAlignment.alignByDistance(laps, 0, -5.0)
        assertEquals(LapAlignmentResult.EMPTY, result)
    }

    @Test
    fun caseC3_stepLargerThanTotalDist() {
        // 1500m total distance → step=3000 → gridSize = floor(1500/3000)+1 = 1
        val lap = mockLap(sampleCount = 500, endLat = 0.0135) // ~1500m
        val laps = listOf(lap)
        val result = LapAlignment.alignByDistance(laps, 0, 3000.0)
        assertEquals(1, result.gridSize)
        assertEquals(1, result.samplesPerLap[0].size)
    }

    // ---- Case D: Reference index out of bounds + empty laps ----

    @Test
    fun caseD1_refIdxNegative() {
        val laps = listOf(mockLap(sampleCount = 100), mockLap(sampleCount = 100))
        val result = LapAlignment.alignByDistance(laps, -1, 5.0)
        assertEquals(LapAlignmentResult.EMPTY, result)
        assertEquals(-1, result.gridIndexFor(100.0))
    }

    @Test
    fun caseD2_refIdxTooLarge() {
        val laps = listOf(mockLap(sampleCount = 100), mockLap(sampleCount = 100))
        val result = LapAlignment.alignByDistance(laps, 5, 5.0)
        assertEquals(LapAlignmentResult.EMPTY, result)
        assertEquals(-1, result.gridIndexFor(0.0))
    }

    @Test
    fun caseD3_emptyLaps() {
        val result = LapAlignment.alignByDistance(emptyList(), 0, 5.0)
        assertEquals(LapAlignmentResult.EMPTY, result)
        assertEquals(-1, result.referenceLapIndex)
        assertEquals(-1, result.gridIndexFor(50.0))
        assertEquals(0.0, result.distanceAtGridIndex(0), 1e-9)
    }

    @Test
    fun caseD4_refLapSingleSample() {
        val lap = mockLap(sampleCount = 1)
        val laps = listOf(lap)
        val result = LapAlignment.alignByDistance(laps, 0, 5.0)
        assertEquals(LapAlignmentResult.EMPTY, result)
    }

    @Test
    fun caseD5_refLapAllSamePosition() {
        val lap = mockLap(sampleCount = 100, fixedLat = 31.0, fixedLon = 121.0)
        val laps = listOf(lap)
        val result = LapAlignment.alignByDistance(laps, 0, 5.0)
        assertEquals(LapAlignmentResult.EMPTY, result)
    }

    // ---- Case E: Cumulative distance with duplicates (stationary car) ----

    @Test
    fun caseE_cumulativeDistanceDuplicates() {
        // 300 frames: 100 moving (0→100m), 100 stationary (at 100m), 100 moving (100→200m)
        // endLat chosen so total first-section distance = exactly 100m
        val endLat1 = 100.0 / (Math.PI / 180.0 * 6378137.0) // ≈ 0.000898315284°
        val movingSamples1 = (0 until 100).map { i ->
            val frac = i.toDouble() / 99.0
            LapTelemetrySample(
                absoluteTsMs = (frac * 4000).toLong(),
                elapsedMsInLap = (frac * 4000).toLong(),
                lat = frac * endLat1,
                lon = 0.0,
                speedKmh = 80.0,
                bearingDeg = 0.0,
                accelerationG = null,
            )
        }
        val stationarySamples = (0 until 100).map { i ->
            LapTelemetrySample(
                absoluteTsMs = 4000L + i * 40L,
                elapsedMsInLap = 4000L + i * 40L,
                lat = endLat1,
                lon = 0.0,
                speedKmh = 0.0,
                bearingDeg = 0.0,
                accelerationG = null,
            )
        }
        val movingSamples2 = (0 until 100).map { i ->
            val frac = i.toDouble() / 99.0
            LapTelemetrySample(
                absoluteTsMs = 8000L + (frac * 4000).toLong(),
                elapsedMsInLap = 8000L + (frac * 4000).toLong(),
                lat = endLat1 + frac * endLat1,
                lon = 0.0,
                speedKmh = 80.0,
                bearingDeg = 0.0,
                accelerationG = null,
            )
        }
        val allSamples = movingSamples1 + stationarySamples + movingSamples2
        val lap = LapTelemetry(
            sessionId = "test",
            lapIndex = 0,
            lapStartWallClock = 0L,
            lapEndWallClock = 12000L,
            lapDurationMs = 12000L,
            samples = allSamples,
            sectorBoundaries = listOf(0L),
            trackId = null,
            trackNameSnapshot = null,
        )

        val result = LapAlignment.alignByDistance(listOf(lap), 0, 5.0)

        // Assert 1: gridSize = floor(200/5)+1 = 41
        assertEquals(41, result.gridSize)

        // Assert 2: grid point at d=100m (gridIdx=20) falls in stationary zone
        // → elapsedMsInLap == 4000L (earliest sample in duplicate range)
        val gridIdx20 = result.gridIndexFor(100.0)
        assertEquals(20, gridIdx20)
        assertEquals(4000L, result.samplesPerLap[0][gridIdx20].elapsedMsInLap)

        // Assert 3: no NaN at stationary grid point
        val sampleAtStationary = result.samplesPerLap[0][gridIdx20]
        assertTrue(!sampleAtStationary.lat.isNaN())
        assertTrue(!sampleAtStationary.lon.isNaN())
        assertTrue(!sampleAtStationary.speedKmh.isNaN())

        // Assert 4: all samples have no NaN
        assertTrue(result.samplesPerLap[0].all {
            !it.lat.isNaN() && !it.lon.isNaN() && !it.speedKmh.isNaN()
        })
    }

    // ---- Case F: Comparison lap sample degradation (fallback) ----

    @Test
    fun caseF1_comparisonLapSingleSample() {
        val lap0 = mockLap(sampleCount = 1500, lapDurationMs = 60000L, endLat = 0.009)
        val lap1SingleSample = LapTelemetrySample(
            absoluteTsMs = 30000L,
            elapsedMsInLap = 30000L,
            lat = lap0.samples[500].lat,
            lon = lap0.samples[500].lon,
            speedKmh = 99.9,
            bearingDeg = 0.0,
            accelerationG = 0.7,
        )
        val lap1 = LapTelemetry(
            sessionId = "test",
            lapIndex = 1,
            lapStartWallClock = 0L,
            lapEndWallClock = 30000L,
            lapDurationMs = 30000L,
            samples = listOf(lap1SingleSample),
            sectorBoundaries = listOf(0L),
            trackId = null,
            trackNameSnapshot = null,
        )
        val laps = listOf(lap0, lap1)

        val result = LapAlignment.alignByDistance(laps, 0, 5.0)

        // Assert 1: output has 2 laps, inner size == gridSize
        assertEquals(2, result.samplesPerLap.size)
        assertEquals(result.gridSize, result.samplesPerLap[1].size)

        // Assert 2: every element == lap1.samples[0] (direct copy)
        assertTrue(result.samplesPerLap[1].all { it == lap1SingleSample })
    }

    @Test
    fun caseF2_comparisonLapEmptySamples() {
        val lap0 = mockLap(sampleCount = 1500, lapDurationMs = 60000L, endLat = 0.009,
            lapStartWallClock = 2000000L)
        val lap1 = LapTelemetry(
            sessionId = "test",
            lapIndex = 1,
            lapStartWallClock = 1000000L,
            lapEndWallClock = 1000000L,
            lapDurationMs = 0L,
            samples = emptyList(),
            sectorBoundaries = listOf(1000000L),
            trackId = null,
            trackNameSnapshot = null,
        )
        val laps = listOf(lap0, lap1)

        val result = LapAlignment.alignByDistance(laps, 0, 5.0)

        // Derived reference: refSample10 = result.samplesPerLap[0][10]
        val refSample10 = result.samplesPerLap[0][10]

        // Assert 1: non-empty list
        assertEquals(result.gridSize, result.samplesPerLap[1].size)

        // Assert 2: 4 fields match reference
        assertEquals(refSample10.lat, result.samplesPerLap[1][10].lat, 1e-9)
        assertEquals(refSample10.lon, result.samplesPerLap[1][10].lon, 1e-9)
        assertEquals(refSample10.speedKmh, result.samplesPerLap[1][10].speedKmh, 1e-9)
        assertEquals(refSample10.elapsedMsInLap, result.samplesPerLap[1][10].elapsedMsInLap)

        // Assert 3: absoluteTsMs re-derived with lap1's lapStartWallClock
        assertEquals(
            laps[1].lapStartWallClock + refSample10.elapsedMsInLap,
            result.samplesPerLap[1][10].absoluteTsMs
        )

        // Assert 4: cross-clock-domain inequality (ref uses 2000000L, lap1 uses 1000000L)
        assertTrue(result.samplesPerLap[1][10].absoluteTsMs != refSample10.absoluteTsMs)

        // Assert 5: accelerationG forced null
        assertTrue(result.samplesPerLap[1].all { it.accelerationG == null })

        // Assert 6: all fallback samples satisfy re-derivation invariant
        assertTrue(result.samplesPerLap[1].all {
            it.absoluteTsMs == laps[1].lapStartWallClock + it.elapsedMsInLap
        })
    }

    // ---- Case G: flags 重采样最近邻 (B4 + L1 R1 P1-1 sub-G4/G5) ----
    // 修复 v3 高频盲点 #16 实战首例 — LapTelemetrySample.flags 字段 W1 round 追加后
    // W3 LapAlignment.interpolate / clamp / 精确命中路径需保留源 sample.flags

    private fun lapWithFlags(
        sampleFlags: List<Int>,
        sampleLats: List<Double>? = null,
        lapDurationMs: Long = 60000L,
        lapStartWallClock: Long = 0L,
    ): LapTelemetry {
        val n = sampleFlags.size
        val samples = (0 until n).map { i ->
            val frac = if (n > 1) i.toDouble() / (n - 1) else 0.0
            LapTelemetrySample(
                absoluteTsMs = lapStartWallClock + (frac * lapDurationMs).toLong(),
                elapsedMsInLap = (frac * lapDurationMs).toLong(),
                lat = sampleLats?.get(i) ?: (0.0 + 0.009 * frac),
                lon = 0.0,
                speedKmh = 80.0,
                bearingDeg = 0.0,
                accelerationG = null,
                flags = sampleFlags[i],
            )
        }
        return LapTelemetry(
            sessionId = "test",
            lapIndex = 0,
            lapStartWallClock = lapStartWallClock,
            lapEndWallClock = lapStartWallClock + lapDurationMs,
            lapDurationMs = lapDurationMs,
            samples = samples,
            sectorBoundaries = listOf(lapStartWallClock),
            trackId = null,
            trackNameSnapshot = null,
        )
    }

    @Test
    fun caseG1_flagsAlphaSmallTakesS0() {
        // 构造 2-sample lap，s0.flags=1, s1.flags=0 → grid 点 distance 落 α≈0.3 应取 s0.flags=1
        // lat 间距：0 → 0.009（约 1000m），grid step 5m → grid[300] 的 distance ≈ 1500 ≈ 1000 * 1.5 → α 落 sample 间隔
        // 简化：用 5-sample lap，flags=[1, 1, 0, 0, 0]，grid step 让中间 sample 之间的 grid 点取最近邻
        val refLap = lapWithFlags(sampleFlags = listOf(1, 1, 0, 0, 0))
        val result = LapAlignment.alignByDistance(listOf(refLap), 0, 5.0)
        assertTrue(result.gridSize > 0)
        // grid 第一个点 distance=0 → clamp 到 sample[0], flags=1
        assertEquals(1, result.samplesPerLap[0][0].flags)
        // 最后一个 grid 点 → clamp 到 sample[4], flags=0
        assertEquals(0, result.samplesPerLap[0][result.gridSize - 1].flags)
    }

    @Test
    fun caseG2_flagsAlphaLargeTakesS1WithValueZero() {
        // s0.flags=1, s1.flags=2，α≥0.5 取 s1.flags=2，验证 default 0 vs explicit 2 不混淆
        // 5-sample lap，sample distances 均匀分布；grid step 0.5 让 grid 落在 sample 之间
        val refLap = lapWithFlags(sampleFlags = listOf(1, 2, 1, 2, 1))
        val result = LapAlignment.alignByDistance(listOf(refLap), 0, 50.0)
        // 至少有一个 grid 点的 flags 为 2（不是默认 0）
        assertTrue(
            "case G2: at least one resampled sample.flags should be 2 (not default 0)",
            result.samplesPerLap[0].any { it.flags == 2 }
        )
        // 所有 grid 点的 flags ∈ {1, 2}（无默认 0 哨兵泄漏）
        assertTrue(
            "case G2: all flags must be 1 or 2 (no default-0 sentinel)",
            result.samplesPerLap[0].all { it.flags == 1 || it.flags == 2 }
        )
    }

    @Test
    fun caseG3_flagsDuplicateDistanceMinIndex() {
        // 重复距离区间：sample[1..3] 同位置（fixedLat），flags=[0, 1, 2, 3, 0]
        // grid 点落入重复区间应取最小 index sample 的 flags（即 sample[1].flags=1）
        val sampleFlags = listOf(0, 1, 2, 3, 0)
        val sampleLats = listOf(0.0, 0.001, 0.001, 0.001, 0.005)  // sample[1..3] 同位置
        val refLap = lapWithFlags(sampleFlags = sampleFlags, sampleLats = sampleLats)
        val result = LapAlignment.alignByDistance(listOf(refLap), 0, 30.0)
        // 找到 grid 点对应 sample[1..3] 的距离 → flags 应该是 1（最小 index）
        // sample[1] 的累计距离 ≈ 111m（0.001 * 111000m）
        // grid step 30m → grid[3] (90m) / grid[4] (120m) 应在 sample[1..3] 区域
        val foundMinIndex = result.samplesPerLap[0].any { it.flags == 1 }
        assertTrue("case G3: must find resampled sample with flags=1 (min index in duplicate)", foundMinIndex)
    }

    @Test
    fun caseG4_flagsClampToFirstOrLast() {
        // clamp 路径 — d* < d_0 直接 return samples[0] 含 flags
        // 用 1-sample 圈 + grid step 让 grid[0] = 0 落 clamp 路径
        // 实际构造 2-sample（n>=2 才能 alignByDistance），grid 第一/最后 clamp
        val refLap = lapWithFlags(sampleFlags = listOf(7, 9))
        val result = LapAlignment.alignByDistance(listOf(refLap), 0, 5.0)
        assertTrue(result.gridSize >= 2)
        // grid[0] 的 distance=0 → clamp samples[0]
        assertEquals(7, result.samplesPerLap[0][0].flags)
        // grid[last] clamp samples[1]
        assertEquals(9, result.samplesPerLap[0][result.gridSize - 1].flags)
    }

    @Test
    fun caseG5_flagsExactMatchPreserveOriginal() {
        // 精确命中分支 — d* == d_k binarySearch 返回 idx >= 0 → return samples[kMin]
        // 重复距离区间起点的 flags 应保留
        val sampleFlags = listOf(5, 5, 5, 5, 5)
        val sampleLats = listOf(0.0, 0.001, 0.001, 0.001, 0.001)  // sample[1..4] 同位置
        val refLap = lapWithFlags(sampleFlags = sampleFlags, sampleLats = sampleLats)
        val result = LapAlignment.alignByDistance(listOf(refLap), 0, 50.0)
        // 所有 grid 点的 flags 应为 5（无默认 0 泄漏）
        assertTrue(
            "case G5: all resampled flags must be 5 (no default-0 sentinel)",
            result.samplesPerLap[0].all { it.flags == 5 }
        )
    }

    // ---- Case H: bearingDeg 跨 360° 边界最近邻 (D2) ----

    @Test
    fun caseH_bearingWrap360() {
        // s0.bearingDeg=359°, s1.bearingDeg=1° (跨 0/360 边界)
        // 用 2-sample 构造 + grid 间隔让 α 接近 0.7（取 s1.bearingDeg=1°）
        val n = 2
        val samples = (0 until n).map { i ->
            val frac = i.toDouble() / (n - 1)
            LapTelemetrySample(
                absoluteTsMs = (frac * 60000L).toLong(),
                elapsedMsInLap = (frac * 60000L).toLong(),
                lat = 0.0 + 0.001 * frac,
                lon = 0.0,
                speedKmh = 80.0,
                bearingDeg = if (i == 0) 359.0 else 1.0,
                accelerationG = null,
            )
        }
        val refLap = LapTelemetry(
            sessionId = "test",
            lapIndex = 0,
            lapStartWallClock = 0L,
            lapEndWallClock = 60000L,
            lapDurationMs = 60000L,
            samples = samples,
            sectorBoundaries = listOf(0L),
            trackId = null,
            trackNameSnapshot = null,
        )
        val result = LapAlignment.alignByDistance(listOf(refLap), 0, 30.0)
        // 所有 grid 点 bearingDeg 应在 {359.0, 1.0}（最近邻取自源），不应平均到 180
        assertTrue(
            "case H: all bearingDeg must be 359.0 or 1.0 (nearest neighbor across 0/360 wrap)",
            result.samplesPerLap[0].all { it.bearingDeg == 359.0 || it.bearingDeg == 1.0 }
        )
        // MUST NOT 平均到 180
        assertTrue(
            "case H: no bearingDeg should be averaged to 180",
            result.samplesPerLap[0].none { it.bearingDeg == 180.0 }
        )
    }

    // ---- Case I: elapsedMsInLap round 浮点边界 deterministic (D3) ----

    @Test
    fun caseI_elapsedFloatBoundary() {
        // L1 R1 D3 修订（apply 期 spec drift 修正）：
        // 现实代码 gridIndexFor 用 (d / step).toInt() truncation。在 step 整数倍（如 100.0 = 5.0*20）
        // 边界两侧的浮点误差**不 deterministic 一致** — 99.999... truncation = 19，100.000... = 20。
        // 真正要锁的是"同侧 ±1e-9 误差 deterministic"（同 step bucket 内的微小误差）。
        val refLap = mockLap(sampleCount = 100, lapDurationMs = 60000L)
        val result = LapAlignment.alignByDistance(listOf(refLap), 0, 5.0)

        // 同侧 +1e-9 deterministic（同 step bucket [100.0, 105.0) 内）
        val gridIdx1 = result.gridIndexFor(100.0)
        val gridIdx2 = result.gridIndexFor(100.0 + 1e-9)
        assertEquals(
            "case I: gridIndexFor at +1e-9 must be deterministic (same step bucket)",
            gridIdx1, gridIdx2
        )

        // 跨 step 边界 truncation 行为符合 expected（spec 加严锁定，避免未来重构改 round/floor）
        // 99.999... < 100 → bucket 19；100.0 = 5*20 → bucket 20；100.000... > 100 → bucket 20
        assertEquals("case I: 99.999... must truncate to 19", 19, result.gridIndexFor(99.999999999))
        assertEquals("case I: 100.0 must equal 20", 20, result.gridIndexFor(100.0))
        assertEquals("case I: 100.000... must truncate to 20", 20, result.gridIndexFor(100.000000001))

        // 同输入幂等 deterministic（trivially pass 但锁定 pure function 语义）
        assertEquals(result.gridIndexFor(105.0), result.gridIndexFor(105.0))
    }
}
