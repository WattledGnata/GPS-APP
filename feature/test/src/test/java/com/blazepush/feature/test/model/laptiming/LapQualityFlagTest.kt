package com.blazepush.feature.test.model.laptiming

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁定 LapQualityFlag 枚举成员契约。
 *
 * 对应 OpenSpec change fix-laptime-clock-source-integrity tasks.md 2.7。
 * ProtocolDesyncGap 是本 change 新增的质量标记，engine 闭圈时扫描 trajectory
 * 相邻 ts 差 > 200ms 会打上。
 */
class LapQualityFlagTest {

    @Test
    fun enumContainsProtocolDesyncGap() {
        assertTrue(
            "LapQualityFlag 必须包含 ProtocolDesyncGap 成员，供 LapTimingEngine.handleStartFinishCrossing " +
                "在闭圈扫描到 trajectory 相邻 ts 差 > 200ms 时追加到 LapRecord.qualityFlags",
            LapQualityFlag.values().any { it.name == "ProtocolDesyncGap" }
        )
    }

    @Test
    fun enumStillContainsLegacyFlags() {
        val names = LapQualityFlag.values().map { it.name }.toSet()
        assertTrue("LowAccuracy 必须保留", "LowAccuracy" in names)
        assertTrue("SparseSamples 必须保留", "SparseSamples" in names)
        assertTrue("SuspectedJitter 必须保留", "SuspectedJitter" in names)
        assertTrue("IncompleteSectors 必须保留", "IncompleteSectors" in names)
    }
}
