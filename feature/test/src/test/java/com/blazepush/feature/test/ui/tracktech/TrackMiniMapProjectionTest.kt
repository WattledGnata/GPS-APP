// @IgnoreFormatCheck
package com.blazepush.feature.test.ui.tracktech

import com.blazepush.feature.test.model.track.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * @author CC
 * @description verify minimap equirectangular projection keeps aspect ratio + boundary clamp + degrade on <2 points
 * @date 2026-05-31
 */
class TrackMiniMapProjectionTest {

    @Test
    fun `fewer than 2 points returns null (degrade not crash)`() {
        assertNull(TrackMiniMapProjection.project(emptyList(), 30.0, 120.0, 100f, 100f, 6f))
        assertNull(
            TrackMiniMapProjection.project(
                listOf(GeoPoint(30.0, 120.0)),
                30.0,
                120.0,
                100f,
                100f,
                6f,
            ),
        )
    }

    @Test
    fun `zero-size canvas returns null`() {
        val pts = listOf(GeoPoint(30.0, 120.0), GeoPoint(30.001, 120.001))
        assertNull(TrackMiniMapProjection.project(pts, 30.0, 120.0, 0f, 100f, 6f))
    }

    @Test
    fun `projected points stay within canvas bounds`() {
        val pts = listOf(
            GeoPoint(30.000, 120.000),
            GeoPoint(30.002, 120.000),
            GeoPoint(30.002, 120.002),
            GeoPoint(30.000, 120.002),
        )
        val w = 200f
        val h = 200f
        val pad = 6f
        val proj = TrackMiniMapProjection.project(pts, 30.001, 120.001, w, h, pad)
        assertNotNull(proj)
        proj!!.polyline.forEach {
            assertTrue("x in bounds: ${it.x}", it.x in 0f..w)
            assertTrue("y in bounds: ${it.y}", it.y in 0f..h)
        }
        val cur = proj.current
        assertNotNull(cur)
        assertTrue(cur!!.x in 0f..w)
        assertTrue(cur.y in 0f..h)
    }

    @Test
    fun `square track keeps aspect ratio (not stretched)`() {
        // 正方形赛道（在赤道附近 lat≈0 时 cos≈1，lon/lat 跨度相等）→ 投影后画布上也应近似正方形
        val pts = listOf(
            GeoPoint(0.000, 0.000),
            GeoPoint(0.002, 0.000),
            GeoPoint(0.002, 0.002),
            GeoPoint(0.000, 0.002),
        )
        // 用非正方形画布验证等比缩放：内容应居中且 x/y 跨度相等
        val proj = TrackMiniMapProjection.project(pts, null, null, 300f, 100f, 0f)!!
        val xs = proj.polyline.map { it.x }
        val ys = proj.polyline.map { it.y }
        val spanX = xs.max() - xs.min()
        val spanY = ys.max() - ys.min()
        // 等比：正方形赛道投影后画布上 x/y 跨度应近似相等（容差 1px）
        assertTrue("aspect kept: spanX=$spanX spanY=$spanY", abs(spanX - spanY) < 1f)
    }

    @Test
    fun `northernmost point maps to top (y smaller)`() {
        // 正北朝上：纬度大的点 y 应更小（画布上方）
        val south = GeoPoint(30.000, 120.000)
        val north = GeoPoint(30.002, 120.000)
        val pts = listOf(south, north)
        val proj = TrackMiniMapProjection.project(pts, null, null, 100f, 100f, 0f)!!
        // polyline[0]=south, polyline[1]=north；north 的 y 应 < south 的 y
        assertTrue("north on top: ${proj.polyline[1].y} < ${proj.polyline[0].y}", proj.polyline[1].y < proj.polyline[0].y)
    }

    @Test
    fun `null current lat-lon yields null current dot but valid polyline`() {
        val pts = listOf(GeoPoint(30.0, 120.0), GeoPoint(30.001, 120.001))
        val proj = TrackMiniMapProjection.project(pts, null, null, 100f, 100f, 6f)
        assertNotNull(proj)
        assertNull(proj!!.current)
        assertEquals(2, proj.polyline.size)
    }
}
