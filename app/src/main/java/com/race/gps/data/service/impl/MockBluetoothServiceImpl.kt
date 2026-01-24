package com.race.gps.data.service.impl

import android.content.Context
import android.util.Log
import com.race.gps.data.model.BluetoothData
import com.race.gps.data.service.BluetoothService
import kotlinx.coroutines.*

/**
 * Mock蓝牙服务实现类，用于测试，模拟蓝牙连接和数据传输
 */
class MockBluetoothServiceImpl(context: Context? = null) : BluetoothService {
    
    companion object {
        private const val TAG = "RaceChronoGPS"
        private const val SIMULATION_DELAY = 100L // 模拟数据更新间隔（毫秒）
    }
    
    // 回调监听器
    private var callback: BluetoothService.BluetoothCallback? = null
    
    // 模拟数据相关变量
    private var isConnected = false
    private var isSimulationRunning = false
    private var currentSpeed = 0.0
    private var isTestReady = false
    
    // Current Data State
    private var currentData = BluetoothData()
    
    // 协程作用域，用于管理模拟数据的协程
    private val scope = CoroutineScope(Dispatchers.IO)
    
    override fun connectToDevice(deviceAddress: String?) {
        Log.d(TAG, "Mock: Connecting to device: $deviceAddress")
        
        // 立即模拟连接成功
        isConnected = true
        currentData = currentData.copy(isConnected = true)
        callback?.onConnectionStateChanged(true)
        
        // 延迟模拟测试准备就绪
        scope.launch {
            delay(1000) // 1秒后模拟准备就绪
            isTestReady = true
            currentData = currentData.copy(isTestReady = true)
            callback?.onTestReady(true)
            
            // 开始模拟GPS数据传输
            startSimulation()
        }
    }
    
    override fun disconnect() {
        Log.d(TAG, "Mock: Disconnecting from device")
        isConnected = false
        isTestReady = false
        isSimulationRunning = false
        currentData = currentData.copy(isConnected = false, isTestReady = false)
        callback?.onConnectionStateChanged(false)
    }
    
    override fun setCallback(callback: BluetoothService.BluetoothCallback?) {
        this.callback = callback
    }
    
    override fun close() {
        Log.d(TAG, "Mock: Closing Bluetooth service")
        disconnect()
        scope.cancel() // 取消所有协程
    }
    
    /**
     * 开始模拟GPS数据传输
     */
    private fun startSimulation() {
        if (isSimulationRunning) return
        
        isSimulationRunning = true
        Log.d(TAG, "Mock: Starting GPS data simulation")
        
        scope.launch {
            while (isSimulationRunning) {
                // 模拟真实车辆速度变化，而不是简单累加
                when {
                    // 模拟怠速状态（测试未开始时）
                    !isTestReady -> {
                        // 保持低速波动（0-5 km/h）
                        currentSpeed = Math.random() * 5.0
                    }
                    // 模拟加速过程（测试进行中）
                    else -> {
                        // 模拟真实加速曲线：开始慢，然后快，最后逐渐变慢
                        // 使用正弦函数模拟加速曲线（0-100 km/h）
                        val time = System.currentTimeMillis() % 10000 // 10秒一个周期
                        val progress = (time.toDouble() / 10000) * Math.PI
                        currentSpeed = (Math.sin(progress) * 50 + 50) // 0-100 km/h
                    }
                }
                
                // 模拟卫星数量，范围5-20
                val satelliteCount = (Math.random() * 15 + 5).toInt()
                
                // Update current data
                currentData = currentData.copy(
                    speed = currentSpeed,
                    satelliteCount = satelliteCount,
                    time = System.currentTimeMillis(),
                    frequency = "10.0", // Mock 10Hz
                    dop = "1.0",
                    altitude = "100.0",
                    latitude = "0.0",
                    longitude = "0.0"
                )
                
                // 通知数据更新
                callback?.onGpsDataUpdated(currentData)
                
                // 等待下一次更新
                delay(SIMULATION_DELAY)
            }
        }
    }
    
    /**
     * 设置模拟速度（用于测试特定场景）
     * @param speed 模拟速度（km/h）
     */
    fun setMockSpeed(speed: Double) {
        currentSpeed = speed
        currentData = currentData.copy(speed = speed)
        callback?.onGpsDataUpdated(currentData)
    }
    
    /**
     * 重置模拟数据
     */
    fun resetSimulation() {
        currentSpeed = 0.0
        isTestReady = false
        isSimulationRunning = false
        currentData = currentData.copy(speed = 0.0, isTestReady = false)
        callback?.onGpsDataUpdated(currentData)
        callback?.onTestReady(false)
    }
}
