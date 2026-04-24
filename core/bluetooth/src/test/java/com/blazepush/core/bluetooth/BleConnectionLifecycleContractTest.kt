package com.blazepush.core.bluetooth

import android.bluetooth.BluetoothGatt
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * @description 战役 G R1 契约测试：BLE 连接生命周期的 GATT 资源单一所有者约束。
 *              核销 spec ble-connection Requirement 1 "GATT 资源唯一所有者是 BleConnection"
 *              与 "连接相关职责单一化"。`BleConnection` MUST 是唯一持有
 *              `BluetoothGatt` 字段的类；`BluetoothDataSource` / `BleDeviceManager`
 *              / `BleDeviceScanner` MUST NOT 持有。采用 JVM reflection 验证，
 *              未来若误把 GATT 字段搬到其他类（例如为省事在 `BluetoothDataSource`
 *              里做 GATT 快捷操作），本测试会立即 fail。
 * @author haozhang93
 * @date 2026-04-24
 */
class BleConnectionLifecycleContractTest {

    /**
     * 用 reflection 枚举 4 个核心类的 declaredFields，断言 `BluetoothGatt`
     * 字段仅出现在 `BleConnection` 上，不出现在数据聚合层、设备管理层、扫描层。
     */
    @Test
    fun bleConnectionLifecycle_gattOwnership_bluetoothGattFieldOnlyInBleConnection() {
        assertTrue(
            "BleConnection MUST 持有 BluetoothGatt 字段（GATT 生命周期唯一所有者）",
            classHasBluetoothGattField(BleConnection::class.java),
        )

        assertFalse(
            "BluetoothDataSource MUST NOT 持有 BluetoothGatt 字段（数据聚合层，非 GATT 所有者）",
            classHasBluetoothGattField(BluetoothDataSource::class.java),
        )

        assertFalse(
            "BleDeviceManager MUST NOT 持有 BluetoothGatt 字段（设备管理层，非 GATT 所有者）",
            classHasBluetoothGattField(BleDeviceManager::class.java),
        )

        assertFalse(
            "BleDeviceScanner MUST NOT 持有 BluetoothGatt 字段（扫描层，非 GATT 所有者）",
            classHasBluetoothGattField(BleDeviceScanner::class.java),
        )
    }

    private fun classHasBluetoothGattField(clazz: Class<*>): Boolean {
        return clazz.declaredFields.any { field ->
            BluetoothGatt::class.java.isAssignableFrom(field.type)
        }
    }
}