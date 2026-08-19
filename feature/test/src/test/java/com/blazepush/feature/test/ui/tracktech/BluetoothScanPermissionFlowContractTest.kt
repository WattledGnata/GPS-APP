package com.blazepush.feature.test.ui.tracktech

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BluetoothScanPermissionFlowContractTest {

    @Test
    fun `every active scan entry uses the runtime permission gate`() {
        val deviceHome = readProjectFile(DEVICE_HOME_PATH)
        val scanSheet = readProjectFile(SCAN_SHEET_PATH)

        assertTrue(deviceHome.contains("onScanClick = requestScan"))
        assertTrue(deviceHome.contains("onScanAgain = requestScan"))
        assertTrue(deviceHome.contains("if (pendingShowScanSheet) {\n            requestScan()"))
        assertTrue(scanSheet.contains("onClick = onScanAgain"))
        assertFalse(
            "SCAN AGAIN must not bypass the UI permission gate",
            scanSheet.contains("clickable(enabled = !isScanning) { gpsViewModel.startScan() }"),
        )
    }

    @Test
    fun `grant resumes scan and permanent denial has settings recovery`() {
        val deviceHome = readProjectFile(DEVICE_HOME_PATH)

        assertTrue(deviceHome.contains("PermissionRequestOutcome.AllGranted -> startScanNow()"))
        assertTrue(deviceHome.contains("shouldShowRequestPermissionRationale"))
        assertTrue(deviceHome.contains("Settings.ACTION_APPLICATION_DETAILS_SETTINGS"))
        assertTrue(deviceHome.contains("event == Lifecycle.Event.ON_RESUME"))
        assertTrue(deviceHome.contains("pendingScanAfterPermission"))
    }

    private fun readProjectFile(path: String): String {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        val projectRoot = generateSequence(File(userDir)) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle").isFile }
            ?: error("Project root not found from $userDir")
        return File(projectRoot, path).readText()
    }

    private companion object {
        const val DEVICE_HOME_PATH =
            "feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/DeviceHomeScreen.kt"
        const val SCAN_SHEET_PATH =
            "feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/BleScanBottomSheet.kt"
    }
}
