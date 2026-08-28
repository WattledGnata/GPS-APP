package com.blazepush.feature.test.ui.tracktech

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LapVboShareContractTest {
    @Test
    fun `lap detail exposes both share menu choices and retires SAF document picker`() {
        val source = locate("feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/LapDetailScreen.kt")
            .readText()

        assertTrue(source.contains("DropdownMenu("))
        assertTrue(source.contains("R.string.detail_share_lap_vbo"))
        assertTrue(source.contains("R.string.detail_share_session_vbo"))
        assertTrue(source.contains("VboShareFileStore.writeText"))
        assertTrue(source.contains("RaceLogicVboSessionExporter.write"))
        assertTrue(source.contains("Intent.createChooser"))
        assertFalse(source.contains("ActivityResultContracts.CreateDocument"))
        assertFalse(source.contains("OpenableColumns.DISPLAY_NAME"))
    }

    @Test
    fun `manifest file provider exposes only dedicated VBO cache directory`() {
        val manifest = locate("app/src/main/AndroidManifest.xml").readText()
        val paths = locate("app/src/main/res/xml/vbo_share_paths.xml").readText()

        assertTrue(manifest.contains("androidx.core.content.FileProvider"))
        assertTrue(manifest.contains("\${applicationId}.fileprovider"))
        assertTrue(manifest.contains("@xml/vbo_share_paths"))
        assertTrue(paths.contains("<cache-path"))
        assertTrue(paths.contains("path=\"shared_vbo/\""))
        assertFalse(paths.contains("path=\".\""))
        assertFalse(paths.contains("files-path"))
        assertFalse(paths.contains("external-path"))
    }

    private fun locate(relativePath: String): File {
        val candidates = listOf(
            File(relativePath),
            File("../$relativePath"),
            File("../../$relativePath"),
            File("../../../$relativePath"),
        )
        return candidates.firstOrNull(File::isFile)
            ?: error("Cannot locate $relativePath from ${File(".").absolutePath}")
    }
}
