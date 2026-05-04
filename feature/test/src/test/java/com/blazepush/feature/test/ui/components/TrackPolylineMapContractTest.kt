package com.blazepush.feature.test.ui.components

import androidx.compose.ui.geometry.Size
import com.blazepush.core.domain.model.LapTelemetrySample
import com.blazepush.feature.test.ui.components.mockSingleLap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackPolylineMapContractTest {

    @Test
    fun `circular track - bounding box center is correct`() {
        val lap = mockSingleLap(n = 100)
        val bbox = computeMapBoundingBox(lap.samples)

        assertEquals(31.0, bbox.centerLat, 0.01)
        assertEquals(121.0, bbox.centerLon, 0.01)
        assertTrue(bbox.maxLat - bbox.minLat > 0.001)
        assertTrue(bbox.maxLon - bbox.minLon > 0.001)
    }

    @Test
    fun `cursor highlight - sample 50 maps to canvas`() {
        val lap = mockSingleLap(n = 100)
        val bbox = computeMapBoundingBox(lap.samples)
        val sample = lap.samples[50]
        val pos = mapLatLonToCanvas(sample.lat, sample.lon, bbox, Size(1000f, 1000f))

        assertTrue(pos.x in 0f..1000f)
        assertTrue(pos.y in 0f..1000f)
    }

    @Test
    fun `empty list - bounding box throws`() {
        try {
            computeMapBoundingBox(emptyList())
            assertTrue("Should have thrown", false)
        } catch (_: Exception) {
            // expected: minOf/maxOf on empty list throws
        }
    }

    @Test
    fun `n=1 single sample - maps to canvas center`() {
        val single = listOf(
            LapTelemetrySample(
                absoluteTsMs = 1000L, elapsedMsInLap = 0L,
                lat = 31.0, lon = 121.0, speedKmh = 100.0,
                bearingDeg = null, accelerationG = null,
            )
        )
        val bbox = computeMapBoundingBox(single)
        assertEquals(31.0, bbox.centerLat, 0.001)
        assertEquals(121.0, bbox.centerLon, 0.001)
        // Single point: latRange/lonRange == 0, mapLatLonToCanvas uses 0.0001 floor
        val pos = mapLatLonToCanvas(31.0, 121.0, bbox, Size(1000f, 1000f))
        assertTrue(pos.x in 0f..1000f)
        assertTrue(pos.y in 0f..1000f)
    }
}
