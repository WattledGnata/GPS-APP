package com.blazepush.feature.test.ui.tracktech

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TrackTechReadinessPresentationContractTest {
    @Test
    fun `reachable TrackTech readiness displays share the presentation mapper`() {
        listOf(
            "LapsHomeScreen.kt",
            "TestHomeScreen.kt",
            "TrackTechTestExecutionScreen.kt",
            "DeviceHomeScreen.kt",
            "LapLiveScreen.kt",
        ).forEach { fileName ->
            val source = sourceFile(fileName).readText()
            assertTrue(
                "$fileName must consume GpsReadinessPresentationMapper",
                source.contains("GpsReadinessPresentationMapper.present("),
            )
        }
    }

    @Test
    fun `TrackTech main route never exposes readiness enum name`() {
        val offenders = sourceDirectory().walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("lapGpsReadiness.name") }
            .map(File::getName)
            .toList()

        assertTrue("enum name readiness display remains in $offenders", offenders.isEmpty())
    }

    @Test
    fun `test execution status does not use parser test-ready flag as copy source`() {
        val source = sourceFile("TrackTechTestExecutionScreen.kt").readText()

        assertTrue(source.contains("sessionViewModel.lapGpsReadiness.collectAsState()"))
        assertTrue(source.contains("connectionState = connectionState"))
        assertFalse(source.contains("isTestReady"))
    }

    private fun sourceFile(fileName: String): File = File(sourceDirectory(), fileName)
        .also { require(it.isFile) { "source file not found: ${it.absolutePath}" } }

    private fun sourceDirectory(): File {
        val userDir = File(requireNotNull(System.getProperty("user.dir")))
        return generateSequence(userDir) { it.parentFile }
            .map { File(it, TRACKTECH_SOURCE_PATH) }
            .firstOrNull(File::isDirectory)
            ?: error("TrackTech source directory not found from ${userDir.absolutePath}")
    }

    private companion object {
        const val TRACKTECH_SOURCE_PATH =
            "feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech"
    }
}
