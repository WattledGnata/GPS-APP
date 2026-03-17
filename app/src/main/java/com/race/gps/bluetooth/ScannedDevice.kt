package com.race.gps.bluetooth

/**
 * 扫描发现的BLE设备
 *
 * @property name 设备名称
 * @property address 设备MAC地址
 * @property rssi 信号强度 (dBm)
 * @property lastSeen 最后发现时间戳
 */
data class ScannedDevice(
    val name: String,
    val address: String,
    val rssi: Int,
    val lastSeen: Long = System.currentTimeMillis()
) {
    /**
     * 获取信号强度描述
     */
    fun getSignalStrength(): SignalStrength {
        return when {
            rssi >= -50 -> SignalStrength.EXCELLENT
            rssi >= -60 -> SignalStrength.GOOD
            rssi >= -70 -> SignalStrength.FAIR
            else -> SignalStrength.WEAK
        }
    }
}

/**
 * 信号强度枚举
 */
enum class SignalStrength {
    EXCELLENT,  // 优秀 (-50 dBm或更好)
    GOOD,       // 良好 (-50 到 -60 dBm)
    FAIR,       // 一般 (-60 到 -70 dBm)
    WEAK        // 弱 (差于 -70 dBm)
}