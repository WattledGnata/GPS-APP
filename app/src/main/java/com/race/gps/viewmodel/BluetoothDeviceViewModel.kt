package com.race.gps.viewmodel

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.race.gps.data.model.BluetoothDeviceModel
import com.race.gps.data.repository.BluetoothDeviceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BluetoothDeviceViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "RaceChronoGPS"
    }
    
    private val repository = BluetoothDeviceRepository(application)
    private val bluetoothAdapter: BluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
    private val bluetoothLeScanner: BluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner
    
    // RaceChrono BLE Service UUID from the ESP32 code
    private val raceChronoServiceUuid = ParcelUuid.fromString("00001ff8-0000-1000-8000-00805f9b34fb")

    private val _scanState = MutableLiveData<ScanState>(ScanState.Idle)
    val scanState: LiveData<ScanState> = _scanState

    private val _discoveredDevices = MutableLiveData<List<BluetoothDeviceModel>>(emptyList())
    val discoveredDevices: LiveData<List<BluetoothDeviceModel>> = _discoveredDevices

    private val _savedDevices = MutableLiveData<List<BluetoothDeviceModel>>(emptyList())
    val savedDevices: LiveData<List<BluetoothDeviceModel>> = _savedDevices

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)
            // Check if the device advertises the RaceChrono service UUID
            if (result.scanRecord?.serviceUuids?.contains(raceChronoServiceUuid) == true) {
                val device = result.device
                val deviceModel = BluetoothDeviceModel(device.name, device.address)
                Log.d(TAG, "Discovered RaceChrono device: ${device.name} (${device.address})")
                
                viewModelScope.launch {
                    updateDiscoveredDevices(deviceModel)
                }
            } else {
                Log.v(TAG, "Discovered non-RaceChrono device: ${result.device.name} (${result.device.address})")
            }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            Log.e(TAG, "BLE scan failed with error code: $errorCode")
            _scanState.value = ScanState.Error("Scan failed with error code: $errorCode")
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            super.onBatchScanResults(results)
            results.forEach { result ->
                if (result.scanRecord?.serviceUuids?.contains(raceChronoServiceUuid) == true) {
                    val device = result.device
                    val deviceModel = BluetoothDeviceModel(device.name, device.address)
                    
                    viewModelScope.launch {
                        updateDiscoveredDevices(deviceModel)
                    }
                }
            }
        }
    }

    init {
        loadSavedDevices()
    }

    fun loadSavedDevices() {
        Log.d(TAG, "Loading saved devices...")
        viewModelScope.launch(Dispatchers.IO) {
            val devices = repository.getSavedDevices()
            Log.d(TAG, "Loaded ${devices.size} saved devices")
            withContext(Dispatchers.Main) {
                _savedDevices.value = devices
            }
        }
    }

    fun startScan() {
        Log.d(TAG, "Starting BLE scan...")
        _scanState.value = ScanState.Scanning
        _discoveredDevices.value = emptyList()

        if (!bluetoothAdapter.isEnabled) {
            Log.e(TAG, "Bluetooth is not enabled, cannot start scan")
            _scanState.value = ScanState.Error("Bluetooth is not enabled")
            return
        }

        // Create scan filter for RaceChrono service UUID
        val scanFilters = listOf(ScanFilter.Builder().setServiceUuid(raceChronoServiceUuid).build())
        
        // Set scan settings for low latency
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        
        Log.d(TAG, "Scan filters: $scanFilters")
        Log.d(TAG, "Scan settings: $scanSettings")
        
        // Start BLE scan with filter
        bluetoothLeScanner.startScan(scanFilters, scanSettings, scanCallback)

        // Stop scanning after 5 seconds
        viewModelScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(5000)
            stopScan()
        }
    }

    fun stopScan() {
        Log.d(TAG, "Stopping BLE scan...")
        bluetoothLeScanner.stopScan(scanCallback)
        // Ensure LiveData update happens on main thread
        viewModelScope.launch(Dispatchers.Main) {
            _scanState.value = ScanState.Idle
            Log.d(TAG, "BLE scan stopped")
        }
    }

    fun saveDevice(device: BluetoothDeviceModel) {
        Log.d(TAG, "Saving device: ${device.name} (${device.address})")
        viewModelScope.launch(Dispatchers.IO) {
            repository.addDevice(device)
            Log.d(TAG, "Device saved successfully")
            loadSavedDevices()
        }
    }

    fun removeDevice(address: String) {
        Log.d(TAG, "Removing device with address: $address")
        viewModelScope.launch(Dispatchers.IO) {
            repository.removeDevice(address)
            Log.d(TAG, "Device removed successfully")
            loadSavedDevices()
        }
    }

    /**
     * Check if a Bluetooth device with the given address exists and is reachable
     * For BLE devices, we don't need to check bonded devices as they don't require pairing
     * @param address Bluetooth MAC address to check
     * @return true if the address is valid (non-empty), false otherwise
     */
    fun isDeviceAvailable(address: String): Boolean {
        Log.d(TAG, "Checking device availability for address: $address")
        // For BLE devices, we don't need to check bonded devices
        // Just validate the address format
        val isValid = address.isNotEmpty() && address.matches(Regex("^([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})$", RegexOption.IGNORE_CASE))
        Log.d(TAG, "Device availability check result: $isValid")
        return isValid
    }

    private fun updateDiscoveredDevices(newDevice: BluetoothDeviceModel) {
        val currentDevices = _discoveredDevices.value ?: emptyList()
        if (!currentDevices.any { it.address == newDevice.address }) {
            val updatedDevices = currentDevices + newDevice
            _discoveredDevices.value = updatedDevices
            Log.d(TAG, "Updated discovered devices list, now has ${updatedDevices.size} devices")
        }
    }

    sealed class ScanState {
        object Idle : ScanState()
        object Scanning : ScanState()
        data class Error(val message: String) : ScanState()
    }
}
