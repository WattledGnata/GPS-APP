// @IgnoreFormatCheck
package com.blazepush.feature.test.permission

import com.blazepush.core.domain.permission.RequiredCameraPermissions
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * camera-module-and-permission round · RequiredCameraPermissions 单测。
 * @author CC
 * @date 2026-05-30
 */
class RequiredCameraPermissionsTest {

    @Test
    fun forSdk_returnsCameraAndRecordAudio() {
        assertEquals(
            listOf("android.permission.CAMERA", "android.permission.RECORD_AUDIO"),
            RequiredCameraPermissions.forSdk(34),
        )
    }

    @Test
    fun forSdk_noSdkBranch_sameAcrossVersions() {
        assertEquals(
            RequiredCameraPermissions.forSdk(28),
            RequiredCameraPermissions.forSdk(34),
        )
    }
}
