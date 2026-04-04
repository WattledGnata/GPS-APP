package com.blazepush.core.domain.permission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RequiredBluetoothPermissionsTest {

    @Test
    fun `android 12 and above requests bluetooth scan connect and fine location`() {
        val permissions = RequiredBluetoothPermissions.forSdk(33)

        assertEquals(
            listOf(
                "android.permission.ACCESS_FINE_LOCATION",
                "android.permission.BLUETOOTH_SCAN",
                "android.permission.BLUETOOTH_CONNECT"
            ),
            permissions
        )
    }

    @Test
    fun `android 11 and below include classic bluetooth permissions`() {
        val permissions = RequiredBluetoothPermissions.forSdk(30)

        assertTrue(permissions.contains("android.permission.BLUETOOTH"))
        assertTrue(permissions.contains("android.permission.BLUETOOTH_ADMIN"))
        assertTrue(permissions.contains("android.permission.ACCESS_FINE_LOCATION"))
    }
}
