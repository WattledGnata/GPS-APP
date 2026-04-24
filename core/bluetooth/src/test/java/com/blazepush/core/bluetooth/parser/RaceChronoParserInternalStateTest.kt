package com.blazepush.core.bluetooth.parser

import com.blazepush.core.domain.model.GpsData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * @description 战役 H 一期 R2 契约测试（A41）：RaceChronoParser MUST NOT 维护
 *              tracking 相关内部死状态。核销 spec `race-chrono-parser` Requirement
 *              "parser MUST NOT 维护 tracking 相关内部死状态（R2 / A41）" 三条
 *              Scenario。`totalDistance / hasStartedTracking / startTime /
 *              lastLatitude / lastLongitude` 五个字段 MUST 从类体完全消失；
 *              `parseGpsData` 路径 MUST NOT 包含 tracking 累加逻辑 / `Location.
 *              distanceBetween` JNI 调用 / tracking 块内的 `System.
 *              currentTimeMillis()`。同时锁定 frequency 与时间同步活字段
 *              （gpsFrequency / gpsDataTimestamps / protocolTimeReference）
 *              MUST 保留 reset 清理行为，防止 R2 误删活状态。
 * @author haozhang93
 * @date 2026-04-24
 */
class RaceChronoParserInternalStateTest {

    /**
     * Spec Scenario 1：`RaceChronoParser` 类上不存在 5 个已删字段。
     *
     * 硬区分 v1：v1 五个字段全部存在，本断言 intersection.isEmpty() 证明 v2
     * 已彻底删除。同时断言三个活字段（frequency / 时间同步）未被 R2 误删。
     */
    @Test
    fun parserClass_doesNotDeclareRemovedTrackingFields() {
        val fieldNames = RaceChronoParser::class.java.declaredFields.map { it.name }.toSet()

        val forbidden = setOf(
            "totalDistance",
            "hasStartedTracking",
            "startTime",
            "lastLatitude",
            "lastLongitude",
        )
        val intersection = fieldNames intersect forbidden
        assertTrue(
            "A41: RaceChronoParser 不得维护已删的 tracking 死状态字段，但发现: $intersection",
            intersection.isEmpty(),
        )

        assertTrue("frequency 活字段 gpsFrequency 必须保留", "gpsFrequency" in fieldNames)
        assertTrue("frequency 活字段 gpsDataTimestamps 必须保留", "gpsDataTimestamps" in fieldNames)
        assertTrue(
            "时间同步活字段 protocolTimeReference 必须保留",
            "protocolTimeReference" in fieldNames,
        )
    }

    /**
     * Spec Scenario 2：`parseGpsData` 解析 100 帧不产生 tracking 副作用。
     *
     * 25Hz × 4s 真实定位序列模拟。断言主包字段正确解码（satelliteCount = 8）
     * + 反射再次验证字段仍未被偷偷加回（防止 R2 回退 / 后续误加）。
     */
    @Test
    fun parseGpsData_100Frames_noTrackingSideEffect() {
        val parser = RaceChronoParser()
        var data = emptyGpsData()
        val packet = buildMainPacket(satellites = 8, hdop = 1.5)

        repeat(100) {
            data = parser.parseGpsData(packet, data)
        }

        assertEquals("100 帧后 satelliteCount 应稳定解码为 8", 8, data.satelliteCount)

        val fieldNames = RaceChronoParser::class.java.declaredFields.map { it.name }.toSet()
        assertFalse(
            "A41: 100 帧后不得出现 totalDistance 字段（防御 R2 回退）",
            "totalDistance" in fieldNames,
        )
        assertFalse(
            "A41: 100 帧后不得出现 hasStartedTracking 字段（防御 R2 回退）",
            "hasStartedTracking" in fieldNames,
        )
    }

    /**
     * Spec Scenario 3：`reset()` 仍清理 frequency + 时间同步活状态。
     *
     * 运行一段时间后 reset，断言三个活字段（gpsFrequency / gpsDataTimestamps /
     * protocolTimeReference）被正确清理。本断言锁定 R2 未误删 reset 的 frequency
     * 清理行为或时间同步清理行为。`lastFrequencyUpdateTime` 的 reset 缺失不在本
     * change scope 内（另行评估 A28），本 Scenario 不对其做任何断言。
     */
    @Test
    fun reset_stillClearsFrequencyAndTimeSync_butNoDeadState() {
        val parser = RaceChronoParser()
        var data = emptyGpsData()
        val packet = buildMainPacket(satellites = 8, hdop = 1.5)
        val timePacket = byteArrayOf(0x20.toByte(), 0x12.toByte(), 0x34.toByte())
        repeat(30) { data = parser.parseGpsData(packet, data) }
        data = parser.parseGpsTimeData(timePacket, data)

        parser.reset()

        val gpsFrequencyField = RaceChronoParser::class.java
            .getDeclaredField("gpsFrequency")
            .apply { isAccessible = true }
        val timestampsField = RaceChronoParser::class.java
            .getDeclaredField("gpsDataTimestamps")
            .apply { isAccessible = true }
        val referenceField = RaceChronoParser::class.java
            .getDeclaredField("protocolTimeReference")
            .apply { isAccessible = true }

        assertEquals(
            "reset() MUST 清 gpsFrequency 到 0.0",
            0.0,
            gpsFrequencyField.getDouble(parser),
            0.0,
        )
        assertEquals(
            "reset() MUST 清 gpsDataTimestamps 到空",
            0,
            (timestampsField.get(parser) as List<*>).size,
        )
        assertNull(
            "reset() MUST 清 protocolTimeReference 到 null",
            referenceField.get(parser),
        )
    }

    /**
     * 测试内 helper：构造一个完整 [GpsData] 零值实例。
     *
     * `GpsData` 构造签名要求 13 个字段无默认值，本 helper 全部填零。与
     * `RaceChronoParserTestReadyStateTest.emptyGpsData` / A8
     * `RaceChronoParserProtocolTimeTest.emptyGpsData` scope 分离 —— 本文件
     * 关心 parser 内部字段集合，不关心输入 GpsData 的任何字段。
     */
    private fun emptyGpsData(): GpsData = GpsData(
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
        isTestReady = false,
        errorMessage = null,
        fixQuality = 0,
    )

    /**
     * 测试内 helper：构造 20 字节主包字节流。
     *
     * 仅覆盖 R2 测试必需的字段（`satellites` / `hdop` / 合法 fixQuality / 真实
     * 坐标让 parser 走过 lat/lon 解码路径）。其它字段填充为合理默认值。与
     * `RaceChronoParserTestReadyStateTest.buildMainPacket` scope 分离，本文件
     * 独立重写简化版。
     */
    private fun buildMainPacket(satellites: Int, hdop: Double): ByteArray {
        val data = ByteArray(20)
        data[0] = 0x00
        data[1] = 0x00
        data[2] = 0x00
        data[3] = ((1 shl 6) or (satellites and 0x3F)).toByte()
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
        data[12] = 0x00
        data[13] = 0x00
        data[14] = 0x00
        data[15] = 0x00
        data[16] = 0x00
        data[17] = 0x00
        data[18] = ((hdop * 10).toInt() and 0xFF).toByte()
        data[19] = 0x0A
        return data
    }
}