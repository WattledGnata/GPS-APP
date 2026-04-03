package com.blazepush.simulator.ble

object GpsMainPayloadPolicy {
    private const val MAIN_PAYLOAD_SIZE = 20

    fun canNotify(data: ByteArray): Boolean = data.size == MAIN_PAYLOAD_SIZE
}
