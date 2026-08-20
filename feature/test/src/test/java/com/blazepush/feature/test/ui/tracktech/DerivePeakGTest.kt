package com.blazepush.feature.test.ui.tracktech

import com.blazepush.core.data.local.entity.TestRecordEntity
import com.blazepush.core.domain.model.TestTemplate
import com.blazepush.feature.test.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * derivePeakG 四个分支单测（round smooth-perftest-acceleration-curve §4）。
 *
 * 锁定 spec.md `Requirement 4` 全部 UI 渲染 scenarios：
 * - 0-100 acc → "PEAK ACCEL G" + maxAcceleration
 * - 100-0 brake + maxD>0 → "PEAK BRAKE G" + maxDeceleration
 * - 100-0 brake + maxD==0（V1 record） → "—" + subtitle "V1 record"，MUST NOT fallback 到 maxA
 * - else（template == null） → "PEAK G" 兜底
 *
 * @author CC
 * @description PEAK G UI 二选一渲染契约
 * @date 2026-05-03
 */
class DerivePeakGTest {

    private fun fixture(
        templateId: String,
        maxA: Double,
        maxD: Double,
    ): TestRecordEntity = TestRecordEntity(
        id = "test-id",
        testTemplateId = templateId,
        testType = "x",
        carModel = "x",
        deviceName = "",
        deviceAddress = "",
        result = "",
        timestamp = 0L,
        totalTime = 0.0,
        totalDistance = 0.0,
        avgAcceleration = 0.0,
        maxAcceleration = maxA,
        maxDeceleration = maxD,
        dataFilePath = "",
    )

    @Test
    fun `acc 测试 PEAK ACCEL G + maxAcceleration`() {
        val record = fixture(templateId = "acc_0_100", maxA = 0.85, maxD = 0.0)
        val peak = derivePeakG(record, TestTemplate.Acceleration0To100)

        assertEquals(R.string.detail_peak_accel_g, peak.labelRes)
        assertEquals("0.85", peak.valueText)
        assertEquals("G", peak.unit)
        assertEquals(0.85, peak.gForceChartMaxG, 1e-9)
        assertEquals(false, peak.isLegacyRecord)
    }

    @Test
    fun `brake 测试 maxD 大于 0 PEAK BRAKE G + maxDeceleration`() {
        val record = fixture(templateId = "brake_100_0", maxA = 0.0, maxD = 1.12)
        val peak = derivePeakG(record, TestTemplate.Braking100To0)

        assertEquals(R.string.detail_peak_brake_g, peak.labelRes)
        assertEquals("1.12", peak.valueText)
        assertEquals("G", peak.unit)
        assertEquals(1.12, peak.gForceChartMaxG, 1e-9)
        assertEquals(false, peak.isLegacyRecord)
    }

    @Test
    fun `brake V1 record maxD 等于 0 显示 dash + subtitle V1 record 不 fallback 到 maxA`() {
        // V1 abs 污染数据：testTemplateId='brake_100_0' AND maxDeceleration=0 AND maxAcceleration>0
        val record = fixture(templateId = "brake_100_0", maxA = 0.91, maxD = 0.0)
        val peak = derivePeakG(record, TestTemplate.Braking100To0)

        assertEquals(R.string.detail_peak_brake_g, peak.labelRes)
        assertEquals("V1 brake 分支 valueText MUST 为 dash，不能 fallback 到 maxA=0.91", "—", peak.valueText)
        assertNull("V1 brake 分支 unit MUST 为 null（不显示 G）", peak.unit)
        assertEquals("V1 brake 分支 gForceChartMaxG MUST 为 0.0", 0.0, peak.gForceChartMaxG, 1e-9)
        assertEquals(true, peak.isLegacyRecord)
    }

    @Test
    fun `template null 兜底 PEAK G + maxAcceleration`() {
        // 边界：testTemplateId 不可识别时 TestTemplate.fromId 返回 null
        val record = fixture(templateId = "unknown", maxA = 0.5, maxD = 0.0)
        val peak = derivePeakG(record, template = null)

        assertEquals(R.string.detail_peak_g, peak.labelRes)
        assertEquals("0.50", peak.valueText)
        assertEquals("G", peak.unit)
        assertEquals(0.5, peak.gForceChartMaxG, 1e-9)
        assertEquals(false, peak.isLegacyRecord)
    }
}
