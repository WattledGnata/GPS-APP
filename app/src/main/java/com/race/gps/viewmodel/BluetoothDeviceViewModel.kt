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
                
                viewModelScope.launch {
                    updateDiscoveredDevices(deviceModel)
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
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
        viewModelScope.launch(Dispatchers.IO) {
            val devices = repository.getSavedDevices()
            withContext(Dispatchers.Main) {
                _savedDevices.value = devices
            }
        }
    }

    fun startScan() {
        _scanState.value = ScanState.Scanning
        _discoveredDevices.value = emptyList()

        if (!bluetoothAdapter.isEnabled) {
            _scanState.value = ScanState.Error("Bluetooth is not enabled")
            return
        }

        // Create scan filter for RaceChrono service UUID
        val scanFilters = listOf(ScanFilter.Builder().setServiceUuid(raceChronoServiceUuid).build())
        
        // Set scan settings for low latency
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        
        // Start BLE scan with filter
        bluetoothLeScanner.startScan(scanFilters, scanSettings, scanCallback)

        // Stop scanning after 5 seconds
        viewModelScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(5000)
            stopScan()
        }
    }

    fun stopScan() {
        bluetoothLeScanner.stopScan(scanCallback)
        // Ensure LiveData update happens on main thread
        viewModelScope.launch(Dispatchers.Main) {
            _scanState.value = ScanState.Idle
        }
    }

    fun saveDevice(device: BluetoothDeviceModel) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.addDevice(device)
            loadSavedDevices()
        }
    }

    fun removeDevice(address: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.removeDevice(address)
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
        // For BLE devices, we don't need to check bonded devices
        // Just validate the address format
        return address.isNotEmpty() && address.matches(Regex("^([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})$", RegexOption.IGNORE_CASE))
    }

    private fun updateDiscoveredDevices(newDevice: BluetoothDeviceModel) {
        val currentDevices = _discoveredDevices.value ?: emptyList()
        if (!currentDevices.any { it.address == newDevice.address }) {
            val updatedDevices = currentDevices + newDevice
            _discoveredDevices.value = updatedDevices
        }
    }

    sealed class ScanState {
        object Idle : ScanState()
        object Scanning : ScanState()
        data class Error(val message: String) : ScanState()
    }
}
