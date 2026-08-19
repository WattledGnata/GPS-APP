// 本测试仅在 :feature:test:testReleaseUnitTest 任务中执行（main + release + test
// + testRelease 源集组合），断言 release variant 的预置赛道列表严格为 [TFIC, XIC, NIC, V1]，
// 不含 debug-only 的天投泊寓环线。
// 锁定：OpenSpec change `add-debug-preset-track-boyu-loop` design D5（变体源集机制）
//      + `add-preset-track-xic` design D4（XIC 进 mainPresets，所有 variant 可见）。
package com.blazepush.feature.test.repository

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * @description Release variant 预置赛道列表契约：MUST 严格为 [TFIC, XIC, NIC, V1] 且顺序固定。
 *   由 OpenSpec change `add-debug-preset-track-boyu-loop` design D5（变体源集机制）+
 *   `add-preset-track-xic` design D4（XIC 进 mainPresets）联合锁定。本类
 *   仅在 :feature:test:testReleaseUnitTest 中执行；testDebugUnitTest 跑
 *   另一份 [TrackCatalogDebugVariantTest]。
 * @author CC (Claude Code)
 * @date 2026-05-01（add-debug-preset-track-boyu-loop 创建）
 *       2026-06-18（add-preset-track-xic 追加 XIC）
 */
class TrackCatalogReleaseVariantTest {

    /**
     * Release variant 下 [PresetTrackCatalog.getAllTracks] 严格返回 [TFIC, XIC, NIC, V1]，
     * 锁死"release 包零 debug 数据泄漏 + mainPresets 顺序固定"的契约。
     */
    @Test
    fun getAllTracks_releaseVariant_exposesTficXicNicAndV1PresetsInOrder() = runTest {
        val catalog = PresetTrackCatalog()

        val ids = catalog.getAllTracks().map { it.id }

        assertEquals(
            listOf(
                "preset-tfic-lpcc",
                "preset-xic-lpcc",
                "preset-nic-full",
                "preset-v1-autoworld-full",
            ),
            ids,
        )
    }
}
