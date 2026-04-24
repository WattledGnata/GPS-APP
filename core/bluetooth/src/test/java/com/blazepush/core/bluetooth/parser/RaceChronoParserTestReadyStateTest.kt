package com.blazepush.core.bluetooth.parser

import com.blazepush.core.domain.model.GpsData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * @description 战役 H 一期 R1 契约测试（A26）：`parseGpsTimeData` MUST NOT 写
 *              `isTestReady` 字段。核销 spec `race-chrono-parser` Requirement
 *              "`parseGpsTimeData` MUST NOT 写 `isTestReady` 字段（R1 / A26）"
 *              四条 Scenario。`isTestReady` 的唯一写入源 MUST 是主包
 *              `parseGpsData` 的 `satellites >= 6 && hdop < 2.0` 判定；时间包
 *              到达只代表"时间同步基准更新"，不代表"GPS 定位质量满足测试就绪
 *              门槛"。与 A8 `RaceChronoParserProtocolTimeTest` 契约互补、scope
 *              完全分离（A8 只关心 `protocolTimeReference` / `isTimeSynced`）。
 * @author haozhang93
 * @date 2026-04-24
 */
class RaceChronoParserTestReadyStateTest {

    /**
     * Spec Scenario 1：时间包到达时输入 `isTestReady = false` 保持 false。
     *
     * 硬区分 v1：v1 实现会在时间包成功路径无条件置 `isTestReady = true`，
     * 本断言证明 v2 已不在时间包路径写 isTestReady。
     */
    @Test
    fun parseGpsTimeData_whenInputIsTestReadyFalse_doesNotFlipToTrue() {
        val parser = RaceChronoParser()
        val input = emptyGpsData(isTestReady = false)
        val timePacket = byteArrayOf(0x20.toByte(), 0x12.toByte(), 0x34.toByte())

        val result = parser.parseGpsTimeData(timePacket, input)

        assertEquals(
            "A26: 时间包 MUST NOT 把 isTestReady 从 false 翻成 true（v1 残留会命中此断言）",
            false,
            result.isTestReady,
        )
        assertNull(
            "A25: 成功路径仍显式清 errorMessage",
            result.errorMessage,
        )
    }

    /**
     * Spec Scenario 2：时间包到达时输入 `isTestReady = true` 保持 true。
     *
     * 即主包已先行判就绪，时间包路径不覆盖/不破坏已就绪状态（时间包只负责
     * 更新 `protocolTimeReference`，与就绪门槛解耦）。
     */
    @Test
    fun parseGpsTimeData_whenInputIsTestReadyTrue_keepsTrue() {
        val parser = RaceChronoParser()
        val input = emptyGpsData(isTestReady = true)
        val timePacket = byteArrayOf(0x20.toByte(), 0x12.toByte(), 0x34.toByte())

        val result = parser.parseGpsTimeData(timePacket, input)

        assertEquals(
            "A26: 时间包路径 MUST NOT 覆盖已就绪状态（isTestReady=true → true）",
            true,
            result.isTestReady,
        )
        assertNull(
            "A25: 成功路径仍显式清 errorMessage",
            result.errorMessage,
        )
    }

    /**
     * Spec Scenario 3：时间包与主包交替冷启动不再闪烁 `isTestReady`。
     *
     * 冷启动顺序：时间包 → 主包(sats=4) → 时间包 → 主包(sats=8)
     * 期望状态：[false, false, false, true]
     * 硬区分 v1：v1 会得到 [true, false, true, true]（时间包每次把 false 翻回
     * true），造成 UI "就绪 ↔ 未就绪"闪烁。
     */
    @Test
    fun parseGpsTimeData_andParseGpsData_coldStartSequence_noFlicker() {
        val parser = RaceChronoParser()
        var data = emptyGpsData(isTestReady = false)
        val timePacket = byteArrayOf(0x20.toByte(), 0x12.toByte(), 0x34.toByte())
        val mainPacketSats4 = buildMainPacket(satellites = 4, hdop = 1.5)
        val mainPacketSats8 = buildMainPacket(satellites = 8, hdop = 1.5)

        data = parser.parseGpsTimeData(timePacket, data)
        assertEquals("time#1: 时间包不翻转 isTestReady（v1 会置 true）", false, data.isTestReady)

        data = parser.parseGpsData(mainPacketSats4, data)
        assertEquals("main#1: sats<6 → isTestReady=false", false, data.isTestReady)

        data = parser.parseGpsTimeData(timePacket, data)
        assertEquals(
            "time#2: 时间包 MUST NOT 把 false 翻成 true（v1 会翻转，v2 保持）",
            false,
            data.isTestReady,
        )

        data = parser.parseGpsData(mainPacketSats8, data)
        assertEquals("main#2: sats>=6 & hdop<2 → isTestReady=true", true, data.isTestReady)
    }

    /**
     * Spec Scenario 4：时间包短包失败不动 `isTestReady`。
     *
     * 短包失败路径 MUST 只写 `errorMessage = "short-packet"`（A25 契约），
     * MUST NOT 触碰 `isTestReady` 字段。
     */
    @Test
    fun parseGpsTimeData_whenShortPacket_doesNotTouchIsTestReady() {
        val parser = RaceChronoParser()
        val input = emptyGpsData(isTestReady = true)
        val shortPacket = byteArrayOf(0x20.toByte(), 0x12.toByte())

        val result = parser.parseGpsTimeData(shortPacket, input)

        assertEquals(
            "A26: 短包失败路径 MUST NOT 触碰 isTestReady（输入 true → 输出仍 true）",
            true,
            result.isTestReady,
        )
        assertEquals(
            "A25: 短包失败写 errorMessage='short-packet'",
            "short-packet",
            result.errorMessage,
        )
    }

    /**
     * 测试内 helper：以指定的 `isTestReady` 构造一个完整 [GpsData] 实例。
     *
     * `GpsData` 构造签名要求 13 个字段无默认值，本 helper 把所有非关键字段
     * 填充为零值，只允许调用方指定 `isTestReady`（本 change R1 场景关注的
     * 唯一字段）。与 A8 `RaceChronoParserProtocolTimeTest.emptyGpsData()`
     * scope 分离 —— A8 关心时间戳字段族，本文件关心就绪状态字段。
     */
    private fun emptyGpsData(isTestReady: Boolean): GpsData = GpsData(
        timestamp = 0L,
        speed = 0.0,
        latitude = 0.0,
        longitude = 0.0,
        altitude = 0.0,
        bearing = 0.0,
        satelliteCount = 0,
        hdop = 0.0,
        vdop = 0.0,
        frequency = 0.0,
        isConnected = false,
        isTestReady = isTestReady,
        errorMessage = null,
        fixQuality = 0,
    )

    /**
     * 测试内 helper：构造 20 字节主包字节流。
     *
     * 仅覆盖本 change R1 测试必需的字段（`satellites` / `hdop` / 合法 fixQuality）。
     * 其它字段填充为合理默认值，让 parser 的 isTestReady 判定分支可命中。
     * 不复用 `RaceChronoParserTest.createValidGpsData20` 以避免与 A16b 既定 helper
     * 耦合 —— 本文件独立重写简化版。
     */
    private fun buildMainPacket(satellites: Int, hdop: Double): ByteArray {
        val data = ByteArray(20)
        // Byte 0-2: timeSinceHourStart (任意合法值，test 不关心)
        data[0] = 0x00
        data[1] = 0x00
        data[2] = 0x00
        // Byte 3: fixQuality=1 (高位 2 bit) | satellites (低 6 bit)
        data[3] = ((1 shl 6) or (satellites and 0x3F)).toByte()
        // Byte 4-11: latitude (60.1725 * 1e7 = 601725000) + longitude (24.9375 * 1e7)
        val latInt = 601_725_000
        data[4] = ((latInt shr 24) and 0xFF).toByte()
        data[5] = ((latInt shr 16) and 0xFF).toByte()
        data[6] = ((latInt shr 8) and 0xFF).toByte()
        data[7] = (latInt and 0xFF).toByte()
        val lonInt = 249_375_000
        data[8] = ((lonInt shr 24) and 0xFF).toByte()
        data[9] = ((lonInt shr 16) and 0xFF).toByte()
        data[10] = ((lonInt shr 8) and 0xFF).toByte()
        data[11] = (lonInt and 0xFF).toByte()
        // Byte 12-13: altitude (任意合法值，走 bit15=0 分支)
        data[12] = 0x00
        data[13] = 0x00
        // Byte 14-15: speed (任意合法值)
        data[14] = 0x00
        data[15] = 0x00
        // Byte 16-17: bearing (任意合法值)
        data[16] = 0x00
        data[17] = 0x00
        // Byte 18: HDOP (raw = hdop * 10)，用于 isTestReady 判定 hdop<2.0
        data[18] = ((hdop * 10).toInt() and 0xFF).toByte()
        // Byte 19: VDOP (任意合法值)
        data[19] = 0x0A
        return data
    }
}