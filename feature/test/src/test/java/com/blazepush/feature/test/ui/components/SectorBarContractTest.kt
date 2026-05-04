package com.blazepush.feature.test.ui.components

import com.blazepush.feature.test.ui.components.mockSingleLap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SectorBarContractTest {

    @Test
    fun `mockSingleLap sectors - 3 equal width`() {
        val lap = mockSingleLap(n = 100, lapDurationMs = 60_000)
        val sectors = computeSectorBounds(lap.sectorBoundaries, lap.lapStartWallClock, lap.lapEndWallClock, 900f)
        assertEquals(3, sectors.size)
        // Each sector should be ~300px wide (900/3)
        assertEquals(300f, sectors[0].xEnd - sectors[0].xStart, 2f)
        assertEquals(300f, sectors[1].xEnd - sectors[1].xStart, 2f)
        assertEquals(300f, sectors[2].xEnd - sectors[2].xStart, 2f)
    }

    @Test
    fun `3 sector equal split`() {
        val lapStart = 1000L
        val lapEnd = 4000L
        val boundaries = listOf(1000L, 2000L, 3000L)
        val sectors = computeSectorBounds(boundaries, lapStart, lapEnd, 900f)

        assertEquals(3, sectors.size)
        assertEquals(0f, sectors[0].xStart, 1f)
        assertEquals(300f, sectors[0].xEnd, 1f)
        assertEquals(300f, sectors[1].xStart, 1f)
        assertEquals(600f, sectors[1].xEnd, 1f)
        assertEquals(600f, sectors[2].xStart, 1f)
        assertEquals(900f, sectors[2].xEnd, 1f)
    }

    @Test
    fun `cursor highlight - falls in second sector`() {
        val lapStart = 1000L
        val lapEnd = 4000L
        val boundaries = listOf(1000L, 2000L, 3000L)
        val cursorTs = 2500L // in sector 2 (2000-3000)
        val lapDuration = lapEnd - lapStart
        val cursorFraction = (cursorTs - lapStart).toFloat() / lapDuration
        assertTrue(cursorFraction in 0.33f..0.67f)
    }

    @Test
    fun `empty boundaries - single sector full lap`() {
        val sectors = computeSectorBounds(emptyList(), 1000L, 4000L, 900f)
        assertEquals(1, sectors.size)
        assertEquals(0f, sectors[0].xStart, 1f)
        assertEquals(900f, sectors[0].xEnd, 1f)
    }

    @Test
    fun `boundaries not starting at lapStart - still renders proportionally`() {
        val lapStart = 1000L
        val lapEnd = 4000L
        val boundaries = listOf(2000L) // doesn't start at lapStart
        val sectors = computeSectorBounds(boundaries, lapStart, lapEnd, 900f)
        assertTrue(sectors.isNotEmpty())
    }

    @Test
    fun `cursor before lapStart - fraction clamps to 0`() {
        val lapStart = 1000L
        val lapEnd = 4000L
        val cursorTs = 500L
        val fraction = ((cursorTs - lapStart).toFloat() / (lapEnd - lapStart)).coerceIn(0f, 1f)
        assertEquals(0f, fraction, 0.001f)
    }

    @Test
    fun `cursor after lapEnd - fraction clamps to 1`() {
        val lapStart = 1000L
        val lapEnd = 4000L
        val cursorTs = 5000L
        val fraction = ((cursorTs - lapStart).toFloat() / (lapEnd - lapStart)).coerceIn(0f, 1f)
        assertEquals(1f, fraction, 0.001f)
    }

    @Test
    fun `lapDuration equals 0 - no division by zero`() {
        val sectors = computeSectorBounds(listOf(1000L), 1000L, 1000L, 900f)
        assertTrue(sectors.isNotEmpty())
    }
}
