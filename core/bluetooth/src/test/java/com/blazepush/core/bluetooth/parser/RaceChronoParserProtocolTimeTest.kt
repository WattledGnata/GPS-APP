package com.blazepush.core.bluetooth.parser

import com.blazepush.core.domain.model.GpsData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class RaceChronoParserProtocolTimeTest {

    private val parser = RaceChronoParser()

    @Test
    fun `gps timestamp comes from protocol date and hour plus millis within hour`() {
        val sampleTimestamp = 1773478969360L
        val syncBits = 3
        val timeData = createGpsTimeData(sampleTimestamp, syncBits)
        val mainData = createGpsMainData((sampleTimestamp % 3_600_000L).toInt(), syncBits)

        val afterTimePacket = parser.parseGpsTimeData(timeData, emptyGpsData())
        val result = parser.parseGpsData(mainData, afterTimePacket)

        // 协议对齐时：timestamp 来自协议还原值，且 isTimeSynced = true
        assertEquals(sampleTimestamp, result.timestamp)
        assertTrue(
            "协议对齐的主包必须把 isTimeSynced 写为 true",
            result.isTimeSynced
        )
    }

    @Test
    fun `gps timestamp writes sentinel when protocol time sync bits do not match main packet`() {
        val sampleTimestamp = 1773478969360L
        val timeData = createGpsTimeData(sampleTimestamp, syncBits = 1)
        val mainData = createGpsMainData((sampleTimestamp % 3_600_000L).toInt(), syncBits = 5)

        val afterTimePacket = parser.parseGpsTimeData(timeData, emptyGpsData())
        val result = parser.parseGpsData(mainData, afterTimePacket)

        // syncBits 失配：必须写 sentinel = Long.MIN_VALUE，不允许 fallback 到本地时钟
        assertEquals(Long.MIN_VALUE, result.timestamp)
        assertEquals(false, result.isTimeSynced)
    }

    @Test
    fun parseGpsData_whenNoTimeDataReceivedYet_writesSentinel() {
        // 冷启动：从未收到过时间包，protocolTimeReference == null
        val mainData = createGpsMainData(timeSinceHourStartMillis = 1_500_000, syncBits = 3)

        val result = parser.parseGpsData(mainData, emptyGpsData())

        assertEquals(false, result.isTimeSynced)
        assertEquals(Long.MIN_VALUE, result.timestamp)
    }

    @Test
    fun parseGpsData_whenSyncBitsMismatch_writesSentinel() {
        // 场景：先喂 syncBits=3 的时间包，再喂 syncBits=5 的主包。
        // 要求：主包写 sentinel (Long.MIN_VALUE)，不得 fallback 到 System.currentTimeMillis()。
        //
        // 为什么不用 Mockito.mockStatic(System::class.java)：
        // Mockito 明确禁止 mock java.lang.System，否则会引发类加载器无限递归
        // （"It is not possible to mock static methods of java.lang.System to avoid interfering
        //   with class loading what leads to infinite loops"）。
        // parser 的频率统计 / tracking 分支目前仍调用 System.currentTimeMillis()（属非本 change
        // 关心的 Non-Critical 路径，归属战役 E/F），因此也无法通过 mockStatic 验证"调用次数 = 0"。
        //
        // 替代证明：通过断言 timestamp 严格 == Long.MIN_VALUE 证明 fallback 路径被彻底移除——
        // 因为原始 fallback 代码 `?: System.currentTimeMillis()` 若仍存在，则 timestamp
        // 必定落在 (Long.MIN_VALUE, +∞) 某个大值上，绝不可能等于 Long.MIN_VALUE。
        // 端到端契约测试（战役 H 的 endToEndNeverCallsSystemCurrentTimeMillis）负责验证
        // 整个调用链路零 System.currentTimeMillis 调用。
        val sampleTimestamp = 1773478969360L
        val timeData = createGpsTimeData(sampleTimestamp, syncBits = 3)
        val mainData = createGpsMainData((sampleTimestamp % 3_600_000L).toInt(), syncBits = 5)

        val afterTimePacket = parser.parseGpsTimeData(timeData, emptyGpsData())
        val result = parser.parseGpsData(mainData, afterTimePacket)

        assertEquals(Long.MIN_VALUE, result.timestamp)
        assertEquals(false, result.isTimeSynced)
        // 旁证：sentinel 是 Long.MIN_VALUE，而任何 System.currentTimeMillis() 返回值都大于 0，
        // 两者永不相等 → 只要断言 == Long.MIN_VALUE 通过，就证明没走 fallback。
        assertNotEquals(
            "sentinel 不得与 System.currentTimeMillis 当前值相等（后者永远 > 0）",
            System.currentTimeMillis(), result.timestamp
        )
    }

    @Test
    fun parseGpsData_afterTimePacketThenMatchingMain_isTimeSyncedTrue() {
        val sampleTimestamp = 1773478969360L
        val syncBits = 3
        val timeData = createGpsTimeData(sampleTimestamp, syncBits)
        val timeSinceHourStart = (sampleTimestamp % 3_600_000L).toInt()
        val mainData = createGpsMainData(timeSinceHourStart, syncBits)

        val afterTimePacket = parser.parseGpsTimeData(timeData, emptyGpsData())
        val result = parser.parseGpsData(mainData, afterTimePacket)

        assertEquals(true, result.isTimeSynced)
        // 协议还原值 = hourStartMillis + timeSinceHourStart
        val expectedHourStartMillis = sampleTimestamp - (sampleTimestamp % 3_600_000L)
        assertEquals(expectedHourStartMillis + timeSinceHourStart, result.timestamp)
    }

    @Test
    fun parseGpsData_recoveryAfterDesync_switchesBackToSynced() {
        val sampleTimestamp = 1773478969360L
        val timeSinceHourStart = (sampleTimestamp % 3_600_000L).toInt()

        // 先喂时间包 syncBits=3 建立参考
        val timeData = createGpsTimeData(sampleTimestamp, syncBits = 3)
        var data = parser.parseGpsTimeData(timeData, emptyGpsData())

        // 3 帧 syncBits=5 主包（与 time 包 syncBits=3 不匹配）
        val desyncMain = createGpsMainData(timeSinceHourStart, syncBits = 5)
        repeat(3) {
            data = parser.parseGpsData(desyncMain, data)
            assertEquals("失配帧必须为 sentinel", Long.MIN_VALUE, data.timestamp)
            assertEquals("失配帧必须 isTimeSynced=false", false, data.isTimeSynced)
        }

        // 第 4 帧 syncBits=3，与 time 包对齐 → 应恢复同步
        val syncedMain = createGpsMainData(timeSinceHourStart, syncBits = 3)
        data = parser.parseGpsData(syncedMain, data)

        assertEquals("恢复后必须 isTimeSynced=true", true, data.isTimeSynced)
        val expectedHourStartMillis = sampleTimestamp - (sampleTimestamp % 3_600_000L)
        assertEquals(expectedHourStartMillis + timeSinceHourStart, data.timestamp)
    }

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
        fixQuality = 0
    )

    private fun createGpsTimeData(timestampMillis: Long, syncBits: Int = 0): ByteArray {
        val calendar = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = timestampMillis
        }
        val yearOffset = (calendar.get(Calendar.YEAR) - 2000).coerceAtLeast(0)
        val month = calendar.get(Calendar.MONTH)
        val day = calendar.get(Calendar.DAY_OF_MONTH) - 1
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val dateAndHour = yearOffset * 8928 + month * 744 + day * 24 + hour

        return byteArrayOf(
            (((syncBits and 0x07) shl 5) or (dateAndHour shr 16 and 0x1F)).toByte(),
            ((dateAndHour shr 8) and 0xFF).toByte(),
            (dateAndHour and 0xFF).toByte()
        )
    }

    private fun createGpsMainData(timeSinceHourStartMillis: Int, syncBits: Int = 0): ByteArray {
        val data = ByteArray(20)
        val encodedTime = timeSinceHourStartMillis / 2
        data[0] = (((syncBits and 0x07) shl 5) or (encodedTime shr 16 and 0x1F)).toByte()
        data[1] = ((encodedTime shr 8) and 0xFF).toByte()
        data[2] = (encodedTime and 0xFF).toByte()
        data[3] = ((1 shl 6) or 12).toByte()
        return data
    }
}
