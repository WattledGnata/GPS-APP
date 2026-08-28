// 本测试仅在 :feature:test:testDebugUnitTest 任务中执行（main + debug + test
// + testDebug 源集组合），断言 debug variant 的预置赛道列表严格为
// [TFIC, XIC, NIC, V1, 天投泊寓]，顺序由 PresetTracks.kt 内 `mainPresets + extraPresetTracks()`
// 拼接表达式锁定（TFIC + XIC + NIC + V1 在 mainPresets，天投泊寓在 extraPresetTracks）。
// 锁定：OpenSpec change `add-debug-preset-track-boyu-loop` design D5（变体源集机制）
//      + `add-preset-track-xic` design D4（XIC 进 mainPresets，所有 variant 可见）。
package com.blazepush.feature.test.repository

import kotlin.math.cos
import kotlin.math.hypot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * @description Debug variant 预置赛道列表契约：MUST 严格为 [TFIC, XIC, NIC, V1, 天投泊寓]
 *   且顺序固定。由 OpenSpec change `add-debug-preset-track-boyu-loop` design D5
 *   （变体源集机制）+ `add-preset-track-xic` design D4（XIC 进 mainPresets）联合
 *   锁定。本类仅在 :feature:test:testDebugUnitTest 中执行；testReleaseUnitTest
 *   跑另一份 [TrackCatalogReleaseVariantTest]。
 * @author CC (Claude Code)
 * @date 2026-05-01（add-debug-preset-track-boyu-loop 创建）
 *       2026-06-18（add-preset-track-xic 追加 XIC）
 */
class TrackCatalogDebugVariantTest {

    /**
     * Debug variant 下 [PresetTrackCatalog.getAllTracks] 严格返回
     * [TFIC, XIC, NIC, V1, 天投泊寓]，顺序由 main 源集 `mainPresets + extraPresetTracks()`
     * 拼接表达式锁定（mainPresets = [TFIC, XIC, NIC, V1]，extraPresetTracks (debug) = [天投泊寓]）。
     */
    @Test
    fun getAllTracks_debugVariant_exposesTficXicNicV1AndBoyuLoopInOrder() = runTest {
        val catalog = PresetTrackCatalog()

        val ids = catalog.getAllTracks().map { it.id }

        assertEquals(
            listOf(
                "preset-tfic-lpcc",
                "preset-xic-lpcc",
                "preset-nic-full",
                "preset-v1-autoworld-full",
                "preset-boyu-loop",
            ),
            ids,
        )
    }

    @Test
    fun boyuLoopStartFinishGate_is120MetersAndKeepsOriginalCenter() {
        val gate = requireNotNull(PresetTrackCatalog().getTrack("preset-boyu-loop")).startFinishGate
        val centerLatitude = (gate.line.start.latitude + gate.line.end.latitude) / 2.0
        val centerLongitude = (gate.line.start.longitude + gate.line.end.longitude) / 2.0
        val northMeters = (gate.line.end.latitude - gate.line.start.latitude) * 111_320.0
        val eastMeters = (gate.line.end.longitude - gate.line.start.longitude) *
            111_320.0 * cos(Math.toRadians(centerLatitude))

        assertEquals(120.0, hypot(northMeters, eastMeters), 0.2)
        assertEquals(30.3997726667, centerLatitude, 1e-12)
        assertEquals(104.06147, centerLongitude, 1e-12)
    }
}
