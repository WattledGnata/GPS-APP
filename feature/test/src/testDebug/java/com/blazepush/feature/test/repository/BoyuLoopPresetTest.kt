// 锁死 debug-only 预置赛道"成都天投泊寓环线"的契约，覆盖 OpenSpec change
// `add-debug-preset-track-boyu-loop` 中 ADDED Requirement
// "天投泊寓环线 Track 数据契约（debug variant only）" 的 4 个 scenario：
// (a) 顶层字段 (b) 4 sector + 1 startFinish gate 顺序 (c) referencePath 几何边界
// (d) referencePath 闭合度 ≤ 5m。
package com.blazepush.feature.test.repository

import com.blazepush.feature.test.model.track.GeoPoint
import com.blazepush.feature.test.model.track.TimingGateType
import com.blazepush.feature.test.model.track.TrackSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * @description Debug-only 预置赛道"成都天投泊寓环线"几何契约。与
 *   ExtraPresetTracksDebug.kt 内 `boyuLoopTrack` 写死值绑定；几何参数原始
 *   来源是 docs/tools/input/session_20260108_225454_天投泊寓环线.rcz 经
 *   decode_rcz_session.py 离线生成（详见 docs/design/rcz-format-decoding.md）。
 *   任何 drift（重新跑脚本 / 手工编辑 DSL）都会触发本测试失败，迫使工件
 *   同步更新。
 * @author CC (Claude Code)
 * @date 2026-05-01
 */
class BoyuLoopPresetTest {

    private val track = requireNotNull(PresetTrackCatalog().getTrack("preset-boyu-loop")) {
        "preset-boyu-loop must exist in debug variant"
    }

    /**
     * 顶层字段契约（spec ADDED Requirement scenario "天投泊寓 Track 顶层字段
     * 契约"）：name 三种写法 / lengthKm / thumbnail / source / referencePath
     * 长度全部锁死。
     */
    @Test
    fun boyuLoop_topLevelFieldContract() {
        assertEquals("成都天投泊寓环线", track.name.zh)
        assertEquals("Chengdu Tiantou Boyu Loop", track.name.en)
        assertNull(track.name.abbr)
        assertEquals(2.591, track.lengthKm, 1e-6)
        assertNull(track.thumbnailAssetPath)
        assertEquals(TrackSource.Preset, track.source)
        assertEquals(87, track.referencePath.points.size)
    }

    /**
     * Gate 顺序契约（spec ADDED Requirement scenario "天投泊寓 4 sector + 1
     * startFinish gate 顺序契约"）：起终点类型 / 名字 / 4 sector 全 Sector
     * 类型 / sequenceIndex 严格 [1,2,3,4]（来自 Lap 1 实测过线时间反推）。
     */
    @Test
    fun boyuLoop_gateOrderContract() {
        assertEquals(TimingGateType.StartFinish, track.startFinishGate.type)
        assertEquals("起终点", track.startFinishGate.name)
        assertEquals(4, track.sectorGates.size)
        assertEquals(
            listOf(
                TimingGateType.Sector,
                TimingGateType.Sector,
                TimingGateType.Sector,
                TimingGateType.Sector,
            ),
            track.sectorGates.map { it.type },
        )
        assertEquals(listOf(1, 2, 3, 4), track.sectorGates.map { it.sequenceIndex })
    }

    /**
     * referencePath 几何边界契约（spec ADDED Requirement scenario "天投泊寓
     * referencePath 几何边界"）：所有 87 点 lat/lon 落在 Lap 1 实测 bbox
     * 内，防止偶发坐标 drift。
     */
    @Test
    fun boyuLoop_referencePathBboxContract() {
        track.referencePath.points.forEach { p ->
            assertTrue(
                "lat $p.latitude out of [30.397, 30.407]",
                p.latitude in 30.397..30.407,
            )
            assertTrue(
                "lon $p.longitude out of [104.054, 104.062]",
                p.longitude in 104.054..104.062,
            )
        }
    }

    /**
     * referencePath 闭合度契约（spec ADDED Requirement scenario "天投泊寓
     * referencePath 闭合度"）：首末点大圆距离 ≤ 5m，sanity check 脚本切片
     * Lap 1 时间窗正确。
     */
    @Test
    fun boyuLoop_referencePathClosure() {
        val pts = track.referencePath.points
        val closureMeters = haversineMeters(pts.first(), pts.last())
        assertTrue(
            "Lap 1 closure must be ≤ 5m, was $closureMeters",
            closureMeters <= 5.0,
        )
    }

    private fun haversineMeters(p1: GeoPoint, p2: GeoPoint): Double {
        val earthRadiusM = 6_371_000.0
        val lat1 = Math.toRadians(p1.latitude)
        val lat2 = Math.toRadians(p2.latitude)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(p2.longitude - p1.longitude)
        val a = sin(dLat / 2.0) * sin(dLat / 2.0) +
            cos(lat1) * cos(lat2) * sin(dLon / 2.0) * sin(dLon / 2.0)
        return 2.0 * earthRadiusM * asin(min(1.0, sqrt(a)))
    }
}