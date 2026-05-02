package com.blazepush.feature.test.usecase

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs

/**
 * @author CC
 * @description verify local plane projection produces meter-scale x/y for typical track GPS deltas
 * @date 2026-05-02
 */
class LocalPlaneProjectionTest {

    @Test
    fun `same point projects to origin`() {
        val (x, y) = LocalPlaneProjection.toMeters(30.0, 120.0, 30.0, 120.0)
        assertEquals(0f, x, 1e-3f)
        assertEquals(0f, y, 1e-3f)
    }

    @Test
    fun `longitude 0_001 degree at lat 30 maps to about 96_5 meters`() {
        val (x, y) = LocalPlaneProjection.toMeters(30.0, 120.0, 30.0, 120.001)
        // cos(30°) × 111_320 × 0.001 ≈ 96.41
        assertTrue("expected ~96.4m, got x=$x", abs(x - 96.4f) < 0.5f)
        assertEquals(0f, y, 1e-3f)
    }

    @Test
    fun `latitude 0_001 degree maps to about 111_3 meters`() {
        val (x, y) = LocalPlaneProjection.toMeters(30.0, 120.0, 30.001, 120.0)
        // 111_320 × 0.001 = 111.32
        assertEquals(0f, x, 1e-3f)
        assertTrue("expected ~111.3m, got y=$y", abs(y - 111.32f) < 0.5f)
    }

    @Test
    fun `negative direction produces negative meters`() {
        val (x, y) = LocalPlaneProjection.toMeters(30.0, 120.0, 29.999, 119.999)
        assertTrue("x should be negative, got $x", x < 0f)
        assertTrue("y should be negative, got $y", y < 0f)
    }

    private fun assertTrue(message: String, condition: Boolean) {
        if (!condition) throw AssertionError(message)
    }
}
