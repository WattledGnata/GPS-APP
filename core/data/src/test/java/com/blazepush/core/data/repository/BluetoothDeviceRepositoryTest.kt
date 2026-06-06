// @IgnoreFormatCheck
package com.blazepush.core.data.repository

import com.blazepush.core.data.local.dao.BluetoothDeviceDao
import com.blazepush.core.data.local.entity.BluetoothDeviceEntity
import com.blazepush.core.data.model.BluetoothDeviceModel
import com.blazepush.core.data.model.displayName
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ble-device-memory：设备记忆 repository 语义锁。
 * 核心反例：recordConnected 不得清除既有 alias（绕开 REPLACE 的 insertIfAbsent+touchConnected 两步）。
 */
class BluetoothDeviceRepositoryTest {

    /** 内存 fake DAO（首个 BluetoothDeviceDao fake，实现全部接口方法——盲点 #14）。 */
    private class FakeBluetoothDeviceDao : BluetoothDeviceDao {
        val rows = LinkedHashMap<String, BluetoothDeviceEntity>()
        private val flow = MutableStateFlow<List<BluetoothDeviceEntity>>(emptyList())

        private fun emit() {
            flow.value = rows.values.toList()
        }

        override fun getAllDevices(): Flow<List<BluetoothDeviceEntity>> = flow.map { it }

        override suspend fun getAllDevicesSync(): List<BluetoothDeviceEntity> = rows.values.toList()

        override suspend fun insertDevice(device: BluetoothDeviceEntity) {
            rows[device.address] = device // REPLACE 语义：整行替换
            emit()
        }

        override suspend fun deleteDevice(address: String) {
            rows.remove(address)
            emit()
        }

        override suspend fun insertIfAbsent(device: BluetoothDeviceEntity): Long {
            if (rows.containsKey(device.address)) return -1L
            rows[device.address] = device
            emit()
            return 1L
        }

        override suspend fun touchConnected(address: String, name: String?, ts: Long) {
            val existing = rows[address] ?: return
            // 对齐生产 SQL 的 COALESCE(:name, name) 语义
            rows[address] = existing.copy(name = name ?: existing.name, lastConnectedAtMs = ts)
            emit()
        }

        override suspend fun updateAlias(address: String, alias: String?) {
            val existing = rows[address] ?: return
            rows[address] = existing.copy(alias = alias)
            emit()
        }

        override suspend fun getLastConnectedDevice(): BluetoothDeviceEntity? =
            rows.values.filter { it.lastConnectedAtMs != null }
                .maxByOrNull { it.lastConnectedAtMs!! }
    }

    private val dao = FakeBluetoothDeviceDao()
    private val repo = BluetoothDeviceRepository(dao)

    @Test
    fun recordConnected_insertsNewDevice() = runTest {
        repo.recordConnected("AA:BB", "BlazePush GPS", ts = 1000L)
        val row = dao.rows["AA:BB"]!!
        assertEquals("BlazePush GPS", row.name)
        assertEquals(1000L, row.lastConnectedAtMs)
        assertNull("新设备无别名", row.alias)
    }

    @Test
    fun recordConnected_mustNotClearExistingAlias() = runTest {
        // spec 反例：实现若走 insertDevice(REPLACE) 持久化连接事件，本断言失败
        repo.recordConnected("AA:BB", "BlazePush GPS", ts = 1000L)
        repo.setAlias("AA:BB", "老张的车")
        repo.recordConnected("AA:BB", "BlazePush GPS v2", ts = 2000L)
        val row = dao.rows["AA:BB"]!!
        assertEquals("再次连接后 alias MUST 保留", "老张的车", row.alias)
        assertEquals("固件名刷新", "BlazePush GPS v2", row.name)
        assertEquals("连接时间刷新", 2000L, row.lastConnectedAtMs)
    }

    @Test
    fun recordConnected_nullName_keepsExistingFirmwareName() = runTest {
        // 冷启动自动重连场景：不经扫描无广播名（name=null），不得把已存固件名抹成 NULL
        repo.recordConnected("AA:BB", "BlazePush GPS", ts = 1000L)
        repo.recordConnected("AA:BB", null, ts = 2000L)
        val row = dao.rows["AA:BB"]!!
        assertEquals("固件名保留", "BlazePush GPS", row.name)
        assertEquals("连接时间仍刷新", 2000L, row.lastConnectedAtMs)
    }

    @Test
    fun setAlias_thenClear_restoresNull() = runTest {
        repo.recordConnected("AA:BB", "BlazePush GPS", ts = 1000L)
        repo.setAlias("AA:BB", "老张的车")
        assertEquals("老张的车", dao.rows["AA:BB"]!!.alias)
        repo.setAlias("AA:BB", null)
        assertNull("清空别名还原 null", dao.rows["AA:BB"]!!.alias)
    }

    @Test
    fun getLastConnectedDevice_picksMaxTimestamp_excludesNull() = runTest {
        dao.rows["NULL:TS"] = BluetoothDeviceEntity("NULL:TS", "migrated-row") // 历史行无时间戳
        repo.recordConnected("OLD:01", "old", ts = 1000L)
        repo.recordConnected("NEW:02", "new", ts = 2000L)
        assertEquals("取 ts 最大者", "NEW:02", repo.getLastConnectedDevice()?.address)
    }

    @Test
    fun getLastConnectedDevice_emptyTable_returnsNull() = runTest {
        assertNull(repo.getLastConnectedDevice())
    }

    @Test
    fun displayName_priorityChain() {
        val full = BluetoothDeviceModel(name = "BlazePush GPS", address = "AA:BB", alias = "老张的车")
        assertEquals("alias 优先", "老张的车", full.displayName)

        val blankAlias = full.copy(alias = "  ")
        assertEquals("空白 alias fallback 固件名", "BlazePush GPS", blankAlias.displayName)

        val noName = BluetoothDeviceModel(name = null, address = "AA:BB")
        assertEquals("无名 fallback address", "AA:BB", noName.displayName)

        val blankName = BluetoothDeviceModel(name = "", address = "AA:BB")
        assertEquals("空串名 fallback address", "AA:BB", blankName.displayName)
    }
}
