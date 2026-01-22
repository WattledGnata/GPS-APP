package com.race.gps.data.model

/**
 * 蓝牙数据类，封装所有蓝牙相关的数据
 * 用于通过Flow进行统一数据分发
 */
data class BluetoothData(
    val isConnected: Boolean = false,
    val speed: Double = 0.0,
    val isTestReady: Boolean = false,
    val satelliteCount: Int = 0,
    val errorMessage: String? = null
)