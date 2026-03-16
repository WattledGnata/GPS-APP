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
    
    // Parser instance
    private val parser = com.race.gps.data.service.parser.RaceChronoParser()
    
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
    
    // Log throttling
    private var lastLogTime = 0L
    private val LOG_INTERVAL = 5000L // 5 seconds
    
    // Bluetooth GATT callback
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)
            Log.i(TAG, "Connection state changed: status=$status, newState=$newState")
            
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
                Log.i(TAG, "Connected to GATT server")
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
                Log.i(TAG, "Disconnected from GATT server")
                bluetoothGatt = null
                // Reset test ready state on disconnect
                 currentData = currentData.copy(isTestReady = false)
                 callback?.onTestReady(false)
            }
        }
        
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            super.onServicesDiscovered(gatt, status)
            Log.i(TAG, "Services discovered: status=$status")
            
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
            
            // 1. First Enable notifications for GPS Main characteristic (Critical)
            // We chain the next enable call in onDescriptorWrite
            enableGpsMainNotifications(gatt, service)
        }
        
        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            super.onDescriptorWrite(gatt, descriptor, status)
            Log.i(TAG, "onDescriptorWrite: ${descriptor.characteristic.uuid} status=$status")
            
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val charUuid = descriptor.characteristic.uuid.toString()
                // If we just enabled Main Characteristic, now enable Time Characteristic
                if (charUuid.equals(RACECHRONO_CHARACTERISTIC_UUID, ignoreCase = true)) {
                    val service = gatt.getService(java.util.UUID.fromString(RACECHRONO_SERVICE_UUID))
                    if (service != null) {
                         // 2. Now Enable notifications for GPS Time characteristic
                         // Add a small delay to prevent BLE command flooding
                         android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                             enableGpsTimeNotifications(gatt, service)
                         }, 200)
                    }
                }
            }
        }
        
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            super.onCharacteristicChanged(gatt, characteristic)
            val data = characteristic.value
            val charUuid = characteristic.uuid.toString()
            
            val currentTime = System.currentTimeMillis()
            
            // Only allow logging for the main characteristic to ensure we see the GPS data
            val isMainChar = charUuid.equals(RACECHRONO_CHARACTERISTIC_UUID, ignoreCase = true)
            val shouldLog = isMainChar && (currentTime - lastLogTime) >= LOG_INTERVAL
            
            if (shouldLog) {
                Log.i(TAG, "Characteristic changed: $charUuid, data size: ${data.size}")
                lastLogTime = currentTime
            }
            
            if (charUuid.equals(RACECHRONO_TIME_CHARACTERISTIC_UUID, ignoreCase = true)) {
                // Parse GPS time data (3 bytes)
                parseGpsTimeData(data)
            } else if (isMainChar) {
                // Parse GPS main data (20 bytes)
                parseGpsData(data, shouldLog)
            }
        }
    }
    
    // Enable GPS time notifications
    private fun enableGpsTimeNotifications(gatt: BluetoothGatt, service: BluetoothGattService) {
        val timeCharacteristic = service.getCharacteristic(java.util.UUID.fromString(RACECHRONO_TIME_CHARACTERISTIC_UUID))
        if (timeCharacteristic != null) {
            Log.i(TAG, "Found GPS time characteristic: ${timeCharacteristic.uuid}")
            
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
            Log.i(TAG, "Enabled GPS time notifications: $success")
            
            // Enable descriptor
            val descriptor = timeCharacteristic.getDescriptor(java.util.UUID.fromString(CLIENT_CHARACTERISTIC_CONFIG))
            if (descriptor != null) {
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                val descSuccess = gatt.writeDescriptor(descriptor)
                Log.i(TAG, "Wrote GPS time descriptor: $descSuccess")
            }
        }
    }
    
    // Enable GPS main notifications
    private fun enableGpsMainNotifications(gatt: BluetoothGatt, service: BluetoothGattService) {
        val mainCharacteristic = service.getCharacteristic(java.util.UUID.fromString(RACECHRONO_CHARACTERISTIC_UUID))
        if (mainCharacteristic != null) {
            Log.i(TAG, "Found GPS main characteristic: ${mainCharacteristic.uuid}")
            
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
            Log.i(TAG, "SetCharacteristicNotification(Main): $success")
            
            if (success) {
                // Enable descriptor
                val descriptor = mainCharacteristic.getDescriptor(java.util.UUID.fromString(CLIENT_CHARACTERISTIC_CONFIG))
                if (descriptor != null) {
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    val descSuccess = gatt.writeDescriptor(descriptor)
                    Log.i(TAG, "WriteDescriptor(Main): $descSuccess")
                } else {
                    Log.e(TAG, "Main Characteristic Descriptor is null")
                }
            }
        } else {
            Log.e(TAG, "Main Characteristic not found")
        }
    }
    
    // Parse GPS time data from BLE characteristic
    private fun parseGpsTimeData(data: ByteArray) {
        val newData = parser.parseGpsTimeData(data, currentData)
        if (newData != currentData) {
            currentData = newData
            if (currentData.isTestReady) {
                callback?.onTestReady(true)
            }
        }
    }
    
    // Parse GPS main data to get speed and satellite count
    private fun parseGpsData(data: ByteArray, shouldLog: Boolean = false) {
        try {
            val newData = parser.parseGpsData(data, currentData, shouldLog)
            currentData = newData
            callback?.onGpsDataUpdated(currentData)
        } catch (e: Exception) {
            Log.e(TAG, "Critical error in parseGpsData", e)
        }
    }
    
    private fun resetTrackingState() {
        parser.reset()
        
        // Reset current data display values related to tracking
        currentData = currentData.copy(
            elapsedTime = "0.0",
            distance = "0.0",
            frequency = "0.0"
        )
    }
}
