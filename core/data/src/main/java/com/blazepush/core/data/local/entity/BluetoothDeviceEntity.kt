// @IgnoreFormatCheck
package com.blazepush.core.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * ble-device-memory round（v8）：加 alias + lastConnectedAtMs 两个 nullable 列。
 * - alias NULL = 用户未设别名（显示 fallback 固件名）
 * - lastConnectedAtMs NULL = 无成功连接记录（冷启动自动连查询天然排除，不用 0 哨兵——盲点 #6）
 */
@Entity(tableName = "bluetooth_devices")
data class BluetoothDeviceEntity(
    @PrimaryKey val address: String,
    val name: String?,
    val alias: String? = null,
    val lastConnectedAtMs: Long? = null
)
