package com.race.gps.data.service.impl

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.util.Log
import androidx.core.app.ActivityCompat
import com.race.gps.data.model.BluetoothData
import com.race.gps.data.service.BluetoothService
import kotlin.math.*

/**
 * BLE蓝牙服务实现类，基于Android原生BLE API实现
 */
class BleBluetoothServiceImpl(private val context: Context) : BluetoothService {
    
    companion object {
        private const val TAG = "RaceChronoGPS"
        
        // BLE UUIDs from ESP32 code
        private const val RACECHRONO_SERVICE_UUID = "00001ff8-0000-1000-8000-00805f9b34fb"
        private const val RACECHRONO_CHARACTERISTIC_UUID = "00000003-0000-1000-8000-00805f9b34fb"
        private const val RACECHRONO_TIME_CHARACTERISTIC_UUID = "00000004-0000-1000-8000-00805f9b34fb"
        private const val CLIENT_CHARACTERISTIC_CONFIG = "00002902-0000-1000-8000-00805f9b34fb"
    }
    
    // BLE相关变量
    private var bluetoothGatt: BluetoothGatt? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    
    // GPS frequency calculation - using time window counting method
    private val gpsDataTimestamps = mutableListOf<Long>()
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
    
    // Current Data State
    private var currentData = BluetoothData()

    // 回调监听器
    private var callback: BluetoothService.BluetoothCallback? = null
    
    override fun connectToDevice(deviceAddress: String?) {
        if (deviceAddress.isNullOrEmpty()) {
            callback?.onError("Device address is null or empty")
            return
        }
        
        // 获取BluetoothAdapter
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter == null) {
            callback?.onError("Bluetooth is not supported on this device")
            return
        }
        
        if (!bluetoothAdapter!!.isEnabled) {
            callback?.onError("Bluetooth is not enabled")
            return
        }
        
        try {
            val device = bluetoothAdapter!!.getRemoteDevice(deviceAddress)
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                callback?.onError("BLUETOOTH_CONNECT permission not granted")
                return
            }
            
            bluetoothGatt = device.connectGatt(context, false, gattCallback)
            Log.d(TAG, "Connecting to GATT server...")
        } catch (e: IllegalArgumentException) {
            callback?.onError("Invalid device address")
            Log.e(TAG, "Invalid device address: $deviceAddress", e)
        }
    }
    
    override fun disconnect() {
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            bluetoothGatt?.disconnect()
        }
    }
    
    override fun setCallback(callback: BluetoothService.BluetoothCallback?) {
        this.callback = callback
    }
    
    override fun close() {
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            bluetoothGatt?.close()
            bluetoothGatt = null
        }
    }
    
    // Bluetooth GATT callback
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)
            Log.d(TAG, "Connection state changed: status=$status, newState=$newState")
            
            val isConnected = newState == BluetoothGatt.STATE_CONNECTED
            
            // Update current data with connection status
            currentData = currentData.copy(isConnected = isConnected)
            
            if (!isConnected) {
                // Reset tracking state on disconnect
                resetTrackingState()
            }
            
            callback?.onConnectionStateChanged(isConnected)
            
            if (status != BluetoothGatt.GATT_SUCCESS) {
                val errorMessage = "Connection error: status=$status"
                Log.e(TAG, errorMessage)
                callback?.onError(errorMessage)
                return
            }
            
            if (isConnected) {
                Log.d(TAG, "Connected to GATT server")
                if (ActivityCompat.checkSelfPermission(
                        context,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    callback?.onError("BLUETOOTH_CONNECT permission not granted")
                    return
                }
                gatt.discoverServices()
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                Log.d(TAG, "Disconnected from GATT server")
                bluetoothGatt = null
                // Reset test ready state on disconnect
                 currentData = currentData.copy(isTestReady = false)
                 callback?.onTestReady(false)
            }
        }
        
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            super.onServicesDiscovered(gatt, status)
            Log.d(TAG, "Services discovered: status=$status")
            
            if (status != BluetoothGatt.GATT_SUCCESS) {
                val errorMessage = "Failed to discover services: status=$status"
                Log.e(TAG, errorMessage)
                callback?.onError(errorMessage)
                return
            }
            
            // Find the RaceChrono service
            val service = gatt.getService(java.util.UUID.fromString(RACECHRONO_SERVICE_UUID))
            if (service == null) {
                val errorMessage = "RaceChrono service not found"
                Log.e(TAG, errorMessage)
                callback?.onError(errorMessage)
                return
            }
            
            // Enable notifications for GPS time characteristic
            enableGpsTimeNotifications(gatt, service)
            
            // Enable notifications for GPS main characteristic
            enableGpsMainNotifications(gatt, service)
        }
        
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            super.onCharacteristicChanged(gatt, characteristic)
            val data = characteristic.value
            val charUuid = characteristic.uuid.toString()
            
            // Log.d(TAG, "Characteristic changed: $charUuid, data size: ${data.size}")
            
            if (charUuid.equals(RACECHRONO_TIME_CHARACTERISTIC_UUID, ignoreCase = true)) {
                // Parse GPS time data (3 bytes)
                parseGpsTimeData(data)
            } else if (charUuid.equals(RACECHRONO_CHARACTERISTIC_UUID, ignoreCase = true)) {
                // Parse GPS main data (20 bytes)
                parseGpsData(data)
            }
        }
    }
    
    // Enable GPS time notifications
    private fun enableGpsTimeNotifications(gatt: BluetoothGatt, service: BluetoothGattService) {
        val timeCharacteristic = service.getCharacteristic(java.util.UUID.fromString(RACECHRONO_TIME_CHARACTERISTIC_UUID))
        if (timeCharacteristic != null) {
            Log.d(TAG, "Found GPS time characteristic: ${timeCharacteristic.uuid}")
            
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                callback?.onError("BLUETOOTH_CONNECT permission not granted")
                return
            }
            
            // Enable notifications
            val success = gatt.setCharacteristicNotification(timeCharacteristic, true)
            Log.d(TAG, "Enabled GPS time notifications: $success")
            
            // Enable descriptor
            val descriptor = timeCharacteristic.getDescriptor(java.util.UUID.fromString(CLIENT_CHARACTERISTIC_CONFIG))
            if (descriptor != null) {
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                val descSuccess = gatt.writeDescriptor(descriptor)
                Log.d(TAG, "Wrote GPS time descriptor: $descSuccess")
            }
        }
    }
    
    // Enable GPS main notifications
    private fun enableGpsMainNotifications(gatt: BluetoothGatt, service: BluetoothGattService) {
        val mainCharacteristic = service.getCharacteristic(java.util.UUID.fromString(RACECHRONO_CHARACTERISTIC_UUID))
        if (mainCharacteristic != null) {
            Log.d(TAG, "Found GPS main characteristic: ${mainCharacteristic.uuid}")
            
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                callback?.onError("BLUETOOTH_CONNECT permission not granted")
                return
            }
            
            // Enable notifications
            val success = gatt.setCharacteristicNotification(mainCharacteristic, true)
            Log.d(TAG, "Enabled GPS main notifications: $success")
            
            // Enable descriptor
            val descriptor = mainCharacteristic.getDescriptor(java.util.UUID.fromString(CLIENT_CHARACTERISTIC_CONFIG))
            if (descriptor != null) {
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                val descSuccess = gatt.writeDescriptor(descriptor)
                Log.d(TAG, "Wrote GPS main descriptor: $descSuccess")
            }
        }
    }
    
    // Parse GPS time data from BLE characteristic
    private fun parseGpsTimeData(data: ByteArray) {
        if (data.size < 3) {
            Log.e(TAG, "Invalid GPS time data size: ${data.size}")
            return
        }
        
        // Extract sync bits and dateAndHour from 3 bytes
        val syncBits = (data[0].toInt() shr 5) and 0x07
        val dateAndHour = ((data[0].toInt() and 0x1F) shl 16) or 
                         (data[1].toInt() shl 8) or 
                          data[2].toInt()
        
        // Note: dateAndHour contains (Year-2000)*8928 + (Month-1)*744 + (Day-1)*24 + Hour
        
        // Mark test as ready once we have valid GPS time
        if (!currentData.isTestReady) {
             currentData = currentData.copy(isTestReady = true)
             callback?.onTestReady(true)
        }
        
        Log.d(TAG, "GPS time updated - syncBits: $syncBits, dateAndHour: $dateAndHour")
    }
    
    // Parse GPS main data to get speed and satellite count
    private fun parseGpsData(data: ByteArray) {
        if (data.size < 20) {
            Log.d(TAG, "Invalid GPS main data size: ${data.size}, expected 20")
            return
        }

        // Extract sync bits (first 3 bits of first byte)
        val syncBits = (data[0].toInt() shr 5) and 0x07
        
        // Extract time since hour start (21 bits total)
        val timeSinceHourStart = ((data[0].toInt() and 0x1F) shl 16) or 
                                 (data[1].toInt() shl 8) or 
                                 data[2].toInt()
        
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
        val hdop = data[18].toInt() / 10.0 // HDOP * 10
        val vdop = data[19].toInt() / 10.0 // VDOP * 10
        
        // Calculate GPS frequency using time window counting method
        val currentTime = System.currentTimeMillis()
        
        // Add current timestamp to list
        gpsDataTimestamps.add(currentTime)
        
        // Remove timestamps older than timeWindowMs
        val cutoffTime = currentTime - timeWindowMs
        gpsDataTimestamps.removeAll { it < cutoffTime }
        
        // Update frequency display at a lower rate to avoid excessive UI updates
        if (currentTime - lastFrequencyUpdateTime >= updateIntervalMs) {
            // Calculate frequency as number of data points in the time window
            gpsFrequency = gpsDataTimestamps.size.toDouble()
            lastFrequencyUpdateTime = currentTime
            // Log.d(TAG, "GPS frequency calculated: $gpsFrequency Hz")
        }
        
        // Calculate tracking data (elapsed time and distance)
        // Only calculate if we have a valid fix (fixQuality > 0 is typical, but depends on device)
        // Let's assume fixQuality > 0 means we have some fix
        if (fixQuality > 0 && satellites >= 3) {
            if (!hasStartedTracking) {
                hasStartedTracking = true
                startTime = currentTime
                lastLatitude = currentLatitude
                lastLongitude = currentLongitude
                totalDistance = 0.0
            } else {
                // Calculate distance from last point
                if (lastLatitude != null && lastLongitude != null) {
                    val results = FloatArray(1)
                    Location.distanceBetween(
                        lastLatitude!!, lastLongitude!!,
                        currentLatitude, currentLongitude,
                        results
                    )
                    // Only add if accuracy suggests it's movement (e.g. > 0.5m)
                    // Or just add raw distance if we trust the GPS
                    val distanceStep = results[0]
                    // Basic noise filtering - only count movement if speed is significant (> 1 km/h)
                    // or distance step is reasonable
                    if (speedKmh > 1.0) {
                        totalDistance += distanceStep / 1000.0 // Convert to km
                    }
                }
                lastLatitude = currentLatitude
                lastLongitude = currentLongitude
            }
        }
        
        val elapsedTimeSeconds = if (hasStartedTracking) {
            (currentTime - startTime) / 1000.0
        } else {
            0.0
        }
        
        // Update current data object
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
        
        // Notify callback with full data
        callback?.onGpsDataUpdated(currentData)
    }
    
    private fun resetTrackingState() {
        hasStartedTracking = false
        startTime = 0
        totalDistance = 0.0
        lastLatitude = null
        lastLongitude = null
        gpsDataTimestamps.clear()
        gpsFrequency = 0.0
        
        // Reset current data display values related to tracking
        currentData = currentData.copy(
            elapsedTime = "0.0",
            distance = "0.0",
            frequency = "0.0"
        )
    }
}
