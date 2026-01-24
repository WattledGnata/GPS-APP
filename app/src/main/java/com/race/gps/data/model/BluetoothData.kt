package com.race.gps.data.model

/**
 * 蓝牙数据类，封装所有蓝牙相关的数据
 * 用于通过Flow进行统一数据分发
 */
data class BluetoothData(
    val isConnected: Boolean = false,
    val speed: Double = 0.0, // km/h
    val isTestReady: Boolean = false,
    val satelliteCount: Int = 0,
    val errorMessage: String? = null,
    
    // GPS Data fields from RealTimeData
    val time: Long = System.currentTimeMillis(),
    val dop: String = "0.00",
    val positionType: Int = 0,
    val azimuth: Int = 0,
    val altitude: String = "0.0",
    val altitudeError: String = "0.00",
    val latitude: String = "0.0",
    val longitude: String = "0.0",
    val elapsedTime: String = "0.0",
    val distance: String = "0.0",
    val frequency: String = "0.0" // GPS update frequency in Hz
)
