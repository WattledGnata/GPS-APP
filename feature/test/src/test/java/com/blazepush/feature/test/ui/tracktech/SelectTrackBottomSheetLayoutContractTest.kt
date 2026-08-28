package com.blazepush.feature.test.ui.tracktech

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectTrackBottomSheetLayoutContractTest {
    @Test
    fun `track rows reserve a stable current badge column without regressing resources`() {
        val source = locateSource().readText()

        assertTrue(source.contains("Column(modifier = Modifier.weight(1f))"))
        assertTrue(source.contains("modifier = Modifier.width(48.dp)"))
        assertTrue(source.contains("contentAlignment = Alignment.CenterEnd"))
        assertTrue(source.contains("stringResource(R.string.select_track_title)"))
        assertTrue(source.contains("stringResource(R.string.action_close)"))
        assertFalse(source.contains("Column(modifier = Modifier.weight(1f, fill = false))"))
    }

    private fun locateSource(): File {
        val relative = "feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/SelectTrackBottomSheet.kt"
        return listOf(File(relative), File("../$relative"), File("../../$relative"))
            .firstOrNull(File::isFile)
            ?: error("Cannot locate $relative")
    }
}
