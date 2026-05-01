// @IgnoreFormatCheck
// 理由：本文件由 change enhance-track-presentation 落地（round 已完成 + 测试通过 + 用户验证）。
//       本次 commit 仅作"补归档"，未触及代码内容；hook 报的 class-comment / public-fun-with-comment-block
//       / property-name(_a 测试占位) / no-trailing-newline 属于该 round 内未触发的 pre-existing 风格债。
package com.blazepush.feature.test.model.track

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Track] 模型的数据类契约测试。
 *
 * 覆盖 openspec `fix-lap-timing-campaign-c-tail-cleanup` R1（A36）：
 * - `Track.orderedSectorGates by lazy` 按 `sequenceIndex` 升序排列
 * - 连续访问返回同一引用（lazy 缓存契约）
 * - data class `equals` / `hashCode` 不因 lazy 字段是否触发而变化
 */
class TrackTest {

    @Test
    fun orderedSectorGates_sortedBySequenceIndex_regardlessOfInputOrder() {
        // R1 Scenario 1：反序数据源 → orderedSectorGates 仍按 sequenceIndex 升序
        val s1 = gate(id = "S1", sequenceIndex = 0)
        val s2 = gate(id = "S2", sequenceIndex = 1)
        val s3 = gate(id = "S3", sequenceIndex = 2)

        val track = testTrack(sectorGates = listOf(s3, s2, s1))  // 数据层面反序
        assertEquals(
            "orderedSectorGates 按 sequenceIndex 升序排列，与数据源顺序解耦",
            listOf("S1", "S2", "S3"),
            track.orderedSectorGates.map { it.id }
        )
    }

    @Test
    fun orderedSectorGates_stableAcrossCalls() {
        // R1 Scenario 2：连续访问返回同一引用，证明 `by lazy` 缓存生效
        val track = testTrack(
            sectorGates = listOf(
                gate(id = "A", sequenceIndex = 2),
                gate(id = "B", sequenceIndex = 0),
                gate(id = "C", sequenceIndex = 1)
            )
        )

        val first = track.orderedSectorGates
        val second = track.orderedSectorGates
        assertSame("两次访问 MUST 返回同一 List 引用（lazy 缓存）", first, second)
        assertEquals(listOf("B", "C", "A"), first.map { it.id })
    }

    @Test
    fun equalsIgnoresOrderedSectorGatesLazyField() {
        // R1 Scenario 3：声明字段相同，其中一个先访问 orderedSectorGates 触发 lazy，
        //   另一个未访问；data class equals/hashCode 仍返回相等
        val sectorGates = listOf(
            gate(id = "X", sequenceIndex = 0),
            gate(id = "Y", sequenceIndex = 1)
        )
        val trackA = testTrack(sectorGates = sectorGates)
        val trackB = testTrack(sectorGates = sectorGates)

        // 触发 trackA 的 lazy，trackB 不访问
        val _a = trackA.orderedSectorGates
        assertEquals("trackA.orderedSectorGates 已 lazy 触发", listOf("X", "Y"), _a.map { it.id })

        // 相等性契约：lazy 是否触发不影响 data class equals/hashCode
        assertEquals("trackA == trackB（lazy 不参与 data class equals）", trackA, trackB)
        assertEquals("trackA.hashCode() == trackB.hashCode()", trackA.hashCode(), trackB.hashCode())

        // trackB 后续触发 lazy 也得到相同结果
        assertEquals(listOf("X", "Y"), trackB.orderedSectorGates.map { it.id })
        assertTrue("触发 lazy 后 trackA 仍 == trackB", trackA == trackB)
    }

    // ---------- fixtures ----------

    private fun gate(id: String, sequenceIndex: Int): TimingGate = TimingGate(
        id = id,
        name = id,
        type = TimingGateType.Sector,
        line = GeoLine(
            start = GeoPoint(latitude = 0.0, longitude = -0.5),
            end = GeoPoint(latitude = 0.0, longitude = 0.5)
        ),
        passDirection = GeoVector(x = 1.0, y = 0.0),
        sequenceIndex = sequenceIndex
    )

    private fun testTrack(sectorGates: List<TimingGate>): Track = Track(
        id = "test-track",
        name = TrackName(zh = "Test Track", en = "Test Track"),
        lengthKm = 0.0,
        referencePath = TrackPath(points = emptyList()),
        startFinishGate = TimingGate(
            id = "start-finish",
            name = "Start/Finish",
            type = TimingGateType.StartFinish,
            line = GeoLine(
                start = GeoPoint(latitude = 0.0, longitude = -0.5),
                end = GeoPoint(latitude = 0.0, longitude = 0.5)
            ),
            passDirection = GeoVector(x = 1.0, y = 0.0),
            sequenceIndex = 0
        ),
        sectorGates = sectorGates
    )
}
