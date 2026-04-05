package com.blazepush.core.bluetooth.parser

import com.blazepush.core.domain.model.GpsData
import org.junit.Assert.assertEquals
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

        assertEquals(sampleTimestamp, result.timestamp)
    }

    @Test
    fun `gps timestamp falls back when protocol time sync bits do not match main packet`() {
        val sampleTimestamp = 1773478969360L
        val timeData = createGpsTimeData(sampleTimestamp, syncBits = 1)
        val mainData = createGpsMainData((sampleTimestamp % 3_600_000L).toInt(), syncBits = 5)

        val afterTimePacket = parser.parseGpsTimeData(timeData, emptyGpsData())
        val beforeParse = System.currentTimeMillis()
        val result = parser.parseGpsData(mainData, afterTimePacket)
        val afterParse = System.currentTimeMillis()

        assertTrue(result.timestamp in beforeParse..afterParse)
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
        val calendar = Calendar.getInstance().apply {
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
