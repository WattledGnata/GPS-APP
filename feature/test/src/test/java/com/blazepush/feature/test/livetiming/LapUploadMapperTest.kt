// @IgnoreFormatCheck
package com.blazepush.feature.test.livetiming

import com.blazepush.core.domain.model.LapEvidence
import com.blazepush.feature.test.model.laptiming.LapRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ble... 不是,livetiming-lap-upload spec R2（clientLapId 命门）+ 字段映射覆盖。
 */
class LapUploadMapperTest {

    private fun record(
        sessionId: String = "s1",
        lapIndex: Int = 2,
        durationMillis: Long = 92345L,
        sectors: List<Long> = listOf(31000L, 30000L, 31345L),
        trackId: String = "preset-tfic-lpcc",
        finishedAt: Long = 1_700_000_000_000L,
    ) = LapRecord(
        recordId = "r",
        sessionId = sessionId,
        trackId = trackId,
        lapIndex = lapIndex,
        startedAtMillis = 0L,
        finishedAtMillis = finishedAt,
        durationMillis = durationMillis,
        sectorTimes = sectors,
        evidence = LapEvidence(
            startCrossingTimestampMillis = 0L,
            finishCrossingTimestampMillis = finishedAt,
            requiredGateIds = setOf("SF"),
            acceptedGateIds = setOf("SF"),
        ),
    )

    @Test
    fun clientLapId_isStableSessionColonLapIndex() {
        assertEquals("s1:2", LapUploadMapper.clientLapId("s1", 2))
    }

    @Test
    fun buildDto_mapsAllFields() {
        val dto = LapUploadMapper.buildDto(record(), "老王")
        assertEquals("preset-tfic-lpcc", dto.trackId)
        assertEquals("老王", dto.driver)
        assertNull("App 无车型概念,carModel 不传", dto.carModel)
        assertEquals("lapNo = lapIndex+1（0-based→1-based）", 3, dto.lapNo)
        assertEquals(92345L, dto.lapTimeMs)
        assertEquals(listOf(31000L, 30000L, 31345L), dto.sectorsMs)
        assertEquals("s1:2", dto.clientLapId)
        assertTrue("lappedAt 是 RFC3339", dto.lappedAt!!.contains("T"))
        assertEquals("Clean", dto.quality)
        assertEquals(1, dto.evidenceVersion)
    }

    @Test
    fun buildDto_emptySectors_omitsSectorsMs() {
        assertNull(LapUploadMapper.buildDto(record(sectors = emptyList()), "x").sectorsMs)
    }

    @Test
    fun buildDto_sameLap_reusesSameClientLapId() {
        // 命门：同圈两次 build 得**完全相同** clientLapId（稳定派生,非随机）
        val r = record()
        assertEquals(
            LapUploadMapper.buildDto(r, "a").clientLapId,
            LapUploadMapper.buildDto(r, "b").clientLapId,
        )
    }

    @Test
    fun reverseLock_livetimingSourceHasNoRandomUuid() {
        // spec R2 反例锁：上报/映射/编排路径 MUST NOT 在请求构造处随机生成 clientLapId
        val dir = File("src/main/java/com/blazepush/feature/test/livetiming")
        assertTrue("livetiming 源目录必须存在", dir.isDirectory)
        // 剔除注释行(// 与 KDoc * 开头)后再扫:注释里合法解释"为什么禁 randomUUID"不应误触发反例锁,
        // 只查**实际代码**有无 randomUUID 调用。
        val codeLines = dir.listFiles { f -> f.name.endsWith(".kt") }!!
            .flatMap { it.readLines() }
            .filterNot {
                val t = it.trimStart()
                t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
            }
        val code = codeLines.joinToString("\n")
        assertFalse(
            "livetiming 实际代码 MUST NOT 含 randomUUID（clientLapId 必须按圈稳定派生/重试复用,否则幂等失效）",
            code.contains("randomUUID"),
        )
        assertTrue(
            "clientLapId MUST 由 sessionId:lapIndex 稳定派生",
            code.contains("\"\$sessionId:\$lapIndex\""),
        )
    }
}
