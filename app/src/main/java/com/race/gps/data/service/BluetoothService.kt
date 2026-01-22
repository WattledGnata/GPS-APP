package com.race.gps.data.service

import com.race.gps.data.service.BluetoothService.BluetoothCallback

/**
 * Bluetooth服务接口，定义所有蓝牙相关功能
 */
interface BluetoothService {
    
    /**
     * 连接到BLE设备
     * @param deviceAddress 设备地址
     */
    fun connectToDevice(deviceAddress: String?)
    
    /**
     * 断开BLE设备连接
     */
    fun disconnect()
    
    /**
     * 设置蓝牙回调监听器
     * @param callback 回调监听器
     */
    fun setCallback(callback: BluetoothCallback?)
    
    /**
     * 关闭蓝牙服务，释放资源
     */
    fun close()
    
    /**
     * Bluetooth回调接口，用于通知调用者状态变化和数据更新
     */
    interface BluetoothCallback {
        /**
         * 连接状态变化回调
         * @param isConnected 是否已连接
         */
        fun onConnectionStateChanged(isConnected: Boolean)
        
        /**
         * 测试准备就绪回调
         * @param isReady 是否已就绪
         */
        fun onTestReady(isReady: Boolean)
        
        /**
         * GPS速度更新回调
         * @param speedKmh 速度（km/h）
         */
        fun onSpeedUpdated(speedKmh: Double)
        
        /**
         * GPS卫星数量更新回调
         * @param satelliteCount 卫星数量
         */
        fun onSatelliteCountUpdated(satelliteCount: Int)
        
        /**
         * 错误回调
         * @param errorMessage 错误信息
         */
        fun onError(errorMessage: String)
    }
}
