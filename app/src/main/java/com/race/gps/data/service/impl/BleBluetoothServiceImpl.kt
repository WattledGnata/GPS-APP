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
import android.util.Log
import androidx.core.app.ActivityCompat
import com.race.gps.data.service.BluetoothService

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
    private var gpsTime = 0L // GPS time in milliseconds since hour start
    private var lastGpsTimeUpdate = 0L // Local time when we last received GPS time
    
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
            Log.d(TAG, "Connecting to GATT server for GPS time...")
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
    
    // Bluetooth GATT callback for GPS time
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)
            Log.d(TAG, "Connection state changed: status=$status, newState=$newState")
            
            val isConnected = newState == BluetoothGatt.STATE_CONNECTED
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
            
            // Enable notifications for GPS main characteristic (to get speed)
            enableGpsMainNotifications(gatt, service)
        }
        
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            super.onCharacteristicChanged(gatt, characteristic)
            val data = characteristic.value
            val charUuid = characteristic.uuid.toString()
            
            Log.d(TAG, "Characteristic changed: $charUuid, data size: ${data.size}")
            
            if (charUuid.equals(RACECHRONO_TIME_CHARACTERISTIC_UUID, ignoreCase = true)) {
                // Parse GPS time data (3 bytes)
                parseGpsTimeData(data)
            } else if (charUuid.equals(RACECHRONO_CHARACTERISTIC_UUID, ignoreCase = true)) {
                // Parse GPS main data (20 bytes) to get speed
                parseGpsMainData(data)
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
        // For our purposes, we don't need to decode the full date, just use it to validate GPS time
        
        // Update GPS time - we'll use the sync bits to estimate time since hour start
        // In a real implementation, we'd use the full GPS time protocol
        // For simplicity, we'll set a dummy GPS time for now
        gpsTime = System.currentTimeMillis() % (3600 * 1000) // Milliseconds since hour start
        lastGpsTimeUpdate = System.currentTimeMillis()
        
        // Mark test as ready once we have valid GPS time
        callback?.onTestReady(true)
        
        Log.d(TAG, "GPS time updated: $gpsTime ms, syncBits: $syncBits, dateAndHour: $dateAndHour")
    }
    
    // Parse GPS main data to get speed
    private fun parseGpsMainData(data: ByteArray) {
        if (data.size < 20) {
            Log.e(TAG, "Invalid GPS main data size: ${data.size}")
            return
        }
        
        // Extract speed from GPS main data (bytes 15-16, big endian)
        val speed = ((data[14].toInt() and 0xFF) shl 8) or (data[15].toInt() and 0xFF)
        val speedKmh = if (speed < 0x8000) {
            // Speed is in km/h * 100
            speed / 100.0
        } else {
            // Speed is in km/h * 10
            (speed and 0x7FFF) / 10.0
        }
        
        // Notify speed update
        callback?.onSpeedUpdated(speedKmh)
        
        Log.d(TAG, "Current speed from GPS: ${speedKmh}km/h")
    }
}
