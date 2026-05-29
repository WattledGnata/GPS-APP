// @IgnoreFormatCheck
package com.blazepush.feature.test.ui.tracktech

import com.blazepush.core.domain.model.GpsDataPoint
import com.blazepush.core.domain.model.SpeedSegment
import com.blazepush.core.domain.model.TestResult
import com.blazepush.core.domain.model.TestSession
import com.blazepush.core.domain.model.TestState
import com.blazepush.core.domain.model.TestTemplate
import com.blazepush.feature.test.viewmodel.TestMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackTechTestExecutionProgressTest {

    private fun runningSession(firstSpeed: Double? = null): TestState.Running {
        val session = TestSession(
            id = "session-1",
            template = TestTemplate.Acceleration0To100,
            carModel = "test-car",
            startTime = 0L,
        )
        if (firstSpeed != null) {
            session.dataPoints.add(
                GpsDataPoint(
                    elapsedTime = 0.0,
                    speed = firstSpeed,
                    latitude = 0.0,
                    longitude = 0.0,
                    altitude = 0.0,
                )
            )
        }
        return TestState.Running(session)
    }

    @Test
    fun acceleration_speed_zero_waitingForLaunch_true_progress_zero() {
        val state = computeProgressState(runningSession(), TestMode.Acceleration, speed = 0.0)
        assertEquals(0f, state.progress, 1e-6f)
        assertTrue(state.waitingForLaunch)
    }

    @Test
    fun acceleration_speed_just_below_threshold_waitingForLaunch_true() {
        val state = computeProgressState(runningSession(), TestMode.Acceleration, speed = 2.9)
        assertTrue(state.waitingForLaunch)
    }

    @Test
    fun acceleration_speed_at_threshold_waitingForLaunch_false() {
        val state = computeProgressState(runningSession(), TestMode.Acceleration, speed = 3.0)
        assertFalse(state.waitingForLaunch)
    }

    @Test
    fun acceleration_speed_5_progress_005() {
        val state = computeProgressState(runningSession(), TestMode.Acceleration, speed = 5.0)
        assertEquals(0.05f, state.progress, 1e-6f)
        assertFalse(state.waitingForLaunch)
    }

    @Test
    fun acceleration_speed_50_progress_half() {
        val state = computeProgressState(runningSession(), TestMode.Acceleration, speed = 50.0)
        assertEquals(0.5f, state.progress, 1e-6f)
        assertFalse(state.waitingForLaunch)
    }

    @Test
    fun acceleration_speed_100_progress_one() {
        val state = computeProgressState(runningSession(), TestMode.Acceleration, speed = 100.0)
        assertEquals(1f, state.progress, 1e-6f)
        assertFalse(state.waitingForLaunch)
    }

    @Test
    fun acceleration_speed_120_clamped_to_one() {
        val state = computeProgressState(runningSession(), TestMode.Acceleration, speed = 120.0)
        assertEquals(1f, state.progress, 1e-6f)
        assertFalse(state.waitingForLaunch)
    }

    @Test
    fun braking_at_start_speed_progress_zero() {
        val state = computeProgressState(
            runningSession(firstSpeed = 100.0),
            TestMode.Braking,
            speed = 100.0,
        )
        assertEquals(0f, state.progress, 1e-6f)
        assertFalse(state.waitingForLaunch)
    }

    @Test
    fun braking_half_speed_progress_half() {
        val state = computeProgressState(
            runningSession(firstSpeed = 100.0),
            TestMode.Braking,
            speed = 50.0,
        )
        assertEquals(0.5f, state.progress, 1e-6f)
        assertFalse(state.waitingForLaunch)
    }

    @Test
    fun braking_speed_zero_progress_one() {
        val state = computeProgressState(
            runningSession(firstSpeed = 100.0),
            TestMode.Braking,
            speed = 0.0,
        )
        assertEquals(1f, state.progress, 1e-6f)
        assertFalse(state.waitingForLaunch)
    }

    @Test
    fun braking_low_speed_does_not_trigger_waitingForLaunch() {
        val state = computeProgressState(
            runningSession(firstSpeed = 100.0),
            TestMode.Braking,
            speed = 2.0,
        )
        assertFalse(state.waitingForLaunch)
    }

    @Test
    fun idle_state_progress_zero_waitingForLaunch_false() {
        val state = computeProgressState(TestState.Idle, TestMode.Acceleration, speed = 0.0)
        assertEquals(0f, state.progress, 1e-6f)
        assertFalse(state.waitingForLaunch)
    }

    @Test
    fun completed_state_progress_one_waitingForLaunch_false() {
        val completed = TestState.Completed(
            TestResult(
                id = "r1",
                sessionId = "s1",
                template = TestTemplate.Acceleration0To100,
                carModel = "test-car",
                timestamp = 0L,
                totalTime = 5.0,
                totalDistance = 100.0,
                avgAcceleration = 0.5,
                maxAcceleration = 0.7,
                maxDeceleration = 0.0,
                segments = emptyList<SpeedSegment>(),
                dataPoints = emptyList<GpsDataPoint>(),
                dataFilePath = "",
            )
        )
        val state = computeProgressState(completed, TestMode.Acceleration, speed = 0.0)
        assertEquals(1f, state.progress, 1e-6f)
        assertFalse(state.waitingForLaunch)
    }
}
