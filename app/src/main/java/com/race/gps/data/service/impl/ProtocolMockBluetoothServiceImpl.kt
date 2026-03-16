package com.race.gps.data.service.impl

import android.content.Context
import android.util.Log
import com.race.gps.data.model.BluetoothData
import com.race.gps.data.service.BluetoothService
import com.race.gps.data.service.parser.RaceChronoParser
import kotlinx.coroutines.*
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI

/**
 * Protocol-level Mock Bluetooth Service.
 * Simulates raw byte arrays and feeds them into the shared parser.
 * This allows testing the parsing logic, tracking logic, and error handling.
 */
class ProtocolMockBluetoothServiceImpl(context: Context? = null) : BluetoothService {

    companion object {
        private const val TAG = "RaceChronoMock"
        private const val SIMULATION_DELAY = 100L // 10Hz update rate
    }

    private var callback: BluetoothService.BluetoothCallback? = null
    private val parser = RaceChronoParser()
    private val scope = CoroutineScope(Dispatchers.Default)
    
    private var isConnected = false
    private var isSimulationRunning = false
    
    // Simulation state
    private var currentData = BluetoothData()
    private var simLatitude = 31.2304 // Shanghai
    private var simLongitude = 121.4737
    private var simHeading = 0.0
    private var simSpeedKmh = 0.0
    private var simAltitude = 100.0
    private var simTime = System.currentTimeMillis()

    override fun connectToDevice(deviceAddress: String?) {
        Log.d(TAG, "Mock: Connecting...")
        isConnected = true
        currentData = currentData.copy(isConnected = true)
        callback?.onConnectionStateChanged(true)
        
        startSimulation()
    }

    override fun disconnect() {
        Log.d(TAG, "Mock: Disconnecting...")
        isConnected = false
        isSimulationRunning = false
        parser.reset()
        currentData = currentData.copy(isConnected = false, isTestReady = false)
        callback?.onConnectionStateChanged(false)
        scope.coroutineContext.cancelChildren()
    }

    override fun setCallback(callback: BluetoothService.BluetoothCallback?) {
        this.callback = callback
    }

    override fun close() {
        disconnect()
    }

    // Log throttling
    private var lastLogTime = 0L
    private val LOG_INTERVAL = 5000L // 5 seconds
    
    private fun startSimulation() {
        if (isSimulationRunning) return
        isSimulationRunning = true
        
        scope.launch {
            // Wait a bit before starting data
            delay(1000)
            
            // Send Time Packet first to mark test ready
            val timePacket = generateTimePacket()
            val dataAfterTime = parser.parseGpsTimeData(timePacket, currentData)
            if (dataAfterTime != currentData) {
                currentData = dataAfterTime
                if (currentData.isTestReady) {
                    callback?.onTestReady(true)
                }
            }

            // Main loop
            while (isActive && isSimulationRunning) {
                updateSimulationState()
                
                val currentTime = System.currentTimeMillis()
                val shouldLog = (currentTime - lastLogTime) >= LOG_INTERVAL
                if (shouldLog) {
                    lastLogTime = currentTime
                }
                
                val mainPacket = generateMainPacket()
                val newData = parser.parseGpsData(mainPacket, currentData, shouldLog)
                currentData = newData
                callback?.onGpsDataUpdated(currentData)
                
                delay(SIMULATION_DELAY)
            }
        }
    }

    private fun updateSimulationState() {
        // Simulate movement
        // Accelerate up to 100 km/h then decelerate
        val timeSec = (System.currentTimeMillis() / 1000.0) % 60
        simSpeedKmh = if (timeSec < 30) {
            timeSec * 3.3 // Accelerate
        } else {
            (60 - timeSec) * 3.3 // Decelerate
        }
        
        // Move in a circle
        simHeading = (simHeading + 1.0) % 360.0
        
        // Simple coordinate update (approximation)
        // 1 deg lat = ~111km, 1 deg lon = ~111km * cos(lat)
        val speedMs = simSpeedKmh / 3.6
        val distMoved = speedMs * (SIMULATION_DELAY / 1000.0) // meters
        
        val latChange = (distMoved * cos(Math.toRadians(simHeading))) / 111000.0
        val lonChange = (distMoved * sin(Math.toRadians(simHeading))) / (111000.0 * cos(Math.toRadians(simLatitude)))
        
        simLatitude += latChange
        simLongitude += lonChange
        
        simTime = System.currentTimeMillis()
    }

    private fun generateTimePacket(): ByteArray {
        val bytes = ByteArray(3)
        // Just mock some time value
        val timeVal = 12345 
        bytes[0] = ((0 shl 5) or ((timeVal shr 16) and 0x1F)).toByte()
        bytes[1] = ((timeVal shr 8) and 0xFF).toByte()
        bytes[2] = (timeVal and 0xFF).toByte()
        return bytes
    }

    private fun generateMainPacket(): ByteArray {
        val bytes = ByteArray(20)
        
        // Byte 0-2: Time since hour (mocked)
        val timeSinceHour = (simTime % 3600000).toInt()
        bytes[0] = ((0 shl 5) or ((timeSinceHour shr 16) and 0x1F)).toByte()
        bytes[1] = ((timeSinceHour shr 8) and 0xFF).toByte()
        bytes[2] = (timeSinceHour and 0xFF).toByte()
        
        // Byte 3: Fix Quality (2 bits) | Satellites (6 bits)
        val fixQuality = 3 // 3D Fix
        val satellites = 12
        bytes[3] = ((fixQuality shl 6) or (satellites and 0x3F)).toByte()
        
        // Byte 4-7: Latitude
        val latVal = (simLatitude * 10000000).toInt()
        bytes[4] = ((latVal shr 24) and 0xFF).toByte()
        bytes[5] = ((latVal shr 16) and 0xFF).toByte()
        bytes[6] = ((latVal shr 8) and 0xFF).toByte()
        bytes[7] = (latVal and 0xFF).toByte()
        
        // Byte 8-11: Longitude
        val lonVal = (simLongitude * 10000000).toInt()
        bytes[8] = ((lonVal shr 24) and 0xFF).toByte()
        bytes[9] = ((lonVal shr 16) and 0xFF).toByte()
        bytes[10] = ((lonVal shr 8) and 0xFF).toByte()
        bytes[11] = (lonVal and 0xFF).toByte()
        
        // Byte 12-13: Altitude
        val altVal = ((simAltitude + 500.0) * 10).toInt()
        bytes[12] = ((altVal shr 8) and 0xFF).toByte()
        bytes[13] = (altVal and 0xFF).toByte()
        
        // Byte 14-15: Speed
        // Use high precision mode (speed * 100) if < 327.68 km/h
        val speedVal = (simSpeedKmh * 100).toInt()
        if (speedVal < 0x8000) {
            bytes[14] = ((speedVal shr 8) and 0xFF).toByte()
            bytes[15] = (speedVal and 0xFF).toByte()
        } else {
            // Fallback to low precision (speed * 10) + flag
            val lowPrecSpeed = (simSpeedKmh * 10).toInt() or 0x8000
            bytes[14] = ((lowPrecSpeed shr 8) and 0xFF).toByte()
            bytes[15] = (lowPrecSpeed and 0xFF).toByte()
        }
        
        // Byte 16-17: Bearing
        val bearVal = (simHeading * 100).toInt()
        bytes[16] = ((bearVal shr 8) and 0xFF).toByte()
        bytes[17] = (bearVal and 0xFF).toByte()
        
        // Byte 18-19: HDOP, VDOP
        bytes[18] = (10).toByte() // 1.0
        bytes[19] = (10).toByte() // 1.0
        
        return bytes
    }
}
