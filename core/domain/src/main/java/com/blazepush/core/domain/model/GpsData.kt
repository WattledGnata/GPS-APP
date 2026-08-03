package com.blazepush.core.domain.model

import com.blazepush.core.domain.CoordTransform

/** 动态节拍尚未建立时的兜底值，也是 Main 静默判定允许的最大窗口。 */
const val GPS_MAIN_SILENCE_MAX_TIMEOUT_MS = 1_000L

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
    val isStale: Boolean = false, // 数据陈旧：链路仍 CONNECTED，但动态 Main deadline 已过。
    val connectionGeneration: Long = 0L, // 每次 connect/reconnect 递增，防止旧 GATT 回调污染新连接。
    val mainFrameSequence: Long = 0L, // 仅成功解析 GPS Main 帧时递增，全 0 帧也是新帧。
    val mainFrameReceivedAtElapsedRealtimeMs: Long = 0L, // 主帧本地单调接收时刻。
    val hasMainFrame: Boolean = false, // 当前连接代次是否已有可解析的 GPS Main 帧。
    val mainFrameSilenceTimeoutMs: Long = GPS_MAIN_SILENCE_MAX_TIMEOUT_MS, // 当前实测节拍对应的动态 deadline。
    val consecutiveReliableMainFrames: Int = 0, // 静默/无 fix 后连续满足全部计时硬条件的 Main 帧数。
    val requiredReliableMainFrames: Int = 11, // 由实测 Main cadence + 稳定时长动态计算。
    val reliableMainStableDurationMs: Long = 0L,
    val requiredReliableMainStableDurationMs: Long = 1_000L,
    val isRecoveryStable: Boolean = false, // 帧数与稳定时长双条件均满足。
    val timingHandshakeState: TimingHandshakeState = TimingHandshakeState.WAITING_MAIN,
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
