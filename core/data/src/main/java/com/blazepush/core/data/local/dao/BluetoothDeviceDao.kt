// @IgnoreFormatCheck
package com.blazepush.core.data.local.dao

import androidx.room.*
import com.blazepush.core.data.local.entity.BluetoothDeviceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BluetoothDeviceDao {
    @Query("SELECT * FROM bluetooth_devices")
    fun getAllDevices(): Flow<List<BluetoothDeviceEntity>>

    @Query("SELECT * FROM bluetooth_devices")
    suspend fun getAllDevicesSync(): List<BluetoothDeviceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDevice(device: BluetoothDeviceEntity)

    @Query("DELETE FROM bluetooth_devices WHERE address = :address")
    suspend fun deleteDevice(address: String)

    // ble-device-memory round（design Decision 2/6）：
    // 连接落库走 insertIfAbsent + touchConnected 两步——MUST NOT 直接用
    // insertDevice（REPLACE 会整行替换抹掉用户已设的 alias，spec 反例锁）。

    /** 不存在则插入；已存在返回 -1 不动原行（alias 保留）。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(device: BluetoothDeviceEntity): Long

    /**
     * 连接成功后仅刷新固件名 + 最近连接时间，不碰 alias。
     * name 用 COALESCE：冷启动自动重连无广播名（name=null）时保留已存固件名，不退化为 NULL。
     */
    @Query("UPDATE bluetooth_devices SET name = COALESCE(:name, name), lastConnectedAtMs = :ts WHERE address = :address")
    suspend fun touchConnected(address: String, name: String?, ts: Long)

    /** 别名唯一变更路径。 */
    @Query("UPDATE bluetooth_devices SET alias = :alias WHERE address = :address")
    suspend fun updateAlias(address: String, alias: String?)

    /** 冷启动自动连目标：lastConnectedAtMs 最大者；NULL（无成功连接记录）天然排除。 */
    @Query("SELECT * FROM bluetooth_devices WHERE lastConnectedAtMs IS NOT NULL ORDER BY lastConnectedAtMs DESC LIMIT 1")
    suspend fun getLastConnectedDevice(): BluetoothDeviceEntity?
}
