package com.blazepush.feature.test.utils

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.util.Log
import com.blazepush.core.domain.model.ConnectionState
import com.blazepush.core.domain.model.GpsData

/**
 * BLE调试助手
 * 帮助识别和诊断BLE连接问题
 */
object BleDebugHelper {
    private const val TAG = "BleDebugHelper"

    /**
     * 检测假连接
     */
    fun detectFakeConnection(connectionState: ConnectionState, gpsData: GpsData): Boolean {
        // 如果显示已连接但没有数据
        val isConnected = connectionState == ConnectionState.CONNECTED
        val hasData = gpsData.satelliteCount > 0 || gpsData.speed > 0.0
        val hasTimestamp = gpsData.timestamp > 0

        if (isConnected && !hasData) {
            Log.w(TAG, "假连接检测：已连接但无数据")
            return true
        }

        if (isConnected && !hasTimestamp) {
            Log.w(TAG, "假连接检测：已连接但时间戳为0")
            return true
        }

        // 检查数据是否太旧
        val dataAge = System.currentTimeMillis() - gpsData.timestamp
        if (isConnected && dataAge > 10000) { // 10秒
            Log.w(TAG, "假连接检测：数据过期 ${dataAge}ms")
            return true
        }

        return false
    }

    /**
     * 获取连接状态描述
     */
    fun getConnectionStatusDescription(connectionState: ConnectionState): String {
        return when (connectionState) {
            ConnectionState.DISCONNECTED -> "未连接"
            ConnectionState.CONNECTING -> "连接中..."
            ConnectionState.CONNECTED -> "已连接"
            ConnectionState.DISCONNECTING -> "断开中..."
        }
    }

    /**
     * 获取GPS数据质量评级
     */
    fun getDataQualityRating(gpsData: GpsData): String {
        if (!gpsData.isConnected) return "未连接"
        if (gpsData.satelliteCount == 0) return "无卫星"
        if (gpsData.satelliteCount < 5) return "信号差"
        if (gpsData.satelliteCount < 10) return "信号中等"
        if (gpsData.satelliteCount < 15) return "信号良好"
        return "信号优秀"
    }

    /**
     * 打印连接诊断信息
     */
    fun printConnectionDiagnostics(connectionState: ConnectionState, gpsData: GpsData) {
        Log.d(TAG, "=== 连接诊断 ===")
        Log.d(TAG, "连接状态: ${getConnectionStatusDescription(connectionState)}")
        Log.d(TAG, "卫星数量: ${gpsData.satelliteCount}")
        Log.d(TAG, "速度: ${gpsData.speed} km/h")
        Log.d(TAG, "数据时间戳: ${gpsData.timestamp}")
        Log.d(TAG, "数据年龄: ${System.currentTimeMillis() - gpsData.timestamp}ms")
        Log.d(TAG, "是否测试就绪: ${gpsData.isTestReady}")

        val isFake = detectFakeConnection(connectionState, gpsData)
        Log.d(TAG, "是否假连接: $isFake")
        Log.d(TAG, "数据质量: ${getDataQualityRating(gpsData)}")
        Log.d(TAG, "===============")
    }

    /**
     * 检查CCCD描述符是否正确设置
     */
    fun checkCccdDescriptor(descriptor: BluetoothGattDescriptor?): Boolean {
        descriptor ?: return false
        val value = descriptor.value
        if (value == null || value.size < 2) {
            return false
        }
        // 检查通知位是否启用 (0x01)
        return (value[0].toInt() and 0x01) == 0x01
    }

    /**
     * 获取特征值支持的操作
     */
    fun getCharacteristicProperties(characteristic: BluetoothGattCharacteristic): String {
        return buildString {
            if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) append("读 ")
            if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) append("写 ")
            if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) append("通知 ")
            if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) append("指示 ")
            if (this.isEmpty()) append("无操作")
        }
    }
}