package com.blazepush.core.domain.model

import com.blazepush.core.domain.CoordTransform

/**
 * GPS数据模型
 * 统一的GPS数据结构，替代原有的BluetoothData
 */
data class GpsData(
    val timestamp: Long,
    val speed: Double,           // km/h
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val bearing: Double,
    val satelliteCount: Int,
    val hdop: Double,
    val vdop: Double,
    val frequency: Double,       // Hz
    val isConnected: Boolean,    // 连接状态
    val isTestReady: Boolean,    // 测试就绪状态
    val errorMessage: String?,   // 错误信息
    val fixQuality: Int = 0,     // 0=无效定位, 1=GPS, 2=DGPS
    val isTimeSynced: Boolean = false,  // 协议时间是否对齐；未对齐时 timestamp 为 sentinel，时间 delta 计算必须跳过
    val isStale: Boolean = false  // 数据陈旧：链路仍 CONNECTED 但已 DATA_TIMEOUT_MS 无新帧（很可能在等卫星）。
                                  // 与 isConnected（语义"最近一次 parse 成功"）正交，见 ble-connection-liveness spec。
) {
    /**
     * 将 GPS 坐标 (WGS84) 转换为高德地图坐标 (GCJ-02)
     * 用于在地图上显示时调用
     */
    fun toGcj02(): GpsData {
        val (lat, lon) = CoordTransform.wgs84ToGcj02(this.latitude, this.longitude)
        return this.copy(latitude = lat, longitude = lon)
    }

    companion object {
        val Empty = GpsData(
            timestamp = 0L,
            speed = 0.0,
            latitude = 0.0,
            longitude = 0.0,
            altitude = 0.0,
            bearing = 0.0,
            satelliteCount = 0,
            hdop = 0.0,
            vdop = 0.0,
            frequency = 0.0,
            isConnected = false,
            isTestReady = false,
            errorMessage = null,
            fixQuality = 0,
            isTimeSynced = false
        )
    }
}
