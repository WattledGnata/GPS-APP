package com.blazepush.data.service.parser

import android.util.Log
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.mockito.Mockito

/**
 * RaceChronoParser 单元测试
 * 测试 ESP32 20字节协议解析逻辑
 */
class RaceChronoParserTest {

    private lateinit var parser: RaceChronoParser

    @Before
    fun setup() {
        parser = RaceChronoParser()
    }

    @After
    fun tearDown() {
        // Nothing to clean up
    }

    // Helper to run a test with Log mocked
    private fun runWithMockedLog(test: () -> Unit) {
        val logMock = Mockito.mockStatic(Log::class.java)
        try {
            test()
        } finally {
            logMock.close()
        }
    }

    // 辅助函数：创建测试用的GpsData对象
    private fun createTestData() = com.blazepush.domain.model.GpsData(
        timestamp = 0,
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
        fixQuality = 0
    )

    // ==================== 基本解析测试 ====================

    @Test
    fun RP01_parseValid20Bytes_returnsCorrectGpsData() {
        // Given: 有效的20字节GPS数据
        val data = createValidGpsData20(
            satellites = 12,
            speed = 15.0,
            latitude = 60.1725897,
            longitude = 24.9376543,
            altitude = 100.0,
            bearing = 45.0,
            fixQuality = 1,
            hdop = 1.0,
            vdop = 1.5
        )

        // When: 解析数据
        val result = parser.parseGpsData(data, createTestData())

        // Then: 应返回有效的GpsData
        assertEquals("卫星数应为12", 12, result.satelliteCount)
        assertEquals("速度应为15.0", 15.0, result.speed, 0.1)
        assertEquals("纬度应正确", 60.1725897, result.latitude, 0.000001)
        assertEquals("经度应正确", 24.9376543, result.longitude, 0.000001)
        assertEquals("海拔应为100.0", 100.0, result.altitude, 0.1)
        assertEquals("方位角应为45.0", 45.0, result.bearing, 0.1)
        assertEquals("fixQuality应为1", 1, result.fixQuality)
        assertEquals("HDOP应为1.0", 1.0, result.hdop, 0.01)
        assertEquals("VDOP应为1.5", 1.5, result.vdop, 0.01)
    }

    @Test
    fun RP02_parseInvalidSize_returnsOriginalData() {
        // Given: 数据长度不足20字节
        val shortData = ByteArray(19)

        // When: 尝试解析
        val result = parser.parseGpsData(shortData, createTestData())

        // Then: 应返回原始数据
        assertEquals("应返回原始数据", 0.0, result.latitude, 0.001)
    }

    @Test
    fun RP03_parseExactly19Bytes_returnsOriginalData() {
        // Given: 刚好19字节
        val almostData = ByteArray(19)

        // When
        val result = parser.parseGpsData(almostData, createTestData())

        // Then: 应返回原始数据
        assertEquals("应返回原始数据", 0.0, result.latitude, 0.001)
    }

    // ==================== fixQuality 测试 ====================

    @Test
    fun RP04_parseFixQuality_0_noFix() {
        // Given: fixQuality=0 (无定位)
        val data = createValidGpsData20(fixQuality = 0, satellites = 0)

        // When
        val result = parser.parseGpsData(data, createTestData())

        // Then
        assertEquals("fixQuality应为0", 0, result.fixQuality)
        assertFalse("无定位时isTestReady应为false", result.isTestReady)
    }

    @Test
    fun RP05_parseFixQuality_1_GPS() {
        // Given: fixQuality=1 (GPS)
        val data = createValidGpsData20(fixQuality = 1, satellites = 12, hdop = 1.0)

        // When
        val result = parser.parseGpsData(data, createTestData())

        // Then
        assertEquals("fixQuality应为1", 1, result.fixQuality)
        assertTrue("GPS定位且条件满足时应就绪", result.isTestReady)
    }

    @Test
    fun RP06_parseFixQuality_2_DGPS() {
        // Given: fixQuality=2 (DGPS)
        val data = createValidGpsData20(fixQuality = 2, satellites = 10, hdop = 0.5)

        // When
        val result = parser.parseGpsData(data, createTestData())

        // Then
        assertEquals("fixQuality应为2", 2, result.fixQuality)
    }

    @Test
    fun RP07_parseFixQuality_3() {
        // Given: fixQuality=3
        val data = createValidGpsData20(fixQuality = 3, satellites = 15, hdop = 1.0)

        // When
        val result = parser.parseGpsData(data, createTestData())

        // Then
        assertEquals("fixQuality应为3", 3, result.fixQuality)
    }

    // ==================== 卫星数测试 ====================

    @Test
    fun RP08_parseSatellites_zero() {
        // Given: 卫星数为0
        val data = createValidGpsData20(satellites = 0, fixQuality = 1)

        // When
        val result = parser.parseGpsData(data, createTestData())

        // Then
        assertEquals("卫星数应为0", 0, result.satelliteCount)
    }

    @Test
    fun RP09_parseSatellites_normal() {
        // Given: 卫星数为12
        val data = createValidGpsData20(satellites = 12, fixQuality = 1)

        // When
        val result = parser.parseGpsData(data, createTestData())

        // Then
        assertEquals("卫星数应为12", 12, result.satelliteCount)
    }

    @Test
    fun RP10_parseSatellites_maxValue() {
        // Given: 卫星数为63 (0b111111)
        val data = createValidGpsData20(satellites = 63, fixQuality = 1)

        // When
        val result = parser.parseGpsData(data, createTestData())

        // Then
        assertEquals("卫星数应为63", 63, result.satelliteCount)
    }

    // ==================== 速度解析测试 ====================

    @Test
    fun RP11_parseSpeed_normal() {
        // Given: 速度15.0 km/h
        val data = createValidGpsData20(speed = 15.0)

        // When
        val result = parser.parseGpsData(data, createTestData())

        // Then
        assertEquals("速度应为15.0", 15.0, result.speed, 0.1)
    }

    @Test
    fun RP12_parseSpeed_zero() {
        // Given: 速度为0
        val data = createValidGpsData20(speed = 0.0)

        // When
        val result = parser.parseGpsData(data, createTestData())

        // Then
        assertEquals("速度应为0", 0.0, result.speed, 0.001)
    }

    @Test
    fun RP13_parseSpeed_high() {
        // Given: 速度300 km/h
        val data = createValidGpsData20(speed = 300.0)

        // When
        val result = parser.parseGpsData(data, createTestData())

        // Then
        assertEquals("速度应��300", 300.0, result.speed, 0.1)
    }

    @Test
    fun RP14_parseSpeed_overflow() {
        // Given: 速度溢出场景，Bit15=1, raw=0x8012 -> 实际值 = 0x0012 * 10 / 100 = 1.2 km/h
        // 模拟 overflow 场景: 速度 = 1200 km/h (需要溢出位)
        // 1200 * 100 / 10 = 12000 = 0x2EE0, overflow bit 设置: 0xAEE0
        val data = createValidGpsData20(speed = 1200.0)
        // Byte 14-15: speed with overflow bit
        data[14] = 0xAE.toByte()  // 0xA = overflow bit, 0xE = high nibble of 0x2EE0
        data[15] = 0xE0.toByte()

        // When
        val result = parser.parseGpsData(data, createTestData())

        // Then: 速度应为1200.0
        assertEquals("速度溢出时应为1200", 1200.0, result.speed, 0.1)
    }

    // ==================== 纬度解析测试 ====================

    @Test
    fun RP15_parseLatitude_normal() {
        // Given: 纬度60.1725897
        val data = createValidGpsData20(latitude = 60.1725897)

        // When
        val result = parser.parseGpsData(data, createTestData())

        // Then
        assertEquals("纬度应为60.1725897", 60.1725897, result.latitude, 0.000001)
    }

    @Test
    fun RP16_parseLatitude_negative() {
        // Given: 南纬33.8688
        val data = createValidGpsData20(latitude = -33.8688)

        // When
        val result = parser.parseGpsData(data, createTestData())

        // Then
        assertEquals("南纬应为负值", -33.8688, result.latitude, 0.0001)
    }

    @Test
    fun RP17_parseLatitude_equator() {
        // Given: 赤道位置
        val data = createValidGpsData20(latitude = 0.0)

        // When
        val result = parser.parseGpsData(data, createTestData())

        // Then
        assertEquals("赤道纬度应为0", 0.0, result.latitude, 0.000001)
    }

    // ==================== 经度解析测试 ====================

    @Test
    fun RP18_parseLongitude_normal() {
        // Given: 经度24.9376543
        val data = createValidGpsData20(longitude = 24.9376543)

        // When
        val result = parser.parseGpsData(data, createTestData())

        // Then
        assertEquals("经度应正确", 24.9376543, result.longitude, 0.000001)
    }

    @Test
    fun RP19_parseLongitude_negative() {
        // Given: 西经 (负值)
        val data = createValidGpsData20(longitude = -122.4194)

        // When
        val result = parser.parseGpsData(data, createTestData())

        // Then
        assertEquals("西经应为负值", -122.4194, result.longitude, 0.0001)
    }

    // ==================== 海拔解析测试 ====================

    @Test
    fun RP20_parseAltitude_normal() {
        // Given: 海拔100m
        val data = createValidGpsData20(altitude = 100.0)

        // When
        val result = parser.parseGpsData(data, createTestData())

        // Then
        assertEquals("海拔应为100.0", 100.0, result.altitude, 0.1)
    }

    @Test
    fun RP21_parseAltitude_negative() {
        // Given: 负海拔 (如死海附近)
        val data = createValidGpsData20(altitude = -430.0)

        // When
        val result = parser.parseGpsData(data, createTestData())

        // Then
        assertEquals("负海拔应正确", -430.0, result.altitude, 0.1)
    }

    @Test
    fun RP22_parseAltitude_overflow() {
        // Given: 海拔溢出场景 (altitude > 327.675m 需要 overflow bit)
        // 1600m + 500 = 2100, 2100 * 10 = 21000 = 0x5208
        // overflow: 0xD208 (overflow bit + high byte 0xD2, low byte 0x08)
        val data = createValidGpsData20(altitude = 100.0)  // 先创建基础数据
        data[12] = 0xD2.toByte()
        data[13] = 0x08.toByte()

        // When
        val result = parser.parseGpsData(data, createTestData())

        // Then: 海拔应为1600.0
        assertEquals("海拔溢出时应为1600", 1600.0, result.altitude, 0.1)
    }

    // ==================== 方位角解析测试 ====================

    @Test
    fun RP23_parseBearing_zero() {
        // Given: 方位角0度
        val data = createValidGpsData20(bearing = 0.0)

        // When
        val result = parser.parseGpsData(data, createTestData())

        // Then
        assertEquals("方位角应为0", 0.0, result.bearing, 0.01)
    }

    @Test
    fun RP24_parseBearing_normal() {
        // Given: 方位角45度
        val data = createValidGpsData20(bearing = 45.0)

        // When
        val result = parser.parseGpsData(data, createTestData())

        // Then
        assertEquals("方位角应为45", 45.0, result.bearing, 0.01)
    }

    @Test
    fun RP25_parseBearing_max() {
        // Given: 方位角359.99度 (最大值)
        val data = createValidGpsData20(bearing = 359.99)

        // When
        val result = parser.parseGpsData(data, createTestData())

        // Then
        assertEquals("方位角最大值为359.99", 359.99, result.bearing, 0.01)
    }

    // ==================== DOP值测试 ====================

    @Test
    fun RP26_parseHDOP_zero() {
        // Given: HDOP = 0
        val data = createValidGpsData20(hdop = 0.0)

        // When
        val result = parser.parseGpsData(data, createTestData())

        // Then
        assertEquals("HDOP应为0", 0.0, result.hdop, 0.01)
    }

    @Test
    fun RP27_parseHDOP_normal() {
        // Given: HDOP = 1.5
        val data = createValidGpsData20(hdop = 1.5)

        // When
        val result = parser.parseGpsData(data, createTestData())

        // Then
        assertEquals("HDOP应为1.5", 1.5, result.hdop, 0.01)
    }

    @Test
    fun RP28_parseHDOP_high() {
        // Given: HDOP = 5.0
        val data = createValidGpsData20(hdop = 5.0)

        // When
        val result = parser.parseGpsData(data, createTestData())

        // Then
        assertEquals("HDOP应为5.0", 5.0, result.hdop, 0.01)
    }

    @Test
    fun RP29_parseVDOP_normal() {
        // Given: VDOP = 2.0
        val data = createValidGpsData20(vdop = 2.0)

        // When
        val result = parser.parseGpsData(data, createTestData())

        // Then
        assertEquals("VDOP应为2.0", 2.0, result.vdop, 0.01)
    }

    // ==================== isTestReady 测试 ====================

    @Test
    fun RP30_isTestReady_goodConditions() {
        // Given: 6颗卫星，HDOP=1.0
        val data = createValidGpsData20(satellites = 6, hdop = 1.0, fixQuality = 1)

        // When
        val result = parser.parseGpsData(data, createTestData())

        // Then
        assertTrue("条件满足时应就绪", result.isTestReady)
    }

    @Test
    fun RP31_isTestReady_notEnoughSatellites() {
        // Given: 只有4颗卫星
        val data = createValidGpsData20(satellites = 4, hdop = 1.0, fixQuality = 1)

        // When
        val result = parser.parseGpsData(data, createTestData())

        // Then
        assertFalse("卫星不足，不应就绪", result.isTestReady)
    }

    @Test
    fun RP32_isTestReady_poorHDOP() {
        // Given: HDOP=2.5 (超过阈值2.0)
        val data = createValidGpsData20(satellites = 8, hdop = 2.5, fixQuality = 1)

        // When
        val result = parser.parseGpsData(data, createTestData())

        // Then
        assertFalse("HDOP过高，不应就绪", result.isTestReady)
    }

    // ==================== 频率计算测试 ====================

    @Test
    fun RP33_frequencyCalculation_worksAfterParsing() {
        // Given
        val data = createValidGpsData20(satellites = 10, hdop = 1.0, fixQuality = 1)

        // When: 解析一个数据包
        val result = parser.parseGpsData(data, createTestData())

        // Then: timestamp 应被更新
        assertTrue("timestamp应被更新", result.timestamp > 0)
    }

    // ==================== GPS时间数据解析测试 ====================

    @Test
    fun RP34_parseGpsTimeData_validData() {
        runWithMockedLog {
            // Given: 3字节时间数据
            // dateAndHour = (24-2000)*8928 + (3-1)*744 + (15-1)*24 + 14
            //            = (-1976)*8928 + 2*744 + 14*24 + 14 = -1976*8928... (负数年份不合法，换个值)
            // 实际测试: dateAndHour = 216038 (0x34C46) -> year=2000+24=2024, month=3, day=15, hour=14
            val data = ByteArray(3)
            data[0] = ((1 shl 5) or (0x0C and 0x1F)).toByte()  // sync=1, high=0x0C
            data[1] = 0xC4.toByte()
            data[2] = 0x46.toByte()

            val currentData = createTestData()

            // When
            val result = parser.parseGpsTimeData(data, currentData)

            // Then: 应标记为testReady
            assertTrue("应标记为testReady", result.isTestReady)
        }
    }

    @Test
    fun RP35_parseGpsTimeData_invalidSize() {
        runWithMockedLog {
            // Given: 数据长度不足
            val data = ByteArray(2)

            // When
            val result = parser.parseGpsTimeData(data, createTestData())

            // Then: 应返回原始数据
            assertFalse("不应修改isTestReady", result.isTestReady)
        }
    }

    // ==================== reset 测试 ====================

    @Test
    fun RP36_reset_clearsState() {
        runWithMockedLog {
            // Given: 解析一些数据
            val data = createValidGpsData20(satellites = 10, fixQuality = 1)
            repeat(5) {
                parser.parseGpsData(data, createTestData())
            }

            // When: 调用reset
            parser.reset()

            // Then: 再次解析时应能继续工作
            val result = parser.parseGpsData(data, createTestData())
            assertTrue("reset后应能继续工作", result.timestamp > 0)
        }
    }

    // ==================== 边界值测试 ====================

    @Test
    fun RP37_boundary_maxSatellitesAndFixQuality() {
        // Given: fixQuality=3 (0b11), satellites=63 (0b111111) -> byte = 0b11000000 | 0b111111 = 0xFF
        val data = createValidGpsData20()
        data[3] = 0xFF.toByte()  // fixQuality=3, satellites=63

        // When
        val result = parser.parseGpsData(data, createTestData())

        // Then
        assertEquals("fixQuality应为3", 3, result.fixQuality)
        assertEquals("卫星数应为63", 63, result.satelliteCount)
    }

    @Test
    fun RP38_boundary_equatorPrimeMeridian() {
        // Given: 赤道本初子午线位置
        val data = createValidGpsData20(latitude = 0.0, longitude = 0.0)

        // When
        val result = parser.parseGpsData(data, createTestData())

        // Then
        assertEquals("纬度应为0", 0.0, result.latitude, 0.000001)
        assertEquals("经度应为0", 0.0, result.longitude, 0.000001)
    }

    // ==================== 综合场景测试 ====================

    @Test
    fun RP39_integration_allFields() {
        // Given: 完整数据
        val data = createValidGpsData20(
            satellites = 12,
            speed = 50.0,
            latitude = 60.1725,
            longitude = 24.9375,
            altitude = 100.0,
            bearing = 45.0,
            fixQuality = 1,
            hdop = 1.0,
            vdop = 1.5
        )

        // When
        val result = parser.parseGpsData(data, createTestData())

        // Then: 所有字段都应正确解析
        assertEquals(12, result.satelliteCount)
        assertEquals(50.0, result.speed, 0.1)
        assertEquals(60.1725, result.latitude, 0.0001)
        assertEquals(24.9375, result.longitude, 0.0001)
        assertEquals(100.0, result.altitude, 0.1)
        assertEquals(45.0, result.bearing, 0.1)
        assertEquals(1, result.fixQuality)
        assertEquals(1.0, result.hdop, 0.01)
        assertEquals(1.5, result.vdop, 0.01)
    }

    @Test
    fun RP40_fixQualityAndSatellites_combined() {
        // Given: fixQuality=1 (GPS), satellites=60
        val data = createValidGpsData20(fixQuality = 1, satellites = 60)
        // Byte 3 = (1 << 6) | 60 = 0x40 | 0x3C = 0x7C

        // When
        val result = parser.parseGpsData(data, createTestData())

        // Then
        assertEquals("fixQuality应为1", 1, result.fixQuality)
        assertEquals("卫星数应为60", 60, result.satelliteCount)
    }

    // ==================== 辅助函数 ====================

    /**
     * 创建符合ESP32 20字节协议的测试数据
     *
     * Format:
     *   Byte 0:   syncBits[7:5] | timeSinceHourStart[4:0]
     *   Byte 1:   timeSinceHourStart[15:8]
     *   Byte 2:   timeSinceHourStart[7:0]
     *   Byte 3:   fixQuality[7:6] | satellites[5:0]
     *   Byte 4-7: latitude (big endian int32, degrees * 10,000,000)
     *   Byte 8-11: longitude (big endian int32, degrees * 10,000,000)
     *   Byte 12-13: altitude (big endian uint16, special encoding)
     *   Byte 14-15: speed (big endian uint16, special encoding)
     *   Byte 16-17: bearing (big endian uint16, degrees * 100)
     *   Byte 18: HDOP (raw value * 0.1)
     *   Byte 19: VDOP (raw value * 0.1)
     *
     * Altitude encoding: alt + 500 = raw / 100.0 (or raw * 10 / 100.0 if overflow)
     * Speed encoding: speed = raw / 100.0 (or raw * 10 / 100.0 if overflow)
     */
    private fun createValidGpsData20(
        satellites: Int = 12,
        speed: Double = 15.0,
        latitude: Double = 60.1725897,
        longitude: Double = 24.9376543,
        altitude: Double = 100.0,
        bearing: Double = 45.0,
        fixQuality: Int = 1,
        hdop: Double = 1.0,
        vdop: Double = 1.0
    ): ByteArray {
        val data = ByteArray(20)

        // Byte 0: sync + time (固定值)
        data[0] = 0x00  // sync=0, timeHigh=0
        data[1] = 0x00
        data[2] = 0x00

        // Byte 3: fixQuality + satellites
        val fixAndSat = ((fixQuality and 0x03) shl 6) or (satellites and 0x3F)
        data[3] = fixAndSat.toByte()

        // Byte 4-7: latitude (big endian int32)
        val latInt = (latitude * 10000000.0).toInt()
        data[4] = ((latInt shr 24) and 0xFF).toByte()
        data[5] = ((latInt shr 16) and 0xFF).toByte()
        data[6] = ((latInt shr 8) and 0xFF).toByte()
        data[7] = (latInt and 0xFF).toByte()

        // Byte 8-11: longitude (big endian int32)
        val lonInt = (longitude * 10000000.0).toInt()
        data[8] = ((lonInt shr 24) and 0xFF).toByte()
        data[9] = ((lonInt shr 16) and 0xFF).toByte()
        data[10] = ((lonInt shr 8) and 0xFF).toByte()
        data[11] = (lonInt and 0xFF).toByte()

        // Byte 12-13: altitude (特殊编码: alt + 500 = raw / 100.0)
        // 无溢出: raw = (alt + 500) * 100
        val altRaw = ((altitude + 500.0) * 100.0).toInt()
        if (altRaw <= 0x7FFF) {
            data[12] = ((altRaw shr 8) and 0xFF).toByte()
            data[13] = (altRaw and 0xFF).toByte()
        } else {
            // 溢出: raw = ((alt + 500) * 10) | 0x8000
            val altOverflowRaw = ((altitude + 500.0) * 10.0).toInt() or 0x8000
            data[12] = ((altOverflowRaw shr 8) and 0xFF).toByte()
            data[13] = (altOverflowRaw and 0xFF).toByte()
        }

        // Byte 14-15: speed (特殊编码: speed = raw / 100.0)
        // 无溢出: raw = speed * 100
        val speedRaw = (speed * 100.0).toInt()
        if (speedRaw <= 0x7FFF) {
            data[14] = ((speedRaw shr 8) and 0xFF).toByte()
            data[15] = (speedRaw and 0xFF).toByte()
        } else {
            // 溢出: raw = (speed * 10) | 0x8000
            val speedOverflowRaw = (speed * 10.0).toInt() or 0x8000
            data[14] = ((speedOverflowRaw shr 8) and 0xFF).toByte()
            data[15] = (speedOverflowRaw and 0xFF).toByte()
        }

        // Byte 16-17: bearing (big endian uint16, 度 * 100)
        val bearingInt = (bearing * 100.0).toInt()
        data[16] = ((bearingInt shr 8) and 0xFF).toByte()
        data[17] = (bearingInt and 0xFF).toByte()

        // Byte 18: HDOP
        data[18] = (hdop * 10.0).toInt().toByte()

        // Byte 19: VDOP
        data[19] = (vdop * 10.0).toInt().toByte()

        return data
    }
}
