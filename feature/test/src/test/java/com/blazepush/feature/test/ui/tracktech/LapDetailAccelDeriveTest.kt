// @IgnoreFormatCheck
package com.blazepush.feature.test.ui.tracktech

import com.blazepush.core.domain.model.LapTelemetrySample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R1 accelerationG UI 层派生纯函数 [deriveAccelerationG] 的单测。
 *
 * pure JVM JUnit4，不依赖 Compose / Robolectric / Android Context。
 *
 * 锁定 spec scenario：
 * - case A：N >= 5 sample（accelerationG 全 null 输入）→ 派生后全非 null（喂 AccelTimeChart 不再 "NO ACCEL DATA"）
 * - case B：派生后第 i 个 sample.absoluteTsMs == 原始第 i 个 absoluteTsMs（不改时间戳 → 锁游标精确相等命中）
 * - case C：单 sample（N=1）→ AccelerationSmoother 返回 [0.0] → accelerationG=0.0 非 null（边界）
 * - case 空：空列表 → 空列表
 *
 * @author CC
 * @description R1 accelerationG derive pure-function unit test
 * @date 2026-05-30
 */
class LapDetailAccelDeriveTest {

    private fun sample(ts: Long, speedKmh: Double): LapTelemetrySample = LapTelemetrySample(
        absoluteTsMs = ts,
        elapsedMsInLap = ts - 1_000_000L,
        lat = 31.0,
        lon = 121.0,
        speedKmh = speedKmh,
        bearingDeg = null,
        accelerationG = null,
        flags = 0,
    )

    @Test
    fun `case A - N greater equal 5 with null accelerationG derives all non-null`() {
        val samples = (0 until 6).map { sample(1_000_000L + it * 40L, 50.0 + it * 5.0) }
        // 前置断言输入确实全 null（reader 语义）
        assertTrue("input precondition: all accelerationG null", samples.all { it.accelerationG == null })

        val derived = deriveAccelerationG(samples)

        assertEquals("derived size == input size", samples.size, derived.size)
        assertTrue(
            "every derived sample.accelerationG MUST be non-null (喂 AccelTimeChart 不再 NO ACCEL DATA)",
            derived.all { it.accelerationG != null },
        )
    }

    @Test
    fun `case B - derived preserves absoluteTsMs per index for cursor exact-match`() {
        val samples = (0 until 8).map { sample(2_000_000L + it * 40L, 30.0 + it * 8.0) }

        val derived = deriveAccelerationG(samples)

        derived.forEachIndexed { i, s ->
            assertEquals(
                "derived[$i].absoluteTsMs MUST equal input[$i].absoluteTsMs (游标精确相等命中依赖不改时间戳)",
                samples[i].absoluteTsMs,
                s.absoluteTsMs,
            )
            // 其余字段也不变
            assertEquals(samples[i].speedKmh, s.speedKmh, 0.0)
            assertEquals(samples[i].elapsedMsInLap, s.elapsedMsInLap)
            assertEquals(samples[i].lat, s.lat, 0.0)
            assertEquals(samples[i].lon, s.lon, 0.0)
        }
    }

    @Test
    fun `case C - single sample N1 derives accelerationG 0_0 non-null`() {
        val samples = listOf(sample(3_000_000L, 100.0))

        val derived = deriveAccelerationG(samples)

        assertEquals(1, derived.size)
        assertNotNull("N=1 派生 accelerationG MUST 非 null（AccelerationSmoother 返回 [0.0]）", derived[0].accelerationG)
        assertEquals("N=1 accelerationG == 0.0", 0.0, derived[0].accelerationG!!, 0.0)
    }

    @Test
    fun `case empty - empty list derives empty list`() {
        val derived = deriveAccelerationG(emptyList())
        assertTrue("empty input → empty output", derived.isEmpty())
    }
}
