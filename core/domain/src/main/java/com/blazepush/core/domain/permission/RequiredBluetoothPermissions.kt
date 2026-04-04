package com.blazepush.core.domain.permission

object RequiredBluetoothPermissions {
    private const val ACCESS_FINE_LOCATION = "android.permission.ACCESS_FINE_LOCATION"
    private const val BLUETOOTH_SCAN = "android.permission.BLUETOOTH_SCAN"
    private const val BLUETOOTH_CONNECT = "android.permission.BLUETOOTH_CONNECT"
    private const val BLUETOOTH = "android.permission.BLUETOOTH"
    private const val BLUETOOTH_ADMIN = "android.permission.BLUETOOTH_ADMIN"

    fun forSdk(sdkInt: Int): List<String> {
        return if (sdkInt >= 31) {
            listOf(
                ACCESS_FINE_LOCATION,
                BLUETOOTH_SCAN,
                BLUETOOTH_CONNECT
            )
        } else {
            listOf(
                ACCESS_FINE_LOCATION,
                BLUETOOTH,
                BLUETOOTH_ADMIN
            )
        }
    }
}
