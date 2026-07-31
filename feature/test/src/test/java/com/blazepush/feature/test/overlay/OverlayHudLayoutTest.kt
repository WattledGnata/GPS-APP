package com.blazepush.feature.test.overlay

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayHudLayoutTest {
    @Test
    fun everyStyle_staysInsideLandscapeFrame() {
        VideoOverlayStyle.entries.forEach { style ->
            val layout = OverlayHudLayout.calculate(style, 1920f, 1080f)
            listOf(layout.speed, layout.timing, layout.gForce, layout.map).forEach { rect ->
                assertTrue("$style left", rect.left >= 0f)
                assertTrue("$style top", rect.top >= 0f)
                assertTrue("$style right", rect.right <= 1920f)
                assertTrue("$style bottom", rect.bottom <= 1080f)
                assertTrue("$style width", rect.width > 0f)
                assertTrue("$style height", rect.height > 0f)
            }
        }
    }

    @Test
    fun threeStyles_haveDistinctContainerGeometry() {
        val flat = OverlayHudLayout.calculate(VideoOverlayStyle.FLAT, 1280f, 720f)
        val rail = OverlayHudLayout.calculate(VideoOverlayStyle.RAIL, 1280f, 720f)
        val mechanical = OverlayHudLayout.calculate(VideoOverlayStyle.MECHANICAL, 1280f, 720f)

        assertTrue(flat.container == null)
        assertNotEquals(rail.container, mechanical.container)
        assertTrue(rail.speed.top > flat.speed.top)
        assertTrue(mechanical.speed.top > flat.speed.top)
    }

    @Test
    fun wideFrame_anchorsHudToTheEdges() {
        val width = 2800f
        val height = 1260f
        VideoOverlayStyle.entries.forEach { style ->
            val layout = OverlayHudLayout.calculate(style, width, height)
            if (style == VideoOverlayStyle.FLAT) {
                assertTrue("$style top-left", layout.speed.left <= 25f && layout.speed.top <= 25f)
                assertTrue("$style bottom-right", layout.map.right >= width - 25f && layout.map.bottom >= height - 25f)
            } else {
                val container = layout.container ?: error("$style must have an edge container")
                assertTrue("$style left edge", container.left <= 25f)
                assertTrue("$style right edge", container.right >= width - 25f)
                assertTrue("$style bottom edge", container.bottom >= height - 25f)
            }
        }
    }
}
