// @IgnoreFormatCheck
package com.blazepush.core.data.model

data class BluetoothDeviceModel(
    val name: String?,
    val address: String,
    val alias: String? = null,
    val lastConnectedAtMs: Long? = null
)

/**
 * ble-device-memory round 显示名优先级（design Decision 4，所有 UI 展示点共用）：
 * alias（非空白）> 固件名（非空白）> address。
 */
val BluetoothDeviceModel.displayName: String
    get() = alias?.takeIf { it.isNotBlank() }
        ?: name?.takeIf { it.isNotBlank() }
        ?: address
