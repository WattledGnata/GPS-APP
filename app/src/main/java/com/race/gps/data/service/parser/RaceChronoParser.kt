package com.race.gps.data.service.parser

import android.location.Location
import android.util.Log
import com.race.gps.data.model.BluetoothData
import java.util.Collections
import java.util.ArrayList

/**
 * RaceChrono GPS Protocol Parser
 * Handles parsing of raw byte arrays into BluetoothData objects.
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
    fun parseGpsTimeData(data: ByteArray, currentData: BluetoothData): BluetoothData {
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
     * Parses GPS Main characteristic data (20 bytes)
     */
    fun parseGpsData(data: ByteArray, inputData: BluetoothData): BluetoothData {
        var currentData = inputData

        if (data.size < 20) {
            Log.d(TAG, "Invalid GPS main data size: ${data.size}, expected 20")
            return currentData
        }

        try {
            // 1. Parse Basic GPS Data (Critical)
            // Extract sync bits (first 3 bits of first byte)
            val syncBits = (data[0].toInt() shr 5) and 0x07

            // Extract time since hour start (21 bits total)
            val timeSinceHourStart = ((data[0].toInt() and 0x1F) shl 16) or
                    ((data[1].toInt() and 0xFF) shl 8) or
                    (data[2].toInt() and 0xFF)

            // Extract fix quality and satellite count from 4th byte
            val fixQuality = (data[3].toInt() shr 6) and 0x03
            val satellites = data[3].toInt() and 0x3F

            // Extract latitude (4 bytes, big endian)
            val latitudeVal = ((data[4].toInt() and 0xFF) shl 24) or
                    ((data[5].toInt() and 0xFF) shl 16) or
                    ((data[6].toInt() and 0xFF) shl 8) or
                    (data[7].toInt() and 0xFF)
            val currentLatitude = latitudeVal / 10000000.0

            // Extract longitude (4 bytes, big endian)
            val longitudeVal = ((data[8].toInt() and 0xFF) shl 24) or
                    ((data[9].toInt() and 0xFF) shl 16) or
                    ((data[10].toInt() and 0xFF) shl 8) or
                    (data[11].toInt() and 0xFF)
            val currentLongitude = longitudeVal / 10000000.0

            // Extract altitude (2 bytes, big endian)
            val altitudeVal = ((data[12].toInt() and 0xFF) shl 8) or (data[13].toInt() and 0xFF)
            val altitudeMeters = altitudeVal / 10.0 - 500.0 // Convert to meters with offset

            // Extract speed (2 bytes, big endian)
            val speedVal = ((data[14].toInt() and 0xFF) shl 8) or (data[15].toInt() and 0xFF)
            val speedKmh = if (speedVal < 0x8000) {
                // Speed is in km/h * 100
                speedVal / 100.0
            } else {
                // Speed is in km/h * 10
                (speedVal and 0x7FFF) / 10.0
            }

            // Extract bearing (2 bytes, big endian)
            val bearing = ((data[16].toInt() and 0xFF) shl 8) or (data[17].toInt() and 0xFF)
            val bearingDegrees = bearing / 100.0

            // Extract HDOP and VDOP (1 byte each)
            val hdop = (data[18].toInt() and 0xFF) / 10.0 // HDOP * 10
            val vdop = (data[19].toInt() and 0xFF) / 10.0 // VDOP * 10

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
                time = System.currentTimeMillis(),
                satelliteCount = satellites,
                dop = String.format("%.2f", hdop),
                positionType = fixQuality,
                azimuth = bearingDegrees.toInt(),
                altitude = String.format("%.1f", altitudeMeters),
                altitudeError = String.format("%.2f", vdop),
                latitude = String.format("%.7f", currentLatitude),
                longitude = String.format("%.7f", currentLongitude),
                elapsedTime = String.format("%.1f", elapsedTimeSeconds),
                distance = String.format("%.2f", totalDistance),
                speed = speedKmh,
                frequency = String.format("%.1f", gpsFrequency)
            )

            return currentData

        } catch (e: Exception) {
            Log.e(TAG, "Error parsing GPS data", e)
            return currentData
        }
    }
}
