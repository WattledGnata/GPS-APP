// @IgnoreFormatCheck
package com.blazepush.core.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Test

class BleConnectionBatteryTest {

    @Test
    fun parseBatteryPercent_85() {
        assertEquals(85, BleConnection.parseBatteryPercent(byteArrayOf(0x55.toByte())))
    }

    @Test
    fun parseBatteryPercent_zero() {
        assertEquals(0, BleConnection.parseBatteryPercent(byteArrayOf(0x00)))
    }

    @Test
    fun parseBatteryPercent_100() {
        assertEquals(100, BleConnection.parseBatteryPercent(byteArrayOf(0x64.toByte())))
    }

    @Test
    fun parseBatteryPercent_over100_returnsNull() {
        assertEquals(null, BleConnection.parseBatteryPercent(byteArrayOf(0x65.toByte())))
    }

    @Test
    fun parseBatteryPercent_emptyArray_returnsNull() {
        assertEquals(null, BleConnection.parseBatteryPercent(byteArrayOf()))
    }

    @Test
    fun parseBatteryPercent_null_returnsNull() {
        assertEquals(null, BleConnection.parseBatteryPercent(null))
    }

    @Test
    fun parseBatteryPercent_255_returnsNull() {
        assertEquals(null, BleConnection.parseBatteryPercent(byteArrayOf(0xFF.toByte())))
    }
}
