package com.blazepush.simulator.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SimulatorViewModelReplayModeTest {

    @Test
    fun `enableReplayMode marks replay mode and locks frequency to 5hz`() {
        val viewModel = SimulatorViewModel()

        viewModel.setFrequency(12)
        viewModel.enableReplayMode()

        assertReplayState(
            viewModel = viewModel,
            isReplayMode = true,
            frequency = 5,
            dataSourceLabel = "圈速模拟回放"
        )
    }

    @Test
    fun `disableReplayMode returns to manual mode label`() {
        val viewModel = SimulatorViewModel()

        viewModel.enableReplayMode()
        viewModel.disableReplayMode()

        assertReplayState(
            viewModel = viewModel,
            isReplayMode = false,
            frequency = 5,
            dataSourceLabel = "手动模拟"
        )
    }

    @Test
    fun `setFrequency ignores manual updates while replay mode is enabled`() {
        val viewModel = SimulatorViewModel()

        viewModel.enableReplayMode()
        viewModel.setFrequency(12)

        assertEquals(5, viewModel.uiState.value.frequency)
    }

    private fun assertReplayState(
        viewModel: SimulatorViewModel,
        isReplayMode: Boolean,
        frequency: Int,
        dataSourceLabel: String
    ) {
        val state = viewModel.uiState.value
        assertEquals(isReplayMode, state.isReplayMode)
        assertEquals(frequency, state.frequency)
        assertEquals(dataSourceLabel, state.dataSourceLabel)
    }
}
