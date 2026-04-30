// @IgnoreFormatCheck
// 理由：本文件由 change fix-gps-stats-and-lazy-catalog-hot-start（战役 F Round 2 A37）
//       新建。JUnit4 测试类命名 snake_case 承载 Gherkin 语义，且需要访问 private
//       `_availableTracks` 字段做构造期空列表断言 —— kt-check 的 class-comment /
//       method-name 与测试契约本质不兼容。评审方 2026-04-25 战役 G B 方案纪律批准 ignore。
package com.blazepush.feature.test.viewmodel

import com.blazepush.core.bluetooth.BleDeviceManager
import com.blazepush.core.data.repository.TelemetryRepository
import com.blazepush.core.data.repository.TestResultRepository
import com.blazepush.core.domain.model.ConnectionState
import com.blazepush.core.domain.usecase.CalculateResultUseCase
import com.blazepush.core.domain.usecase.GpsDataFilter
import com.blazepush.core.domain.usecase.SmartTestLauncher
import com.blazepush.feature.test.model.laptiming.GpsSample
import com.blazepush.feature.test.repository.PresetTrackCatalog
import com.blazepush.feature.test.repository.ReplayAlignedTrackCatalog
import com.blazepush.feature.test.repository.ReplayTrackSource
import com.blazepush.feature.test.usecase.LapTimingEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import java.io.File

/**
 * A37 change fix-gps-stats-and-lazy-catalog-hot-start：
 * 验证 TestSessionViewModel 构造期不触发 catalog asset IO（spec R4 Scenario
 * "构造期零 IO 触发" + "launch 完成后非空"）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TestSessionViewModelTrackLoadingTest {

    private companion object {
        private const val REPLAY_JSON_PATH = "feature/test/src/main/assets/replay/tianfu_track_replay_5hz.json"
        private const val REPLAY_VBO_PATH = "feature/test/src/main/assets/replay/tianfu_track.vbo"
    }

    private val dispatcher = StandardTestDispatcher()

    /**
     * spec R4 Scenario "构造期零 IO 触发"：
     * 用 ReplayAlignedTrackCatalog + fake source 验证构造期不同步读 asset。
     *
     * 注意：不验证 launch 完成后结果 —— ReplayAlignedTrackCatalog.getAllTracks
     * 内部 withContext(Dispatchers.IO) 会切到真实 IO 池，StandardTestDispatcher
     * 的 advanceUntilIdle 无法推进。launch 完成后的非空断言放到独立测试
     * `init_availableTracksLoadedAfterAsyncLaunch_usingPresetCatalog` 里用
     * PresetTrackCatalog（内存直返、不切 IO）覆盖。
     */
    @Test
    fun init_availableTracksStartsEmpty_noAssetIoOnConstruction() = runTest {
        Dispatchers.setMain(dispatcher)
        try {
            var loadReplayCount = 0
            var loadVboCount = 0
            val fakeSource = object : ReplayTrackSource {
                override fun loadReplayJson(): String {
                    loadReplayCount++
                    return File(projectRoot(), REPLAY_JSON_PATH).readText()
                }
                override fun loadTrackVbo(): String {
                    loadVboCount++
                    return File(projectRoot(), REPLAY_VBO_PATH).readText()
                }
            }
            val trackCatalog = ReplayAlignedTrackCatalog(fakeSource, PresetTrackCatalog())

            val viewModel = createViewModel(trackCatalog)

            // 构造期零 IO 触发（StandardTestDispatcher 未 advance，launch 未执行）
            assertEquals(
                "ViewModel 构造返回后 availableTracks 应为空列表（加载态）",
                emptyList<Any>(),
                viewModel.availableTracks.value,
            )
            assertEquals("构造期 loadReplayJson 不应被调用", 0, loadReplayCount)
            assertEquals("构造期 loadTrackVbo 不应被调用", 0, loadVboCount)
        } finally {
            Dispatchers.resetMain()
        }
    }

    /**
     * spec R4 Scenario "launch 完成后 availableTracks 非空"：
     * 用 PresetTrackCatalog（纯内存，不切 IO）验证异步 launch 完成后 StateFlow
     * 被填充。PresetTrackCatalog 的 suspend 不走 withContext，TestDispatcher
     * 的 advanceUntilIdle 能推进到完成。
     */
    @Test
    fun init_availableTracksLoadedAfterAsyncLaunch_usingPresetCatalog() = runTest {
        Dispatchers.setMain(dispatcher)
        try {
            val trackCatalog = PresetTrackCatalog()

            val viewModel = createViewModel(trackCatalog)

            // 构造期空（launch 未推进）
            assertEquals(emptyList<Any>(), viewModel.availableTracks.value)

            // 推进 launch 完成
            dispatcher.scheduler.advanceUntilIdle()

            // launch 完成后 availableTracks 非空并含 TFIC
            assertTrue(
                "launch 完成后 availableTracks 应非空并包含 TFIC",
                viewModel.availableTracks.value.any { it.id == "preset-tfic-lpcc" },
            )
        } finally {
            Dispatchers.resetMain()
        }
    }

    /**
     * A37 spec R4 Scenario "launch 不指定 Dispatchers.IO"：源码断言。
     * ViewModel 不应预设 dispatcher（IO 边界唯一防线在 catalog 实现侧）。
     */
    @Test
    fun testSessionViewModel_sourceDoesNotSpecifyDispatchersIOForTrackLoading() {
        val source = File(
            projectRoot(),
            "feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt",
        ).readText()
        // 禁止 `viewModelScope.launch(Dispatchers.IO)` 在 _availableTracks 加载路径
        // 简化：整文件禁止 `launch(Dispatchers.IO)`（当前 TestSessionViewModel 也确实未使用）
        assertTrue(
            "TestSessionViewModel 不应为 track 加载指定 Dispatchers.IO（IO 边界在 catalog 实现侧）",
            !source.contains("viewModelScope.launch(Dispatchers.IO)"),
        )
    }

    private fun createViewModel(
        trackCatalog: com.blazepush.feature.test.repository.TrackCatalog
    ): TestSessionViewModel {
        val gpsFlow = MutableStateFlow<com.blazepush.core.domain.model.GpsData>(
            com.blazepush.core.domain.model.GpsData.Empty
        )
        val connectionState = MutableStateFlow(ConnectionState.CONNECTED)

        val gpsDataViewModel = mock(GpsDataViewModel::class.java)
        doReturn(gpsFlow).`when`(gpsDataViewModel).gpsData

        val bleDeviceManager = mock(BleDeviceManager::class.java)
        doReturn(connectionState).`when`(bleDeviceManager).connectionState

        return TestSessionViewModel(
            gpsDataViewModel = gpsDataViewModel,
            bleDeviceManager = bleDeviceManager,
            testResultRepository = mock(TestResultRepository::class.java),
            calculateResultUseCase = mock(CalculateResultUseCase::class.java),
            smartTestLauncher = mock(SmartTestLauncher::class.java),
            gpsDataFilter = GpsDataFilter(),
            trackCatalog = trackCatalog,
            lapTimingEngine = LapTimingEngine(),
            telemetryRepository = mock(TelemetryRepository::class.java),
        )
    }

    private fun projectRoot(): File {
        val classesDir = File(javaClass.protectionDomain.codeSource.location.toURI())
        val userDir = File(System.getProperty("user.dir"))
        return sequenceOf(classesDir, userDir)
            .flatMap { start -> generateSequence(start) { current -> current.parentFile }.filterNotNull() }
            .first { File(it, "settings.gradle").exists() || File(it, "settings.gradle.kts").exists() }
    }

    @Suppress("unused")
    private fun emptyGpsSample() = GpsSample(
        timestampMillis = 0L,
        latitude = 0.0,
        longitude = 0.0,
        speedKmh = 0.0,
    )
}
