package com.race.gps.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * 蓝牙管理器，用于管理蓝牙服务连接和数据访问
 * 单例模式，方便跨组件使用
 */
class BluetoothManager private constructor() {
    companion object {
        private const val TAG = "RaceChronoGPS"
        
        // 单例实例
        @Volatile
        private var INSTANCE: BluetoothManager? = null
        
        /**
         * 获取单例实例
         */
        fun getInstance(): BluetoothManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: BluetoothManager().also {
                    INSTANCE = it
                }
            }
        }
    }
    
    // 蓝牙服务连接状态
    private var isServiceConnected = false
    
    // 蓝牙服务实例
    private var bluetoothService: BluetoothForegroundService? = null
    
    // 保存的设备地址，用于服务连接后自动连接
    private var pendingDeviceAddress: String? = null
    
    // Flow for bluetooth data
    private val _bluetoothDataFlow = MutableStateFlow(com.race.gps.data.model.BluetoothData())
    val bluetoothDataFlow: StateFlow<com.race.gps.data.model.BluetoothData> = _bluetoothDataFlow.asStateFlow()
    
    // 服务连接回调
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "Bluetooth service connected")
            val binder = service as BluetoothForegroundService.LocalBinder
            bluetoothService = binder.getService()
            isServiceConnected = true
            
            // 订阅服务的Flow
            subscribeToServiceFlow()
            
            // 如果有等待连接的设备地址，立即连接
            pendingDeviceAddress?.let {
                Log.d(TAG, "Connecting to pending device: $it")
                bluetoothService?.connectToDevice(it)
                pendingDeviceAddress = null
            }
        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "Bluetooth service disconnected")
            bluetoothService = null
            isServiceConnected = false
            
            // 重置数据
            _bluetoothDataFlow.value = com.race.gps.data.model.BluetoothData()
        }
    }
    
    /**
     * 绑定到蓝牙服务
     */
    fun bindService(context: Context) {
        if (!isServiceConnected) {
            Log.d(TAG, "Binding to Bluetooth service")
            val intent = Intent(context, BluetoothForegroundService::class.java)
            context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            
            // 同时启动服务，确保它在后台运行
            context.startService(intent)
        }
    }
    
    /**
     * 取消绑定蓝牙服务
     */
    fun unbindService(context: Context) {
        if (isServiceConnected) {
            Log.d(TAG, "Unbinding from Bluetooth service")
            context.unbindService(serviceConnection)
            isServiceConnected = false
        }
    }
    
    /**
     * 连接到蓝牙设备
     */
    fun connectToDevice(deviceAddress: String) {
        if (isServiceConnected) {
            Log.d(TAG, "Connecting to device: $deviceAddress")
            bluetoothService?.connectToDevice(deviceAddress)
        } else {
            Log.d(TAG, "Bluetooth service not connected, saving device address for later connection: $deviceAddress")
            pendingDeviceAddress = deviceAddress
        }
    }
    
    /**
     * 断开蓝牙连接
     */
    fun disconnect() {
        if (isServiceConnected) {
            Log.d(TAG, "Disconnecting from device")
            bluetoothService?.disconnect()
        } else {
            Log.e(TAG, "Cannot disconnect: Bluetooth service not connected")
        }
    }
    
    /**
     * 订阅服务的Flow
     */
    private fun subscribeToServiceFlow() {
        bluetoothService?.let {service ->
            // 启动协程收集服务的Flow数据
            kotlinx.coroutines.GlobalScope.launch {
                service.bluetoothDataFlow.collect {bluetoothData ->
                    _bluetoothDataFlow.value = bluetoothData
                }
            }
        }
    }
    
    /**
     * 观察蓝牙数据变化
     */
    fun observeBluetoothData(
        lifecycleOwner: LifecycleOwner,
        collector: (com.race.gps.data.model.BluetoothData) -> Unit
    ) {
        lifecycleOwner.lifecycleScope.launch {
            bluetoothDataFlow.collect {bluetoothData ->
                collector(bluetoothData)
            }
        }
    }
}