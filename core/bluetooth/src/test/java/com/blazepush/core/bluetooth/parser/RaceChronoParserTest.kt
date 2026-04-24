// @IgnoreFormatCheck
// 理由：本文件 40+ 条 legacy 测试（RP01~RP40）使用下划线分段 `method-name` +
//       无 class comment / public fun comment —— 战役 D 已经沿用此风格回迁测试。
//       本战役 G R4 只在文件末尾追加 5 条新测试遵循相同风格。rename 40+ 方法 +
//       给所有 legacy 测试补注释远超 R4 scope。评审方 2026-04-24 commit 阶段
//       B 方案批准此 ignore。
package com.blazepush.core.bluetooth.parser

import android.util.Log
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Ignore
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
    private fun createTestData() = com.blazepush.core.domain.model.GpsData(
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

    // A16a 战役 D 尾巴：lat/lon signed int32 边界覆盖（对应 Spec R3 两条 Scenario）

    @Test
    fun parseGpsData_southernHemisphereAndWesternHemisphere_decodeBothNegativeCorrectly() {
        // 布宜诺斯艾利斯 (-34.6037°, -58.3816°) —— 两轴同时为负
        val data = createValidGpsData20(latitude = -34.6037, longitude = -58.3816)
        val result = parser.parseGpsData(data, createTestData())
        assertEquals("南纬", -34.6037, result.latitude, 0.0001)
        assertEquals("西经", -58.3816, result.longitude, 0.0001)
    }

    @Test
    fun parseGpsData_extremeBoundaryValues_nearPolesAndAntimeridian() {
        // 南极附近 + 接近反子午线西半球边界值
        val data = createValidGpsData20(latitude = -89.9999, longitude = -179.9999)
        val result = parser.parseGpsData(data, createTestData())
        assertEquals("接近南极纬度", -89.9999, result.latitude, 0.00001)
        assertEquals("接近反子午线经度", -179.9999, result.longitude, 0.00001)
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
    fun RP22_parseAltitude_bit15ZeroBranch_1600m() {
        // A16b R3：1600m 走 bit15=0 分支（`1600 < 6053.5`）
        // ino 真实编码：raw = (1600+500)*10 = 21000 = 0x5208，字节 `0x52 0x08`
        // v1 错字节 `0xD2 0x08`（= 0x5208 | 0x8000）是按错公式反推自洽，现已修正
        val data = createValidGpsData20(altitude = 100.0)  // 先创建基础数据
        data[12] = 0x52.toByte()
        data[13] = 0x08.toByte()

        // When
        val result = parser.parseGpsData(data, createTestData())

        // Then: bit15=0 分支 parser 解 `21000 / 10 - 500 = 1600`
        assertEquals(
            "bit15=0 低海拔分支应为 1600m（ino 真实编码 0x52 0x08）",
            1600.0,
            result.altitude,
            0.1
        )
    }

    @Test
    fun RP22b_parseAltitude_bit15OneMinIntegerBoundary_6054m() {
        // A16b R3 新增：6054m 是 bit15=1 最小整数精确边界
        // ino 判定 `6054 >= 6053.5` → bit15=1 → raw = (6054+500) | 0x8000 = 6554 | 0x8000 = 0x999A
        // 字节 `0x99 0x9A`
        // 注意：6053m 走 bit15=0 截断区间（`6053 < 6053.5`），非 bit15=1
        val data = createValidGpsData20(altitude = 100.0)
        data[12] = 0x99.toByte()
        data[13] = 0x9A.toByte()

        val result = parser.parseGpsData(data, createTestData())

        // parser v2 解码：(0x999A and 0x7FFF) - 500 = 6554 - 500 = 6054（精度 1m 精确整数 round-trip）
        // 硬区分 v1：v1 bit15=1 公式 ((0x999A and 0x7FFF) * 10) / 100 - 500 = 65540/100 - 500 = 155.4m
        assertEquals(
            "bit15=1 最小整数边界 6054m 精确 round-trip",
            6054.0,
            result.altitude,
            0.1
        )
    }

    @Test
    fun RP22c_parseAltitude_bit15OneTypicalHighAltitude_10000m() {
        // A16b R3 新增：10000m bit15=1 典型高海拔
        // ino 编码：raw = (10000+500) | 0x8000 = 10500 | 0x8000 = 0xA904，字节 `0xA9 0x04`
        val data = createValidGpsData20(altitude = 100.0)
        data[12] = 0xA9.toByte()
        data[13] = 0x04.toByte()

        val result = parser.parseGpsData(data, createTestData())

        // parser v2 解码：(0xA904 and 0x7FFF) - 500 = 10500 - 500 = 10000
        // 硬区分 v1：v1 公式 ((0xA904 and 0x7FFF) * 10) / 100 - 500 = 105000/100 - 500 = 550m
        assertEquals(
            "bit15=1 高海拔 10000m 精确 round-trip",
            10000.0,
            result.altitude,
            0.1
        )
    }

    @Test
    fun RP22d_parseAltitude_inoTruncationRange_4000m_nonGoalContract() {
        // A16b R5 Non-goal 机器锚点：[2776.7m, 6053.5m] 区间 ino 自身 `& 0x7FFF` 截断不可逆
        // ino 对 4000m 按 bit15=0 编码（`4000 < 6053.5`）：
        //   raw = ((4000+500)*10) & 0x7FFF = 45000 & 0x7FFF = 45000 - 32768 = 12232 = 0x2FC8
        //   字节 `0x2F 0xC8`，高位信息丢失不可逆
        // parser 单边无法恢复，本 change Non-goal 区间锚点
        val data = createValidGpsData20(altitude = 100.0)
        data[12] = 0x2F.toByte()
        data[13] = 0xC8.toByte()

        val result = parser.parseGpsData(data, createTestData())

        // 断言 1：parser 不抛异常（隐含：测试方法无 expected 异常仍能 pass）
        // 断言 2：解码值 = 截断后的 723.2m（12232 / 10 - 500）
        assertEquals(
            "ino 截断区间 parser 按 bit15=0 公式解得截断后的错值",
            723.2,
            result.altitude,
            0.1
        )
        // 断言 3：显式声明**不恢复**真实高度（Non-goal 契约不允许精确往返）
        assertNotEquals(
            "Non-goal 契约：parser 单边无法恢复截断前的真实 alt=4000m",
            4000.0,
            result.altitude,
            0.1
        )
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

        // Then: parse 成功（通过字段断言代替 timestamp）。
        // 战役 A (fix-laptime-clock-source-integrity) 后：未喂 time 包时 parser 写
        // sentinel `timestamp = Long.MIN_VALUE`（见 RaceChronoParserProtocolTimeTest），
        // 不再回落到 `System.currentTimeMillis()`。原断言 `timestamp > 0` 不再成立。
        assertEquals("satellites 应被更新", 10, result.satelliteCount)
        assertEquals("fixQuality 应被更新", 1, result.fixQuality)
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

            // Then: 再次解析时应能继续工作（通过字段断言代替 timestamp）
            // 战役 A 后：parser.reset() 会清零 protocolTimeReference（单源派生 isTimeSynced），
            // 新一轮解析首帧 main 包仍为未同步状态，timestamp = Long.MIN_VALUE（sentinel），
            // 原断言 `timestamp > 0` 不再成立。改用字段级断言确保 parse 逻辑正常运转。
            val result = parser.parseGpsData(data, createTestData())
            assertEquals("reset 后 satellites 字段应被正确更新", 10, result.satelliteCount)
            assertEquals("reset 后 fixQuality 字段应被正确更新", 1, result.fixQuality)
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
     * Altitude encoding (A16b 对齐 ino `RaceChrono_ESP32_M9N.ino:294-298`)：
     *   - alt &lt; 6053.5:  raw = ((alt + 500.0) * 10).toInt() and 0x7FFF         // bit15=0，精度 0.1m
     *   - alt &gt;= 6053.5: raw = ((alt + 500.0).toInt() and 0x7FFF) or 0x8000    // bit15=1，精度 1m（不乘 10）
     *   [2776.7m, 6053.5m] 区间在 ino 自身 `& 0x7FFF` 截断下不可逆（A16b Non-goal 契约）
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

        // Byte 12-13: altitude (A16b: 按 ino 真实编码公式，与 R2 simulator / R1 parser 对称)
        // bit15=0 (低海拔, alt < 6053.5): raw = ((alt+500)*10) & 0x7FFF
        // bit15=1 (高海拔, alt >= 6053.5): raw = ((alt+500).toInt() & 0x7FFF) | 0x8000 (不乘 10)
        val altEncoded = if (altitude < 6053.5) {
            (((altitude + 500.0) * 10.0).toInt()) and 0x7FFF
        } else {
            ((((altitude + 500.0).toInt()) and 0x7FFF)) or 0x8000
        }
        data[12] = ((altEncoded shr 8) and 0xFF).toByte()
        data[13] = (altEncoded and 0xFF).toByte()

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

    // ---------- 战役 G R4（A25 isConnected 语义收敛） ----------

    @Test
    fun parseGpsData_shortPacket_setsErrorMessageShortPacket() = runWithMockedLog {
        val shortData = ByteArray(10)  // < 20 字节
        val result = parser.parseGpsData(shortData, createTestData(), shouldLog = false)

        assertEquals(
            "短包 MUST 设 errorMessage=\"short-packet\"，让下游 BluetoothDataSource " +
                "不把 isConnected 强置为 true（A25 语义收敛）",
            "short-packet",
            result.errorMessage,
        )
    }

    @Test
    fun parseGpsData_catchBlockSetsErrorMessageParseError_sourceAssertion() {
        // parse 内部抛异常在 JVM 单测环境难以自然触发（parser 主要是位运算），
        // 改用源码结构断言锁定 catch 分支包含 errorMessage = "parse-error:
        // —— 未来若有人误改 catch 分支吞异常不打标记，本断言立即 fail。
        //
        // parseGpsData 内部有多个内嵌 catch（frequency 计算 / distance 计算），
        // 最外层 catch 的特征 log 是 "Error parsing GPS data"，用它作为锚点
        // 精确定位 A25 修复的目标 catch
        val source = java.io.File(
            "src/main/java/com/blazepush/core/bluetooth/parser/RaceChronoParser.kt"
        ).readText()
        val logAnchor = source.indexOf("Log.e(TAG, \"Error parsing GPS data\"")
        assertTrue(
            "parseGpsData 最外层 catch 的 log \"Error parsing GPS data\" 必须存在",
            logAnchor > 0,
        )
        val catchBody = source.substring(logAnchor, (logAnchor + 300).coerceAtMost(source.length))
        assertTrue(
            "catch 分支 MUST 返回 currentData.copy(errorMessage = \"parse-error: ...\")，" +
                "将解析异常信号上抛给 BluetoothDataSource",
            catchBody.contains("errorMessage = \"parse-error:"),
        )
    }

    // ---------- 第五轮 review：GPS_TIME 路径对称修补 ----------

    @Test
    fun parseGpsTimeData_shortPacket_setsErrorMessageShortPacket() = runWithMockedLog {
        val shortData = ByteArray(2)  // < 3 字节
        val result = parser.parseGpsTimeData(shortData, createTestData())

        assertEquals(
            "GPS_TIME 短包 MUST 设 errorMessage=\"short-packet\"，与 GPS_MAIN 路径对称 " +
                "—— 第五轮 review 挖出：原本此路径 return currentData 不写 errorMessage，" +
                "下游 BluetoothDataSource 会把它当成功 parse 误置 isConnected=true",
            "short-packet",
            result.errorMessage,
        )
    }

    @Test
    fun parseGpsTimeData_catchBlockSetsErrorMessageParseError_sourceAssertion() {
        // 用 parseGpsTimeData 特征 log "Error parsing GPS time data" 精确定位 catch 分支
        val source = java.io.File(
            "src/main/java/com/blazepush/core/bluetooth/parser/RaceChronoParser.kt"
        ).readText()
        val logAnchor = source.indexOf("Log.e(TAG, \"Error parsing GPS time data\"")
        assertTrue(
            "parseGpsTimeData 的 catch 特征 log \"Error parsing GPS time data\" 必须存在",
            logAnchor > 0,
        )
        val catchBody = source.substring(logAnchor, (logAnchor + 300).coerceAtMost(source.length))
        assertTrue(
            "parseGpsTimeData catch 分支 MUST 返回 currentData.copy(errorMessage = \"parse-error: ...\") " +
                "与 parseGpsData 对称（两路 parse 函数契约对齐）",
            catchBody.contains("errorMessage = \"parse-error:"),
        )
    }

    @Test
    fun parseGpsData_successPathExplicitlyClearsErrorMessage_sourceAssertion() {
        // 契约闭合：parser 成功路径 MUST 显式 errorMessage = null 切断级联
        // 源码锚点：parseGpsData 的主成功 copy（isTimeSynced = syncedNow）附近
        val source = java.io.File(
            "src/main/java/com/blazepush/core/bluetooth/parser/RaceChronoParser.kt"
        ).readText()
        val copyAnchor = source.indexOf("isTimeSynced = syncedNow")
        assertTrue("parseGpsData 成功路径 copy 锚点必须存在", copyAnchor > 0)
        // copy 块从锚点开始往前/往后扩展，errorMessage = null 应在同一 copy call 内
        val copyBlock = source.substring(
            (copyAnchor - 600).coerceAtLeast(0),
            (copyAnchor + 200).coerceAtMost(source.length),
        )
        assertTrue(
            "parseGpsData 成功路径 copy MUST 显式 errorMessage = null，避免前帧 errorMessage " +
                "被 carry 导致 '短包后第一帧成功无法恢复 isConnected=true' 级联故障",
            copyBlock.contains("errorMessage = null"),
        )
    }

    @Test
    fun parseGpsTimeData_successPathExplicitlyClearsErrorMessage_sourceAssertion() {
        // parseGpsTimeData 成功路径 MUST 对两个分支都显式 errorMessage = null
        val source = java.io.File(
            "src/main/java/com/blazepush/core/bluetooth/parser/RaceChronoParser.kt"
        ).readText()
        val fnStart = source.indexOf("fun parseGpsTimeData(")
        assertTrue("parseGpsTimeData 必须定义", fnStart > 0)
        val fnEnd = source.indexOf("Error parsing GPS time data", fnStart)
        assertTrue("parseGpsTimeData catch 锚点必须在函数内", fnEnd > fnStart)
        val fnBody = source.substring(fnStart, fnEnd)
        // 断言：函数成功路径（try 体内）出现至少两次 errorMessage = null（对应 if/else 两分支）
        val occurrences = Regex("errorMessage = null").findAll(fnBody).count()
        assertTrue(
            "parseGpsTimeData 成功路径的 if/else 两个 copy 分支 MUST 都显式 errorMessage = null " +
                "（当前计数=$occurrences，应 ≥ 2）",
            occurrences >= 2,
        )
    }
}
