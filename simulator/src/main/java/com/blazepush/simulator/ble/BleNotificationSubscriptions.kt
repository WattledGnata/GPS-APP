package com.blazepush.simulator.ble

import java.util.UUID

class BleNotificationSubscriptions {
    private val enabledByDevice = mutableMapOf<String, MutableSet<UUID>>()

    fun update(deviceAddress: String, characteristicUuid: UUID, value: ByteArray) {
        val uuids = enabledByDevice.getOrPut(deviceAddress) { mutableSetOf() }

        if (value.contentEquals(CCCD_NOTIFY_ENABLED)) {
            uuids += characteristicUuid
        } else {
            uuids -= characteristicUuid
            if (uuids.isEmpty()) {
                enabledByDevice.remove(deviceAddress)
            }
        }
    }

    fun isEnabled(deviceAddress: String, characteristicUuid: UUID): Boolean {
        return enabledByDevice[deviceAddress]?.contains(characteristicUuid) == true
    }

    fun cccdValue(deviceAddress: String, characteristicUuid: UUID): ByteArray {
        return if (isEnabled(deviceAddress, characteristicUuid)) CCCD_NOTIFY_ENABLED else CCCD_NOTIFY_DISABLED
    }

    fun clearDevice(deviceAddress: String) {
        enabledByDevice.remove(deviceAddress)
    }

    fun clearAll() {
        enabledByDevice.clear()
    }

    private companion object {
        val CCCD_NOTIFY_ENABLED = byteArrayOf(0x01, 0x00)
        val CCCD_NOTIFY_DISABLED = byteArrayOf(0x00, 0x00)
    }
}
