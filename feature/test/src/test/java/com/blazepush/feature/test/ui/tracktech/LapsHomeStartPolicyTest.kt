package com.blazepush.feature.test.ui.tracktech

import com.blazepush.feature.test.repository.PresetTrackCatalog
import com.blazepush.core.domain.model.TelemetrySession
import com.blazepush.core.domain.model.TelemetrySessionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LapsHomeStartPolicyTest {
    @Test
    fun `selected track starts lap mode and navigates without GPS readiness input`() {
        val track = requireNotNull(PresetTrackCatalog().getTrack("preset-tfic-lpcc"))
        val selectedTrackIds = mutableListOf<String>()
        var consumed = false
        var autoRecord: Boolean? = null
        var navigationCount = 0

        val started = startLapSession(
            track = track,
            recordThisSession = true,
            selectLapDebugMode = selectedTrackIds::add,
            consumeRecordingChoice = { consumed = true },
            navigateToLapLive = {
                autoRecord = it
                navigationCount++
            },
        )

        assertTrue(started)
        assertEquals(listOf(track.id), selectedTrackIds)
        assertTrue(consumed)
        assertEquals(true, autoRecord)
        assertEquals(1, navigationCount)
    }

    @Test
    fun `missing selected track performs no partial start or navigation`() {
        var selectCount = 0
        var consumeCount = 0
        var navigationCount = 0

        val started = startLapSession(
            track = null,
            recordThisSession = true,
            selectLapDebugMode = { selectCount++ },
            consumeRecordingChoice = { consumeCount++ },
            navigateToLapLive = { navigationCount++ },
        )

        assertFalse(started)
        assertEquals(0, selectCount)
        assertEquals(0, consumeCount)
        assertEquals(0, navigationCount)
    }

    @Test
    fun `recording choice defaults off after denial and only turns on after grant`() {
        assertFalse(recordingChoiceAfterPermissionResult(allGranted = false))
        assertTrue(recordingChoiceAfterPermissionResult(allGranted = true))
    }

    @Test
    fun `auto recording gate attempts only once when camera and permission are ready`() {
        assertFalse(
            shouldAttemptAutoRecording(
                requested = true,
                cameraAvailabilityResolved = false,
                hasCamera = false,
                permissionGranted = true,
                cameraBindingReady = false,
                recordingActive = false,
                alreadyAttempted = false,
            ),
        )
        assertFalse(
            shouldAttemptAutoRecording(
                requested = true,
                cameraAvailabilityResolved = true,
                hasCamera = true,
                permissionGranted = false,
                cameraBindingReady = false,
                recordingActive = false,
                alreadyAttempted = false,
            ),
        )
        assertTrue(
            shouldAttemptAutoRecording(
                requested = true,
                cameraAvailabilityResolved = true,
                hasCamera = true,
                permissionGranted = true,
                cameraBindingReady = true,
                recordingActive = false,
                alreadyAttempted = false,
            ),
        )
        assertFalse(
            shouldAttemptAutoRecording(
                requested = true,
                cameraAvailabilityResolved = true,
                hasCamera = true,
                permissionGranted = true,
                cameraBindingReady = true,
                recordingActive = false,
                alreadyAttempted = true,
            ),
        )
        assertFalse(
            shouldAttemptAutoRecording(
                requested = true,
                cameraAvailabilityResolved = true,
                hasCamera = true,
                permissionGranted = true,
                cameraBindingReady = true,
                recordingActive = true,
                alreadyAttempted = false,
            ),
        )
        assertTrue(isCurrentAutoBindCallback(callbackGeneration = 3, currentGeneration = 3))
        assertFalse(isCurrentAutoBindCallback(callbackGeneration = 2, currentGeneration = 3))
    }

    @Test
    fun `recording off still starts a fresh lap session and navigates`() {
        val track = requireNotNull(PresetTrackCatalog().getTrack("preset-tfic-lpcc"))
        val calls = mutableListOf<String>()

        val started = startLapSession(
            track = track,
            recordThisSession = false,
            selectLapDebugMode = { calls += "start:$it" },
            consumeRecordingChoice = { calls += "consume" },
            navigateToLapLive = { calls += "navigate:$it" },
        )

        assertTrue(started)
        assertEquals(
            listOf("start:${track.id}", "consume", "navigate:false"),
            calls,
        )
    }

    @Test
    fun `run again creates a fresh session and consumes the current recording choice`() {
        val track = requireNotNull(PresetTrackCatalog().getTrack("preset-tfic-lpcc"))
        val calls = mutableListOf<String>()

        quickStartPreviousTrack(
            track = track,
            recordThisSession = true,
            selectTrack = { calls += "select:${it.id}" },
            selectLapDebugMode = { calls += "start:$it" },
            consumeRecordingChoice = { calls += "consume" },
            navigateToLapLive = { calls += "navigate:$it" },
        )

        assertEquals(
            listOf("select:${track.id}", "start:${track.id}", "consume", "navigate:true"),
            calls,
        )
    }

    @Test
    fun `run again track must match the displayed session and current track`() {
        val catalog = PresetTrackCatalog()
        val trackA = requireNotNull(catalog.getTrack("preset-tfic-lpcc"))
        val trackB = requireNotNull(catalog.getTrack("preset-xic-lpcc"))
        val displayedSession = TelemetrySession(
            sessionId = "session-a",
            sessionType = TelemetrySessionType.LAP_SESSION,
            startTs = 1L,
            endTs = 2L,
            binaryFilePath = "session-a.bin",
            trackId = trackA.id,
        )

        assertEquals(trackA, resolveRunAgainTrack(displayedSession, trackA, listOf(trackA, trackB)))
        assertEquals(null, resolveRunAgainTrack(displayedSession, trackB, listOf(trackA, trackB)))
        assertEquals(null, resolveRunAgainTrack(displayedSession, trackA, listOf(trackB)))
        assertEquals(null, resolveRunAgainTrack(null, trackA, listOf(trackA)))
    }

    @Test
    fun `START action delegates directly without tab readiness gate`() {
        val source = readSource()
        val startAction = source.substringAfter("title = stringResource(R.string.action_start_lap_session)")
            .substringBefore("SecondaryActionPanel(")

        assertTrue(startAction.contains("startLapSession("))
        assertFalse(startAction.contains("canEnterTestFlow"))
        assertFalse(startAction.contains("computeTabReadiness"))
        assertFalse(startAction.contains("requestShowScanSheet"))
        assertFalse(startAction.contains("onTabSelected"))
        assertTrue(startAction.contains("consumeRecordingChoice"))
        assertTrue(startAction.contains("lap_live?autoRecord="))
    }

    @Test
    fun `laps home keeps the locked information hierarchy`() {
        val source = readSource()
        val status = source.indexOf("TrackTechStatusStrip(")
        val track = source.indexOf("CurrentTrackPanel(")
        val recording = source.indexOf("RecordingChoiceRow(")
        val start = source.indexOf("PrimaryActionPanel(")
        val lastSession = source.indexOf("LastSessionPanel(")

        assertTrue(status >= 0)
        assertTrue(status < track)
        assertTrue(track < recording)
        assertTrue(recording < start)
        assertTrue(start < lastSession)
    }

    @Test
    fun `recording choice survives rotation but every successful session consumes it`() {
        val source = readSource()

        assertTrue(source.contains("var recordThisSession by rememberSaveable"))
        assertTrue(source.contains("resolveRunAgainTrack("))
        assertTrue(source.contains("quickStartPreviousTrack("))
        assertTrue(source.contains("action_run_again"))
        assertFalse(source.contains("val recentTrackIds by"))
        val debugCaptureAction = source.substringAfter("title = stringResource(R.string.action_free_capture)")
            .substringBefore("Spacer(Modifier.height")
        assertEquals(1, debugCaptureAction.split("recordThisSession = false").size - 1)
        assertTrue(debugCaptureAction.contains("lap_live?autoRecord=false"))
        assertFalse(source.contains("Preset direction"))
        assertFalse(source.contains("预设方向"))
    }

    @Test
    fun `auto recording failure feedback cannot end or navigate the lap session`() {
        val source = readLapLiveSource()
        val autoStartBlock = source.substringAfter("// WP2: one-shot asynchronous auto REC")
            .substringBefore("DisposableEffect(Unit)")

        assertTrue(autoStartBlock.contains("record_this_session_start_failed"))
        assertTrue(autoStartBlock.contains("prepareActiveLapSessionForRecording"))
        assertTrue(autoStartBlock.contains("cameraBindingReady"))
        assertFalse(autoStartBlock.contains("AUTO_RECORD_BIND_SETTLE_MS"))
        assertFalse(autoStartBlock.contains("finishActiveLapSession"))
        assertFalse(autoStartBlock.contains("popBackStack"))
        assertFalse(autoStartBlock.contains("navigate("))
    }

    private fun readSource(): String {
        val userDir = File(requireNotNull(System.getProperty("user.dir")))
        val source = generateSequence(userDir) { current -> current.parentFile }
            .map { root -> File(root, LAPS_HOME_PATH) }
            .firstOrNull(File::exists)
            ?: error("source file not found from ${userDir.absolutePath}: $LAPS_HOME_PATH")
        return source.readText()
    }

    private fun readLapLiveSource(): String {
        val userDir = File(requireNotNull(System.getProperty("user.dir")))
        val source = generateSequence(userDir) { current -> current.parentFile }
            .map { root -> File(root, LAP_LIVE_PATH) }
            .firstOrNull(File::exists)
            ?: error("source file not found from ${userDir.absolutePath}: $LAP_LIVE_PATH")
        return source.readText()
    }

    private companion object {
        const val LAPS_HOME_PATH =
            "feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/LapsHomeScreen.kt"
        const val LAP_LIVE_PATH =
            "feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/LapLiveScreen.kt"
    }
}
