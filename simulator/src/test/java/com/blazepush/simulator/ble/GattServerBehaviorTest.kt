package com.blazepush.simulator.ble

import java.util.UUID
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GattServerBehaviorTest {

    @Test
    fun `main payload policy rejects empty heartbeat payload`() {
        assertFalse(GpsMainPayloadPolicy.canNotify(byteArrayOf()))
        assertTrue(GpsMainPayloadPolicy.canNotify(ByteArray(20)))
    }

    @Test
    fun `notification subscriptions are tracked per device and characteristic`() {
        val subscriptions = BleNotificationSubscriptions()
        val deviceA = "AA:BB:CC:DD:EE:01"
        val deviceB = "AA:BB:CC:DD:EE:02"
        val mainUuid = UUID.fromString("00000003-0000-1000-8000-00805f9b34fb")
        val timeUuid = UUID.fromString("00000004-0000-1000-8000-00805f9b34fb")

        subscriptions.update(deviceA, mainUuid, byteArrayOf(0x01, 0x00))
        subscriptions.update(deviceB, timeUuid, byteArrayOf(0x01, 0x00))

        assertTrue(subscriptions.isEnabled(deviceA, mainUuid))
        assertFalse(subscriptions.isEnabled(deviceA, timeUuid))
        assertFalse(subscriptions.isEnabled(deviceB, mainUuid))
        assertTrue(subscriptions.isEnabled(deviceB, timeUuid))
    }

    @Test
    fun `notification subscriptions disable when client writes disable value`() {
        val subscriptions = BleNotificationSubscriptions()
        val device = "AA:BB:CC:DD:EE:01"
        val mainUuid = UUID.fromString("00000003-0000-1000-8000-00805f9b34fb")

        subscriptions.update(device, mainUuid, byteArrayOf(0x01, 0x00))
        subscriptions.update(device, mainUuid, byteArrayOf(0x00, 0x00))

        assertFalse(subscriptions.isEnabled(device, mainUuid))
    }

    @Test
    fun `cccd readback reflects each device subscription state`() {
        val subscriptions = BleNotificationSubscriptions()
        val deviceA = "AA:BB:CC:DD:EE:01"
        val deviceB = "AA:BB:CC:DD:EE:02"
        val mainUuid = UUID.fromString("00000003-0000-1000-8000-00805f9b34fb")

        subscriptions.update(deviceA, mainUuid, byteArrayOf(0x01, 0x00))

        assertTrue(subscriptions.cccdValue(deviceA, mainUuid).contentEquals(byteArrayOf(0x01, 0x00)))
        assertTrue(subscriptions.cccdValue(deviceB, mainUuid).contentEquals(byteArrayOf(0x00, 0x00)))
    }
}
