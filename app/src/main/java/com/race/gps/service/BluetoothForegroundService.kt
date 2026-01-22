package com.race.gps.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import com.race.gps.data.model.BluetoothData
import com.race.gps.data.service.BluetoothService
import com.race.gps.data.service.impl.BleBluetoothServiceImpl
import com.race.gps.data.service.impl.MockBluetoothServiceImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import android.util.Log

/**
 * 蓝牙服务，用于管理蓝牙连接和数据传输
 * 跨越多个页面，通过Flow分发数据
 */
class BluetoothForegroundService : Service() {
    companion object {
        private const val TAG = "RaceChronoGPS"
        private const val USE_MOCK_BLUETOOTH = false // 设置为true使用mock蓝牙服务，false使用真实BLE服务
    }
    
    // Binder for clients to access this service
    private val binder = LocalBinder()
    
    // 当前蓝牙连接地址
    private var currentDeviceAddress: String? = null
    
    // 蓝牙服务实现
    private lateinit var bluetoothService: BluetoothService
    
    // Flow for bluetooth data
    private val _bluetoothDataFlow = MutableStateFlow(BluetoothData())
    val bluetoothDataFlow: StateFlow<BluetoothData> = _bluetoothDataFlow.asStateFlow()
    
    // 协程作用域
    private val serviceScope = CoroutineScope(Dispatchers.Default)
    
    /**
     * Class used for the client Binder.  Because we know this service always
     * runs in the same process as its clients, we don't need to deal with IPC.
     */
    inner class LocalBinder : Binder() {
        // Return this instance of BluetoothForegroundService so clients can call public methods
        fun getService(): BluetoothForegroundService = this@BluetoothForegroundService
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "BluetoothForegroundService onCreate")
        
        // 初始化BluetoothService
        initializeBluetoothService()
    }
    
    override fun onBind(intent: Intent): IBinder {
        Log.d(TAG, "BluetoothForegroundService onBind")
        return binder
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "BluetoothForegroundService onStartCommand")
        return START_STICKY
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "BluetoothForegroundService onDestroy")
        
        // 关闭蓝牙服务
        bluetoothService.close()
    }
    
    /**
     * 初始化蓝牙服务
     */
    private fun initializeBluetoothService() {
        // 根据标志选择蓝牙服务实现
        bluetoothService = if (USE_MOCK_BLUETOOTH) {
            Log.d(TAG, "Using Mock Bluetooth Service")
            MockBluetoothServiceImpl(this)
        } else {
            Log.d(TAG, "Using Real BLE Bluetooth Service")
            BleBluetoothServiceImpl(this)
        }

        // 设置蓝牙回调
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
            
            override fun onSpeedUpdated(speedKmh: Double) {
                // 更新速度
                updateBluetoothData {
                    it.copy(speed = speedKmh)
                }
            }
            
            override fun onSatelliteCountUpdated(satelliteCount: Int) {
                // 更新卫星数
                updateBluetoothData {
                    it.copy(satelliteCount = satelliteCount)
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
        currentDeviceAddress = deviceAddress
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
        serviceScope.launch {
            _bluetoothDataFlow.value = updateFunction(_bluetoothDataFlow.value)
        }
    }
}