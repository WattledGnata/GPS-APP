package com.blazepush.feature.test.ui.tracktech

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TruthfulUxLocalizationContractTest {
    private val root = projectRoot()
    private val trackTech = File(
        root,
        "feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech",
    )

    @Test
    fun `decorative top actions and fake filter are absent`() {
        val testHome = File(trackTech, "TestHomeScreen.kt").readText()
        val lapsHome = File(trackTech, "LapsHomeScreen.kt").readText()
        val records = File(trackTech, "RecordsHomeScreen.kt").readText()
        val device = File(trackTech, "DeviceHomeScreen.kt").readText()

        assertFalse(testHome.contains("HelpOutline"))
        assertFalse(lapsHome.contains("HelpOutline"))
        assertFalse(records.contains("FilterAlt"))
        assertFalse(records.contains("Filter coming"))
        assertFalse(device.contains("contentDescription = stringResource(R.string.action_settings)"))
        assertTrue(device.contains("onClick = { navController.navigate(\"settings\") }"))
    }

    @Test
    fun `driver name never blocks startup and is requested only when enabling upload`() {
        val device = File(trackTech, "DeviceHomeScreen.kt").readText()
        val settings = File(
            root,
            "feature/test/src/main/java/com/blazepush/feature/test/ui/settings/SettingsScreen.kt",
        ).readText()
        val profile = File(
            root,
            "feature/test/src/main/java/com/blazepush/feature/test/datastore/UserProfileRepository.kt",
        ).readText()

        assertFalse(device.contains("hasShownDriverNamePrompt"))
        assertFalse(device.contains("showDriverPrompt"))
        assertTrue(settings.contains("enabled && draft.trim().isEmpty()"))
        assertTrue(settings.contains("showDriverNameRequired = true"))
        assertTrue(profile.contains("prefs[KEY_LIVETIMING_ENABLED] ?: false"))
    }

    @Test
    fun `default and English resources expose identical keys`() {
        val defaultKeys = resourceKeys(File(root, "feature/test/src/main/res/values/strings.xml"))
        val englishKeys = resourceKeys(File(root, "feature/test/src/main/res/values-en/strings.xml"))
        assertEquals(defaultKeys, englishKeys)
    }

    @Test
    fun `all reviewed reachable screens have no hardcoded user-facing English`() {
        val files = listOf(
            "TestHomeScreen.kt",
            "LapsHomeScreen.kt",
            "RecordsHomeScreen.kt",
            "DeviceHomeScreen.kt",
            "TrackTechTestExecutionScreen.kt",
            "LapComparisonScreen.kt",
            "TrackThumbnail.kt",
            "PerformanceResultScreen.kt",
            "GpsDetailsScreen.kt",
        ).map { File(trackTech, it) } + File(
            root,
            "feature/test/src/main/java/com/blazepush/feature/test/ui/settings/SettingsScreen.kt",
        )
        val userFacingLiteral = Regex(
            """(?:text|title|subtitle|contentDescription|label|status)\s*=\s*\"([A-Za-z][^\"]*)\"""",
        )
        val allowedTechnical = setOf("GPS", "BLE", "HDOP", "RSSI", "km/h", "s")
        val hits = files.flatMap { file ->
            userFacingLiteral.findAll(file.readText()).mapNotNull { match ->
                match.groupValues[1].takeUnless { it in allowedTechnical }
                    ?.let { "${file.name}: $it" }
            }
        }
        assertTrue("Hardcoded user-facing English: $hits", hits.isEmpty())
    }

    @Test
    fun `Lap Live diagnostic English is confined to debug capture dashboard`() {
        val source = File(trackTech, "LapLiveScreen.kt").readText()
        val debugDashboard = source.substringAfter("private fun DebugCaptureDashboard(")
        assertTrue(source.contains("WP3 本地化边界"))
        listOf("PHONE TIME", "GPS TIME", "RX AGE").forEach { diagnosticTerm ->
            assertFalse(source.substringBefore("private fun DebugCaptureDashboard(").contains(diagnosticTerm))
            assertTrue(debugDashboard.contains(diagnosticTerm))
        }
    }

    private fun resourceKeys(file: File): Set<String> =
        Regex("""<string\s+name=\"([^\"]+)\"""")
            .findAll(file.readText())
            .map { it.groupValues[1] }
            .toSet()

    private fun projectRoot(): File = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "settings.gradle").isFile || File(it, "settings.gradle.kts").isFile }
}
