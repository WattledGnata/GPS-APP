package com.race.gps.data.service.parser

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * RaceChronoParser 单元测试
 * 测试28字节RaceChrono协议解析逻辑
 */
class RaceChronoParserTest {

    private lateinit var parser: RaceChronoParser

    @Before
    fun setup() {
        parser = RaceChronoParser()
    }

    // 辅助函数：创建测试用的GpsData对象
    private fun createTestData() = com.race.gps.domain.model.GpsData(
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
        errorMessage = null
    )

    // ==================== 基本解析测试 ====================

    @Test
    fun RP01_parseValid28Bytes_returnsCorrectGpsData() {
        // Given: 有效的28字节GPS数据
        val data = createValidGpsData(
            satellites = 12,
            speed = 15.0,
            latitude = 60.1725897,
            longitude = 24.9376543,
            altitude = 100.0,
            bearing = 45.0
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
    }

    @Test
    fun RP02_parseInvalidSize_returnsOriginalData() {
        // Given: 数据长度不足28字节
        val shortData = ByteArray(20)

        // When: 尝试解析
        val result = parser.parseGpsData(shortData, createTestData())

        // Then: 应返回原始数据
        assertEquals("应返回原始数据", 0.0, result.latitude, 0.001)
    }

    @Test
    fun RP03_parseSatellites_correctValue() {
        // Given: 卫星数为12 (0x4C = 01001100, 低6位=001100=12)
        val data = ByteArray(28)
        data[5] = 0x4C.toByte() // fixQuality=1, sats=12

        // When
        val result = parser.parseGpsData(data, createTestData())

        // Then
        assertEquals("卫星数应为12", 12, result.satelliteCount)
    }

    @Test
    fun RP03b_parseSatellites_maxValue() {
        // Given: 卫星数为63 (0xFF低6位=111111=63)
        val data = createValidGpsData()
        data[5] = 0xFF.toByte()

        // When
        val result = parser.parseGpsData(data, createTestData())

        // Then
        assertEquals("卫星数应为63", 63, result.satelliteCount)
    }

    @Test
    fun RP04_parseSpeed_correctValue() {
        // Given: 速度15.0 km/h = 1500 (0x000005DC)
        val data = createValidGpsData(speed = 15.0)

        // When
        val result = parser.parseGpsData(data, createTestData())

        // Then
        assertEquals("速度应为15.0", 15.0, result.speed, 0.1)
    }

    @Test
    fun RP04b_parseSpeed_zeroSpeed() {
        // Given: 速度为0
        val data = createValidGpsData(speed = 0.0)

        // When
        val result = parser.parseGpsData(data, createTestData())

        // Then
        assertEquals("速度应为0", 0.0, result.speed, 0.001)
    }

    @Test
    fun RP04c_parseSpeed_highSpeed() {
        // Given: 速度300 km/h
        val data = createValidGpsData(speed = 300.0)

        // When
        val result = parser.parseGpsData(data, createTestData())

        // Then
        assertEquals("速度应为300", 300.0, result.speed, 0.1)
    }

    @Test
    fun RP05_parseLatitude_correctValue() {
        // Given: 纬度60.1725897
        val data = createValidGpsData(latitude = 60.1725897)

        // When
        val result = parser.parseGpsData(data, createTestData())

        // Then
        assertEquals("纬度应为60.1725897", 60.1725897, result.latitude, 0.000001)
    }

    @Test
    fun RP05b_parseLatitude_negativeValue() {
        // Given: 南纬33.8688
        val data = createValidGpsData(latitude = -33.8688)

        // When
        val result = parser.parseGpsData(data, createTestData())

        // Then
        assertEquals("南纬应为负值", -33.8688, result.latitude, 0.0001)
    }

    @Test
    fun RP06_parseLongitude_correctValue() {
        // Given: 经度24.9376543
        val data = createValidGpsData(longitude = 24.9376543)

        // When
        val result = parser.parseGpsData(data, createTestData())

        // Then
        assertEquals("经度应为24.9376543", 24.9376543, result.longitude, 0.000001)
    }

    @Test
    fun RP08_parseTime_bigEndianFormat() {
        // Given: 时间 0x002B4C12 = 2868018 ms
        val data = createValidGpsData()
        data[1] = 0x00.toByte()
        data[2] = 0x2B.toByte()
        data[3] = 0x4C.toByte()
        data[4] = 0x12.toByte()

        // When
        val result = parser.parseGpsData(data, createTestData())

        // Then: 解析成功
        assertNotNull("解析应成功", result)
    }

    // ==================== DOP值测试 ====================

    @Test
    fun RP09_parseHDOP_correctValue() {
        // Given: HDOP = 1.0 (编码为10，单位0.1)
        val data = createValidGpsData()
        data[26] = 10.toByte() // 1.0 * 10

        // When
        val result = parser.parseGpsData(data, createTestData())

        // Then
        assertEquals("HDOP应为1.0", 1.0, result.hdop, 0.01)
    }

    @Test
    fun RP09b_parseHDOP_highValue() {
        // Given: HDOP = 5.0 (精度较低)
        val data = createValidGpsData()
        data[26] = 50.toByte() // 5.0 * 10

        // When
        val result = parser.parseGpsData(data, createTestData())

        // Then
        assertEquals("HDOP应为5.0", 5.0, result.hdop, 0.01)
    }

    @Test
    fun RP10_parseVDOP_correctValue() {
        // Given: VDOP = 1.0
        val data = createValidGpsData()
        data[27] = 10.toByte()

        // When
        val result = parser.parseGpsData(data, createTestData())

        // Then
        assertEquals("VDOP应为1.0", 1.0, result.vdop, 0.01)
    }

    // ==================== isTestReady 测试 ====================

    @Test
    fun RP11_isTestReady_goodConditions() {
        // Given: 6颗卫星，HDOP=1.0
        val data = createValidGpsData(satellites = 6, hdop = 1.0)

        // When
        val result = parser.parseGpsData(data, createTestData())

        // Then
        assertTrue("应准备就绪", result.isTestReady)
    }

    @Test
    fun RP12_isTestReady_notEnoughSatellites() {
        // Given: 只有4颗卫星
        val data = createValidGpsData(satellites = 4, hdop = 1.0)

        // When
        val result = parser.parseGpsData(data, createTestData())

        // Then
        assertFalse("卫星不足，不应就绪", result.isTestReady)
    }

    @Test
    fun RP12b_isTestReady_poorHDOP() {
        // Given: HDOP=2.5 (精度较差)
        val data = createValidGpsData(satellites = 8, hdop = 2.5)

        // When
        val result = parser.parseGpsData(data, createTestData())

        // Then
        assertFalse("HDOP过高，不应就绪", result.isTestReady)
    }

    // ==================== 频率计算测试 ====================

    @Test
    fun RP13_frequencyCalculation_tenPacketsPerSecond() {
        // Given
        val data = createValidGpsData()

        // When: 在1秒内接收10个数据包
        repeat(10) {
            parser.parseGpsData(data, createTestData())
            Thread.sleep(50)
        }

        val result = parser.parseGpsData(data, createTestData())

        // Then: 频率应大于0
        assertTrue("频率应大于0", result.frequency > 0)
    }

    // ==================== GPS时间数据解析测试 ====================

    @Test
    fun RP14_parseGpsTimeData_validData() {
        // Given: 3字节时间数据
        val data = ByteArray(3)
        data[0] = 0x40.toByte()
        data[1] = 0x00.toByte()
        data[2] = 0x00.toByte()

        val currentData = createTestData()

        // When
        val result = parser.parseGpsTimeData(data, currentData)

        // Then
        assertTrue("应标记为testReady", result.isTestReady)
    }

    @Test
    fun RP14b_parseGpsTimeData_invalidSize() {
        // Given: 数据长度不足
        val data = ByteArray(2)

        // When
        val result = parser.parseGpsTimeData(data, createTestData())

        // Then: 应返回原始数据
        assertFalse("不应修改数据", result.isTestReady)
    }

    // ==================== reset 测试 ====================

    @Test
    fun RP15_reset_clearsState() {
        // Given: 解析一些数据
        val data = createValidGpsData()
        repeat(5) {
            parser.parseGpsData(data, createTestData())
        }

        // When: 调用reset
        parser.reset()

        // Then: 再次解析时应能继续工作
        val result = parser.parseGpsData(data, createTestData())
        assertTrue("reset后应能继续工作", result.timestamp > 0)
    }

    // ==================== 边界值测试 ====================

    @Test
    fun RP16_boundary_maxSatellites() {
        // Given: 63颗卫星（理论最大值，6位）
        val data = createValidGpsData()
        data[5] = 0xFF.toByte()

        // When
        val result = parser.parseGpsData(data, createTestData())

        // Then
        assertEquals("最大卫星数应为63", 63, result.satelliteCount)
    }

    @Test
    fun RP17_boundary_zeroSpeed() {
        // Given: 速度为0
        val data = createValidGpsData(speed = 0.0)

        // When
        val result = parser.parseGpsData(data, createTestData())

        // Then
        assertEquals("速度应为0", 0.0, result.speed, 0.001)
    }

    @Test
    fun RP18_boundary_equator() {
        // Given: 赤道位置
        val data = createValidGpsData(latitude = 0.0, longitude = 0.0)

        // When
        val result = parser.parseGpsData(data, createTestData())

        // Then
        assertEquals("纬度应为0", 0.0, result.latitude, 0.000001)
        assertEquals("经度应为0", 0.0, result.longitude, 0.000001)
    }

    // ==================== 大端序验证测试 ====================

    @Test
    fun RP19_verifyBigEndian_latLon() {
        // Given: 特定纬度值，验证字节序
        val targetLat = 60.1725897
        val data = createValidGpsData(latitude = targetLat)

        val latInt = (targetLat * 10000000.0).toInt()
        val expectedByte6 = (latInt shr 24).toByte()
        val expectedByte7 = ((latInt shr 16) and 0xFF).toByte()
        val expectedByte8 = ((latInt shr 8) and 0xFF).toByte()
        val expectedByte9 = (latInt and 0xFF).toByte()

        assertEquals("Byte 6应匹配", expectedByte6, data[6])
        assertEquals("Byte 7应匹配", expectedByte7, data[7])
        assertEquals("Byte 8应匹配", expectedByte8, data[8])
        assertEquals("Byte 9应匹配", expectedByte9, data[9])

        // When: 解析
        val result = parser.parseGpsData(data, createTestData())

        // Then: 应正确解析
        assertEquals("纬度应正确", targetLat, result.latitude, 0.000001)
    }

    // ==================== 定位质量测试 ====================

    @Test
    fun RP20_fixQuality_extraction() {
        // Given: fixQuality=3 (高2位), satellites=60 (低6位)
        val data = createValidGpsData()
        data[5] = 0xFC.toByte() // fix=3, sats=60

        // When
        val result = parser.parseGpsData(data, createTestData())

        // Then
        assertEquals("卫星数应为60", 60, result.satelliteCount)
    }

    // ==================== 辅助函数 ====================

    private fun createValidGpsData(
        satellites: Int = 12,
        speed: Double = 15.0,
        latitude: Double = 60.1725897,
        longitude: Double = 24.9376543,
        altitude: Double = 100.0,
        bearing: Double = 45.0,
        hdop: Double = 1.0,
        vdop: Double = 1.0
    ): ByteArray {
        val data = ByteArray(28)

        data[0] = 0x00
        data[1] = 0x00
        data[2] = 0x2B
        data[3] = 0x4C
        data[4] = 0x12

        val fixQuality = 1
        val fixAndSat = ((fixQuality shl 6) or (satellites and 0x3F))
        data[5] = fixAndSat.toByte()

        val latInt = (latitude * 10000000.0).toInt()
        data[6] = ((latInt shr 24) and 0xFF).toByte()
        data[7] = ((latInt shr 16) and 0xFF).toByte()
        data[8] = ((latInt shr 8) and 0xFF).toByte()
        data[9] = (latInt and 0xFF).toByte()

        val lonInt = (longitude * 10000000.0).toInt()
        data[10] = ((lonInt shr 24) and 0xFF).toByte()
        data[11] = ((lonInt shr 16) and 0xFF).toByte()
        data[12] = ((lonInt shr 8) and 0xFF).toByte()
        data[13] = (lonInt and 0xFF).toByte()

        val altInt = (altitude * 100.0).toInt()
        data[14] = ((altInt shr 24) and 0xFF).toByte()
        data[15] = ((altInt shr 16) and 0xFF).toByte()
        data[16] = ((altInt shr 8) and 0xFF).toByte()
        data[17] = (altInt and 0xFF).toByte()

        val speedInt = (speed * 100.0).toInt()
        data[18] = ((speedInt shr 24) and 0xFF).toByte()
        data[19] = ((speedInt shr 16) and 0xFF).toByte()
        data[20] = ((speedInt shr 8) and 0xFF).toByte()
        data[21] = (speedInt and 0xFF).toByte()

        val bearingInt = (bearing * 100.0).toInt()
        data[22] = ((bearingInt shr 24) and 0xFF).toByte()
        data[23] = ((bearingInt shr 16) and 0xFF).toByte()
        data[24] = ((bearingInt shr 8) and 0xFF).toByte()
        data[25] = (bearingInt and 0xFF).toByte()

        data[26] = (hdop * 10.0).toInt().toByte()
        data[27] = (vdop * 10.0).toInt().toByte()

        return data
    }
}
