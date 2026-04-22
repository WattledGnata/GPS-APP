package com.blazepush.core.bluetooth.parser

import android.location.Location
import android.util.Log
import com.blazepush.core.domain.model.GpsData
import java.util.Collections
import java.util.ArrayList

/**
 * RaceChrono GPS Protocol Parser
 * Handles parsing of raw byte arrays into GpsData objects.
 * Maintains state for frequency calculation and tracking (distance/time).
 *
 * Protocol: ESP32 20-byte GPS Main Data + 3-byte GPS Time Data
 * See: docs/RaceChrono_BLE_Protocol.md
 */
class RaceChronoParser {

    companion object {
        private const val TAG = "RaceChronoParser"
    }

    // GPS frequency calculation
    private val gpsDataTimestamps = Collections.synchronizedList(ArrayList<Long>())
    private val timeWindowMs = 1000 // 1 second time window
    private var lastFrequencyUpdateTime = 0L
    private val updateIntervalMs = 500 // Update frequency display every 500ms
    private var gpsFrequency = 0.0 // Current GPS frequency in Hz

    // Tracking state for calculations
    private var startTime: Long = 0
    private var totalDistance: Double = 0.0
    private var lastLatitude: Double? = null
    private var lastLongitude: Double? = null
    private var hasStartedTracking = false
    private var protocolTimeReference: ProtocolTimeReference? = null

    private data class ProtocolTimeReference(
        val syncBits: Int,
        val hourStartMillis: Long
    )

    /**
     * Resets all tracking state (distance, time, frequency)
     */
    fun reset() {
        hasStartedTracking = false
        startTime = 0
        totalDistance = 0.0
        lastLatitude = null
        lastLongitude = null
        gpsDataTimestamps.clear()
        gpsFrequency = 0.0
        protocolTimeReference = null
        // A8 / opsx code review C.4：`isTimeSynced` 不再作 parser 私有字段，
        // 而是从 `protocolTimeReference` 单源派生（reset 后 reference = null →
        // 下一次 parseGpsData 必判未同步 → 写 sentinel + isTimeSynced=false）。
    }

    /**
     * Parses GPS Time characteristic data (3 bytes)
     *
     * Format:
     *   Byte 0: syncBits[7:5] | dateAndHour[4:0]
     *   Byte 1: dateAndHour[15:8]
     *   Byte 2: dateAndHour[7:0]
     *
     *   dateAndHour = (year - 2000) * 8928 + (month - 1) * 744 + (day - 1) * 24 + hour
     */
    fun parseGpsTimeData(data: ByteArray, currentData: GpsData): GpsData {
        if (data.size < 3) {
            Log.e(TAG, "Invalid GPS time data size: ${data.size}, expected 3")
            return currentData
        }

        return try {
            val syncBits = (data[0].toInt() shr 5) and 0x07
            val dateAndHour = ((data[0].toInt() and 0x1F) shl 16) or
                    ((data[1].toInt() and 0xFF) shl 8) or
                    (data[2].toInt() and 0xFF)

            val yearOffset = dateAndHour / 8928
            val remainder = dateAndHour % 8928
            val month = remainder / 744
            val remainder2 = remainder % 744
            val day = remainder2 / 24
            val hour = remainder2 % 24
            val year = 2000 + yearOffset

            val calendar = java.util.Calendar.getInstance().apply {
                clear()
                set(year, month, day + 1, hour, 0, 0)
            }
            protocolTimeReference = ProtocolTimeReference(
                syncBits = syncBits,
                hourStartMillis = calendar.timeInMillis
            )

//            Log.d(TAG, "GPS Time: $year-${month + 1}-${day + 1} $hour:xx (sync=$syncBits)")

            if (!currentData.isTestReady) {
                currentData.copy(isTestReady = true)
            } else {
                currentData
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing GPS time data", e)
            currentData
        }
    }

    /**
     * Parses GPS Main characteristic data (20 bytes - ESP32 protocol)
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
     * Altitude encoding:
     *   - Bit 15 = 0: alt = raw / 100.0 - 500.0
     *   - Bit 15 = 1: alt = ((raw & 0x7FFF) * 10) / 100.0 - 500.0
     *
     * Speed encoding:
     *   - Bit 15 = 0: speed = raw / 100.0
     *   - Bit 15 = 1: speed = ((raw & 0x7FFF) * 10) / 100.0
     */
    fun parseGpsData(data: ByteArray, inputData: GpsData, shouldLog: Boolean = false): GpsData {
        var currentData = inputData

        if (data.size < 20) {
            // 注释掉高频错误日志，25Hz数据会刷屏
            // Log.e(TAG, "Invalid GPS main data size: ${data.size}, expected 20")
            return currentData
        }

        try {
            if (shouldLog) {
                val hexDump = data.joinToString("") { "%02X".format(it) }
                Log.d(TAG, "Raw GPS Data (20 bytes): $hexDump")
            }

            // Byte 0: sync + time high
            val syncBits = (data[0].toInt() shr 5) and 0x07
            val timeHigh = data[0].toInt() and 0x1F
            val timeMid = data[1].toInt() and 0xFF
            val timeLow = data[2].toInt() and 0xFF
            val timeSinceHourStart = ((timeHigh shl 16) or (timeMid shl 8) or timeLow) * 2  // 每个单位=2ms

            // Byte 3: fix + satellites
            val fixQuality = (data[3].toInt() shr 6) and 0x03
            val satellites = data[3].toInt() and 0x3F

            // Byte 4-7: latitude (big endian uint32, 度 * 10,000,000)
            val latInt = ((data[4].toInt() and 0xFF) shl 24) or
                    ((data[5].toInt() and 0xFF) shl 16) or
                    ((data[6].toInt() and 0xFF) shl 8) or
                    (data[7].toInt() and 0xFF)
            val currentLatitude = (latInt.toLong() and 0xFFFFFFFFL) / 10000000.0

            // Byte 8-11: longitude (big endian uint32, 度 * 10,000,000)
            val lonInt = ((data[8].toInt() and 0xFF) shl 24) or
                    ((data[9].toInt() and 0xFF) shl 16) or
                    ((data[10].toInt() and 0xFF) shl 8) or
                    (data[11].toInt() and 0xFF)
            val currentLongitude = (lonInt.toLong() and 0xFFFFFFFFL) / 10000000.0

            // Byte 12-13: altitude special encoding (big endian uint16)
            val altRaw = ((data[12].toInt() and 0xFF) shl 8) or (data[13].toInt() and 0xFF)
            val altitudeMeters = if ((altRaw and 0x8000) == 0) {
                // bit15=0: alt = raw / 100 - 500 (精度 0.01m, 范围 -500 ~ 277.67m)
                (altRaw and 0x7FFF) / 100.0 - 500.0
            } else {
                // bit15=1: alt = (raw & 0x7FFF) * 10 / 100 - 500 (精度 0.1m, 扩展范围到 6052.7m)
                ((altRaw and 0x7FFF) * 10.0) / 100.0 - 500.0
            }

            // Byte 14-15: speed special encoding (big endian uint16)
            val speedRaw = ((data[14].toInt() and 0xFF) shl 8) or (data[15].toInt() and 0xFF)
            val speedKmh = if ((speedRaw and 0x8000) == 0) {
                (speedRaw and 0x7FFF) / 100.0
            } else {
                ((speedRaw and 0x7FFF) * 10) / 100.0
            }

            // Byte 16-17: bearing (big endian uint16, 度 * 100)
            val bearingInt = ((data[16].toInt() and 0xFF) shl 8) or (data[17].toInt() and 0xFF)
            val bearingDegrees = bearingInt / 100.0

            // Byte 18: HDOP (0.1单位)
            val hdop = (data[18].toInt() and 0xFF) / 10.0

            // Byte 19: VDOP (0.1单位)
            val vdop = (data[19].toInt() and 0xFF) / 10.0

            // Frequency Calculation (Non-Critical)
            try {
                val currentTime = System.currentTimeMillis()
                synchronized(gpsDataTimestamps) {
                    gpsDataTimestamps.add(currentTime)
                    val cutoffTime = currentTime - timeWindowMs
                    gpsDataTimestamps.removeAll { it < cutoffTime }

                    if (currentTime - lastFrequencyUpdateTime >= updateIntervalMs) {
                        gpsFrequency = gpsDataTimestamps.size.toDouble()
                        lastFrequencyUpdateTime = currentTime
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error calculating frequency", e)
            }

            // Tracking Calculation (Non-Critical)
            try {
                // Only calculate if we have a valid fix
                if (fixQuality > 0 && satellites >= 3) {
                    if (!hasStartedTracking) {
                        hasStartedTracking = true
                        startTime = System.currentTimeMillis()
                        lastLatitude = currentLatitude
                        lastLongitude = currentLongitude
                        totalDistance = 0.0
                        if (shouldLog) Log.d(TAG, "Tracking started: Lat=$currentLatitude, Lon=$currentLongitude")
                    } else {
                        val lastLat = lastLatitude
                        val lastLon = lastLongitude

                        if (lastLat != null && lastLon != null) {
                            val results = FloatArray(1)
                            try {
                                Location.distanceBetween(
                                    lastLat, lastLon,
                                    currentLatitude, currentLongitude,
                                    results
                                )
                                val distanceStep = results[0]
                                if (speedKmh > 1.0) {
                                    totalDistance += distanceStep / 1000.0
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error in distanceBetween", e)
                            }
                        }
                        lastLatitude = currentLatitude
                        lastLongitude = currentLongitude
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in tracking calculation", e)
            }

            // 协议时间对齐判定（A8 / opsx code review C.4：从单源 `protocolTimeReference` 派生）：
            //   - protocolTimeReference == null：从未收到过时间包 → 未同步
            //   - syncBits 失配：当前帧与最近一次时间包不对齐 → 未同步
            // 未同步时写入 sentinel (Long.MIN_VALUE)，严禁 fallback 到本地系统时钟。
            val reference = protocolTimeReference
            val syncedNow = reference != null && reference.syncBits == syncBits
            val protocolTimestamp = if (syncedNow) {
                reference!!.hourStartMillis + timeSinceHourStart
            } else {
                Long.MIN_VALUE
            }

            // Update Current Data
            currentData = currentData.copy(
                timestamp = protocolTimestamp,
                speed = speedKmh,
                latitude = currentLatitude,
                longitude = currentLongitude,
                altitude = altitudeMeters,
                bearing = bearingDegrees,
                satelliteCount = satellites,
                hdop = hdop,
                vdop = vdop,
                frequency = gpsFrequency,
                isTestReady = satellites >= 6 && hdop < 2.0,
                fixQuality = fixQuality,
                isTimeSynced = syncedNow
            )

            if (shouldLog) {
                Log.d(TAG, "Parsed: Sync=$syncBits, Time=$timeSinceHourStart, " +
                        "Fix=$fixQuality, Sats=$satellites, " +
                        "Lat=${"%.7f".format(currentLatitude)}, " +
                        "Lon=${"%.7f".format(currentLongitude)}, " +
                        "Alt=${"%.1f".format(altitudeMeters)}m, " +
                        "Speed=${"%.1f".format(speedKmh)}km/h, " +
                        "Bearing=${"%.1f".format(bearingDegrees)}, " +
                        "HDOP=$hdop, VDOP=$vdop")
            }

            return currentData

        } catch (e: Exception) {
            Log.e(TAG, "Error parsing GPS data", e)
            return currentData
        }
    }
}
