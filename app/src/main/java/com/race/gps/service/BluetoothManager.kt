package com.race.gps.service

import android.content.Context
import android.util.Log
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.race.gps.data.model.BluetoothData
import com.race.gps.data.service.BluetoothService
import com.race.gps.data.service.impl.BleBluetoothServiceImpl
import com.race.gps.data.service.impl.MockBluetoothServiceImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 蓝牙管理器，用于管理蓝牙服务连接和数据访问
 * 单例模式，方便跨组件使用
 * 改为纯Singleton模式，不再依赖Android Service
 */
class BluetoothManager private constructor(context: Context) {
    companion object {
        private const val TAG = "RaceChronoGPS"
        
        // 单例实例
        @Volatile
        private var INSTANCE: BluetoothManager? = null
        
        /**
         * 获取单例实例
         */
        fun getInstance(context: Context): BluetoothManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: BluetoothManager(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }
    
    // 协程作用域
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    // 蓝牙服务实现
    private var bluetoothService: BluetoothService
    
    // Flow for bluetooth data
    private val _bluetoothDataFlow = MutableStateFlow(BluetoothData())
    val bluetoothDataFlow: StateFlow<BluetoothData> = _bluetoothDataFlow.asStateFlow()
    
    init {
        Log.d(TAG, "Initializing BluetoothManager")
        
        // Get configuration from preferences
        val mockMode = com.race.gps.utils.AppPreferences.getMockMode(context)
        
        // 初始化BluetoothService
        bluetoothService = when (mockMode) {
            com.race.gps.utils.AppPreferences.MOCK_MODE_PROTOCOL -> {
                Log.d(TAG, "Using Protocol Mock Bluetooth Service")
                com.race.gps.data.service.impl.ProtocolMockBluetoothServiceImpl(context)
            }
            com.race.gps.utils.AppPreferences.MOCK_MODE_SIMPLE -> {
                Log.d(TAG, "Using Simple Mock Bluetooth Service")
                MockBluetoothServiceImpl(context)
            }
            else -> {
                Log.d(TAG, "Using Real BLE Bluetooth Service")
                BleBluetoothServiceImpl(context)
            }
        }
        
        // 设置回调
        bluetoothService.setCallback(object : BluetoothService.BluetoothCallback {
            override fun onConnectionStateChanged(isConnected: Boolean) {
                Log.d(TAG, "Bluetooth connection state changed: $isConnected")
                updateBluetoothData {
                    it.copy(isConnected = isConnected)
                }
            }
            
            override fun onTestReady(isReady: Boolean) {
                Log.d(TAG, "Test ready state changed: $isReady")
                updateBluetoothData {
                    it.copy(isTestReady = isReady)
                }
            }
            
            override fun onGpsDataUpdated(data: BluetoothData) {
                // Update with full data
                updateBluetoothData { 
                    data
                }
            }
            
            override fun onError(errorMessage: String) {
                Log.e(TAG, "Bluetooth error: $errorMessage")
                updateBluetoothData {
                    it.copy(errorMessage = errorMessage)
                }
            }
        })
    }
    
    /**
     * 连接到蓝牙设备
     */
    fun connectToDevice(deviceAddress: String) {
        Log.d(TAG, "Connecting to device: $deviceAddress")
        bluetoothService.connectToDevice(deviceAddress)
    }
    
    /**
     * 断开蓝牙连接
     */
    fun disconnect() {
        Log.d(TAG, "Disconnecting from device")
        bluetoothService.disconnect()
    }
    
    /**
     * 更新蓝牙数据
     */
    private fun updateBluetoothData(updateFunction: (BluetoothData) -> BluetoothData) {
        scope.launch {
            _bluetoothDataFlow.value = updateFunction(_bluetoothDataFlow.value)
        }
    }
    
    /**
     * 观察蓝牙数据变化 (Helper method)
     */
    fun observeBluetoothData(
        lifecycleOwner: LifecycleOwner,
        collector: (BluetoothData) -> Unit
    ) {
        lifecycleOwner.lifecycleScope.launch {
            bluetoothDataFlow.collect {bluetoothData ->
                collector(bluetoothData)
            }
        }
    }
}
