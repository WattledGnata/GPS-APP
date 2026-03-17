package com.race.gps.data.service.parser

import android.location.Location
import android.util.Log
import com.race.gps.domain.model.GpsData
import java.util.Collections
import java.util.ArrayList

/**
 * RaceChrono GPS Protocol Parser
 * Handles parsing of raw byte arrays into GpsData objects.
 * Maintains state for frequency calculation and tracking (distance/time).
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
    }

    /**
     * Parses GPS Time characteristic data (3 bytes)
     */
    fun parseGpsTimeData(data: ByteArray, currentData: GpsData): GpsData {
        if (data.size < 3) {
            Log.e(TAG, "Invalid GPS time data size: ${data.size}")
            return currentData
        }

        return try {
            // Extract sync bits and dateAndHour from 3 bytes
            val syncBits = (data[0].toInt() shr 5) and 0x07
            val dateAndHour = ((data[0].toInt() and 0x1F) shl 16) or
                    ((data[1].toInt() and 0xFF) shl 8) or
                    (data[2].toInt() and 0xFF)

            // Mark test as ready once we have valid GPS time
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
     * Parses GPS Main characteristic data (28 bytes - RaceChrono protocol)
     */
    fun parseGpsData(data: ByteArray, inputData: GpsData, shouldLog: Boolean = false): GpsData {
        var currentData = inputData

        if (data.size < 28) {
            Log.e(TAG, "Invalid GPS main data size: ${data.size}, expected 28")
            return currentData
        }

        try {
            // 添加原始数据hex dump日志
            if (shouldLog) {
                val hexDump = data.joinToString("") { "%02X".format(it) }
                Log.d(TAG, "Raw GPS Data (28 bytes): $hexDump")
            }

            // Byte 0: 同步位 (低3位)
            val syncBits = data[0].toInt() and 0x07

            // Byte 1-4: 小时开始时间 (big endian)
            val timeSinceHourStart = ((data[1].toInt() and 0xFF) shl 24) or
                    ((data[2].toInt() and 0xFF) shl 16) or
                    ((data[3].toInt() and 0xFF) shl 8) or
                    (data[4].toInt() and 0xFF)

            // Byte 5: 定位质量(高2位) + 卫星数(低6位)
            val fixQuality = (data[5].toInt() shr 6) and 0x03
            val satellites = data[5].toInt() and 0x3F

            // Byte 6-9: 纬度 (big endian, 度 * 10,000,000)
            val latInt = ((data[6].toInt() and 0xFF) shl 24) or
                    ((data[7].toInt() and 0xFF) shl 16) or
                    ((data[8].toInt() and 0xFF) shl 8) or
                    (data[9].toInt() and 0xFF)
            val currentLatitude = latInt / 10000000.0

            // Byte 10-13: 经度 (big endian, 度 * 10,000,000)
            val lonInt = ((data[10].toInt() and 0xFF) shl 24) or
                    ((data[11].toInt() and 0xFF) shl 16) or
                    ((data[12].toInt() and 0xFF) shl 8) or
                    (data[13].toInt() and 0xFF)
            val currentLongitude = lonInt / 10000000.0

            // Byte 14-17: 海拔 (big endian, 米 * 100)
            val altInt = ((data[14].toInt() and 0xFF) shl 24) or
                    ((data[15].toInt() and 0xFF) shl 16) or
                    ((data[16].toInt() and 0xFF) shl 8) or
                    (data[17].toInt() and 0xFF)
            val altitudeMeters = altInt / 100.0

            // Byte 18-21: 速度 (big endian, km/h * 100)
            val speedInt = ((data[18].toInt() and 0xFF) shl 24) or
                    ((data[19].toInt() and 0xFF) shl 16) or
                    ((data[20].toInt() and 0xFF) shl 8) or
                    (data[21].toInt() and 0xFF)
            val speedKmh = speedInt / 100.0

            // Byte 22-25: 方位角 (big endian, 度 * 100)
            val bearingInt = ((data[22].toInt() and 0xFF) shl 24) or
                    ((data[23].toInt() and 0xFF) shl 16) or
                    ((data[24].toInt() and 0xFF) shl 8) or
                    (data[25].toInt() and 0xFF)
            val bearingDegrees = bearingInt / 100.0

            // Byte 26: HDOP (0.1单位)
            val hdop = (data[26].toInt() and 0xFF) / 10.0

            // Byte 27: VDOP (0.1单位)
            val vdop = (data[27].toInt() and 0xFF) / 10.0

            // 2. Frequency Calculation (Non-Critical)
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

            // 3. Tracking Calculation (Non-Critical)
            var elapsedTimeSeconds = 0.0
            try {
                val currentTime = System.currentTimeMillis()
                // Only calculate if we have a valid fix
                if (fixQuality > 0 && satellites >= 3) {
                    if (!hasStartedTracking) {
                        hasStartedTracking = true
                        startTime = currentTime
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
                                // Fallback distance calculation if Location fails
                                Log.e(TAG, "Error in distanceBetween", e)
                            }
                        }
                        lastLatitude = currentLatitude
                        lastLongitude = currentLongitude
                    }
                }

                if (hasStartedTracking) {
                    elapsedTimeSeconds = (currentTime - startTime) / 1000.0
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in tracking calculation", e)
            }

            // 4. Update Current Data
            currentData = currentData.copy(
                timestamp = System.currentTimeMillis(),
                speed = speedKmh,
                latitude = currentLatitude,
                longitude = currentLongitude,
                altitude = altitudeMeters,
                bearing = bearingDegrees,
                satelliteCount = satellites,
                hdop = hdop,
                vdop = vdop,
                frequency = gpsFrequency,
                isTestReady = satellites >= 6 && hdop < 2.0
            )

            if (shouldLog) {
                Log.d(TAG, "Parsed: Sync=$syncBits, Time=$timeSinceHourStart, " +
                        "Fix=$fixQuality, Sats=$satellites, " +
                        "Lat=${"%.7f".format(currentLatitude)}, " +
                        "Lon=${"%.7f".format(currentLongitude)}, " +
                        "Alt=${"%.1f".format(altitudeMeters)}m, " +
                        "Speed=${"%.1f".format(speedKmh)}km/h, " +
                        "Bearing=${"%.1f".format(bearingDegrees)}°, " +
                        "HDOP=$hdop, VDOP=$vdop")
            }

            return currentData
            
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing GPS data", e)
            return currentData
        }
    }
}
