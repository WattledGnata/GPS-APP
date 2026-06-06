// @IgnoreFormatCheck
package com.blazepush.core.data.repository

import com.blazepush.core.data.local.dao.BluetoothDeviceDao
import com.blazepush.core.data.local.entity.BluetoothDeviceEntity
import com.blazepush.core.data.local.mapper.toEntity
import com.blazepush.core.data.local.mapper.toModel
import com.blazepush.core.data.model.BluetoothDeviceModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class BluetoothDeviceRepository(
    private val bluetoothDeviceDao: BluetoothDeviceDao
) {
    // Flow自动从Room获取
    val devicesFlow: Flow<List<BluetoothDeviceModel>> =
        bluetoothDeviceDao.getAllDevices()
            .map { list -> list.map { it.toModel() } }

    suspend fun saveDevices(devices: List<BluetoothDeviceModel>) {
        devices.forEach { device ->
            bluetoothDeviceDao.insertDevice(device.toEntity())
        }
    }

    suspend fun getSavedDevices(): List<BluetoothDeviceModel> {
        return bluetoothDeviceDao.getAllDevicesSync().map { it.toModel() }
    }

    suspend fun addDevice(device: BluetoothDeviceModel) {
        bluetoothDeviceDao.insertDevice(device.toEntity())
    }

    suspend fun removeDevice(address: String) {
        bluetoothDeviceDao.deleteDevice(address)
    }

    // ble-device-memory round（design Decision 2/6）——以下为设备记忆新链路。

    /**
     * 连接成功落库：不存在则插入，已存在仅刷新固件名 + 最近连接时间。
     * 两步幂等顺序执行（不要求事务，中断最坏丢一次 touch）；alias 只经 setAlias 变更。
     */
    suspend fun recordConnected(address: String, name: String?, ts: Long) {
        bluetoothDeviceDao.insertIfAbsent(
            BluetoothDeviceEntity(address = address, name = name, lastConnectedAtMs = ts)
        )
        bluetoothDeviceDao.touchConnected(address = address, name = name, ts = ts)
    }

    /** 设置/清除别名（null 或空白 = 还原固件名显示）。 */
    suspend fun setAlias(address: String, alias: String?) {
        bluetoothDeviceDao.updateAlias(address, alias)
    }

    /** 冷启动自动连目标：lastConnectedAtMs 最大者，无记录返回 null。 */
    suspend fun getLastConnectedDevice(): BluetoothDeviceModel? {
        return bluetoothDeviceDao.getLastConnectedDevice()?.toModel()
    }
}
